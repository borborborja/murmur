package dev.murmur.android

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.util.Base64
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.work.*
import dev.murmur.android.intelligence.SecretStore
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** Pairing is the opt-in. Foreground sync is immediate; background checks are constrained jobs. */
object AutoSync {
    private const val WORK = "murmur.android.local-sync"
    fun schedule(context: Context) {
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(WORK, ExistingPeriodicWorkPolicy.KEEP,
            PeriodicWorkRequestBuilder<LocalSyncWorker>(15, TimeUnit.MINUTES)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.UNMETERED).setRequiresStorageNotLow(true).build())
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS).build())
    }
    fun cancel(context: Context) = WorkManager.getInstance(context).cancelUniqueWork(WORK)

    fun install(runtime: AppRuntime, lifecycle: Lifecycle): AutoCloseable {
        val context = runtime.context
        if (runtime.config.read().optJSONObject("pairedComputer") != null) schedule(context)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val foreground = AtomicBoolean(lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED))
        val working = AtomicBoolean(false)
        fun attempt() {
            if (!foreground.get() || !working.compareAndSet(false, true)) return
            scope.launch {
                try {
                    val settings = runtime.config.read()
                    if (settings.optJSONObject("pairedComputer") == null || !settings.optBoolean("autoSync", true)) return@launch
                    if (runtime.activeId != null || runtime.processor.hasWork() || !SyncDiscovery.localNetwork(context)) return@launch
                    runtime.sync.sync()
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (error: Exception) { runtime.log.event("sync.automatic.failed", error) }
                finally { working.set(false) }
            }
        }
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_START) { foreground.set(true); attempt() }
            if (event == Lifecycle.Event.ON_STOP) foreground.set(false)
        }
        lifecycle.addObserver(observer)
        val connectivity = context.getSystemService(ConnectivityManager::class.java)
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) = attempt()
        }
        connectivity.registerDefaultNetworkCallback(callback)
        scope.launch { while (isActive) { attempt(); delay(30_000) } }
        return AutoCloseable {
            lifecycle.removeObserver(observer)
            runCatching { connectivity.unregisterNetworkCallback(callback) }
            scope.cancel()
        }
    }
}

/** No AppRuntime construction: a background sync must not restart heavy processing or capture. */
class LocalSyncWorker(context: Context, parameters: WorkerParameters) : CoroutineWorker(context, parameters) {
    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val context = applicationContext
        val settings = AppSettings(context).read()
        val computer = settings.optJSONObject("pairedComputer") ?: return@withContext Result.success()
        if (!settings.optBoolean("autoSync", true) || !SyncDiscovery.localNetwork(context)) return@withContext Result.success()
        val data = File(context.filesDir, "data")
        if (File(data, "active.json").exists()) return@withContext Result.success()
        val tasks = runCatching { JSONArray(File(data, "jobs.json").readText()).objects() }.getOrDefault(emptyList())
        if (tasks.any { it.optString("status") in listOf("running", "waiting") }) return@withContext Result.success()
        val encoded = SecretStore(context).get("phone.noise.private") ?: return@withContext Result.success()
        if (!SyncCoordinator.gate.tryLock()) return@withContext Result.success()
        try {
            // The original protocol is resumable. No network listener or unpaired peer is added.
            val addresses = linkedSetOf<String>()
            computer.optString("address").takeIf(String::isNotBlank)?.let(addresses::add)
            addresses += SyncDiscovery.discover(context)
            val engine = NativeEngine(context)
            val key = Base64.decode(encoded, Base64.NO_WRAP)
            val response = engine.call("sync", json("addresses" to JSONArray(addresses), "privateKey" to JSONArray(key.map { it.toInt() and 255 }),
                "computerKey" to computer.getJSONArray("key"), "name" to android.os.Build.MODEL,
                "keepAudio" to settings.optBoolean("keepPhoneAudio", true)))
            computer.put("address", response.getString("address"))
            AppSettings(context).update(json("pairedComputer" to computer, "lastSync" to System.currentTimeMillis()))
            TechnicalLog(context).event("sync.automatic.finished")
            Result.success()
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (error: Exception) { TechnicalLog(context).event("sync.automatic.failed", error); Result.retry() }
        finally { SyncCoordinator.gate.unlock() }
    }
}

internal object SyncDiscovery {
    fun localNetwork(context: Context): Boolean {
        val manager = context.getSystemService(ConnectivityManager::class.java)
        val capabilities = manager.getNetworkCapabilities(manager.activeNetwork ?: return false) ?: return false
        return capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) || capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)
    }
    @Suppress("DEPRECATION") suspend fun discover(context: Context): List<String> {
        val manager = context.getSystemService(NsdManager::class.java)
        val found = ConcurrentHashMap.newKeySet<String>()
        val discovery = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(type: String) = Unit
            override fun onDiscoveryStopped(type: String) = Unit
            override fun onStartDiscoveryFailed(type: String, error: Int) = Unit
            override fun onStopDiscoveryFailed(type: String, error: Int) = Unit
            override fun onServiceLost(info: NsdServiceInfo) = Unit
            override fun onServiceFound(info: NsdServiceInfo) {
                if (!info.serviceType.startsWith("_murmur._tcp")) return
                runCatching {
                    manager.resolveService(info, object : NsdManager.ResolveListener {
                        override fun onResolveFailed(service: NsdServiceInfo, error: Int) = Unit
                        override fun onServiceResolved(service: NsdServiceInfo) {
                            val host = service.host?.hostAddress ?: return
                            found += if (host.contains(':')) "[$host]:${service.port}" else "$host:${service.port}"
                        }
                    })
                }
            }
        }
        try { manager.discoverServices("_murmur._tcp.", NsdManager.PROTOCOL_DNS_SD, discovery); delay(1600) }
        finally { runCatching { manager.stopServiceDiscovery(discovery) } }
        return found.toList()
    }
}
