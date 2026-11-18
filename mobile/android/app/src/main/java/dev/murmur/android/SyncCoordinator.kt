package dev.murmur.android

import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.util.Base64
import dev.murmur.android.intelligence.SecretStore
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap

/** Protocol v1 is the original Noise-encrypted phone-to-computer transfer, not library mirroring. */
class SyncCoordinator(private val runtime: AppRuntime) {
    private val secrets = SecretStore(runtime.context)
    private val one = gate
    @Volatile private var syncing = false
    @Volatile private var lastError: String? = null

    suspend fun pair(uri: String) = one.withLock {
        val key = privateKey()
        val result = runtime.native("pair", JSONObject().put("uri", uri).put("privateKey", bytes(key))
            .put("addresses", JSONArray(discover())).put("name", android.os.Build.MODEL))
        val computer = result.getJSONObject("computer")
        runtime.config.update(JSONObject().put("pairedComputer", computer).put("lastSync", JSONObject.NULL))
        lastError = null
        AutoSync.schedule(runtime.context)
        publish()
    }

    suspend fun sync() = one.withLock {
        check(runtime.activeId == null) { "Stop the recording before synchronizing" }
        val computer = runtime.config.read().optJSONObject("pairedComputer") ?: error("No paired computer")
        syncing = true; lastError = null; publish()
        try {
            val addresses = linkedSetOf<String>()
            computer.optString("address").takeIf(String::isNotBlank)?.let(addresses::add)
            addresses += discover()
            val response = runtime.native("sync", JSONObject().put("addresses", JSONArray(addresses))
                .put("privateKey", bytes(privateKey())).put("computerKey", computer.getJSONArray("key"))
                .put("name", android.os.Build.MODEL).put("keepAudio", runtime.config.read().optBoolean("keepPhoneAudio", true)))
            computer.put("address", response.getString("address"))
            runtime.config.update(JSONObject().put("pairedComputer", computer).put("lastSync", System.currentTimeMillis()))
            runtime.refresh()
        } catch (error: Exception) {
            lastError = error.javaClass.simpleName
            throw error
        } finally { syncing = false; publish() }
    }

    suspend fun unpair() = one.withLock {
        runtime.native("forget_computer")
        runtime.config.update(JSONObject().put("pairedComputer", JSONObject.NULL).put("lastSync", JSONObject.NULL))
        AutoSync.cancel(runtime.context)
        lastError = null; publish()
    }

    suspend fun status(): JSONObject {
        val settings = runtime.config.read()
        return JSONObject().put("paired", settings.optJSONObject("pairedComputer") != null).put("computer", settings.optJSONObject("pairedComputer") ?: JSONObject.NULL)
            .put("syncing", syncing).put("lastSync", settings.opt("lastSync") ?: JSONObject.NULL)
            .put("error", lastError ?: JSONObject.NULL)
            .put("unsent", runtime.native("status").optInt("unsent"))
    }

    private suspend fun publish() {
        val status = status()
        runtime.updateState { it.copy(settings = runtime.config.read().put("syncStatus", status)) }
    }

    private suspend fun privateKey(): ByteArray {
        secrets.get("phone.noise.private")?.let { return Base64.decode(it, Base64.NO_WRAP) }
        val raw = runtime.native("new_sync_key").getJSONArray("key")
        val key = ByteArray(raw.length()) { raw.getInt(it).toByte() }
        secrets.put("phone.noise.private", Base64.encodeToString(key, Base64.NO_WRAP))
        return key
    }

    private fun bytes(raw: ByteArray) = JSONArray(raw.map { it.toInt() and 255 })

    private suspend fun discover(): List<String> = SyncDiscovery.discover(runtime.context)
    companion object { internal val gate = Mutex() }
}
