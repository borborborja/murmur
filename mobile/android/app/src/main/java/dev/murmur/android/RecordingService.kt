package dev.murmur.android

import android.app.*
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.*
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.*
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import kotlinx.coroutines.*
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean

/** Capture writes to private PCM spools before inference, so slow models never block audio. */
class RecordingService : Service() {
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO + CoroutineExceptionHandler { _, error ->
        runtime.failure("recording.worker", error)
        scope.launch { finish() }
    })
    private val running = AtomicBoolean(false)
    private val records = CopyOnWriteArrayList<AudioRecord>()
    private val captureJobs = mutableListOf<Job>()
    private var pipeline: Job? = null
    private var projection: MediaProjection? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private val runtime by lazy { AppRuntime.get(this) }
    private val spools = linkedMapOf<String, File>()

    override fun onBind(intent: Intent?) = null
    override fun onCreate() {
        super.onCreate()
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(CHANNEL, getString(R.string.app_name), NotificationManager.IMPORTANCE_LOW))
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            STOP -> scope.launch { finish() }
            MARK -> runtime.dispatch("record.mark")
            CAPTURE -> ProjectionCapture.capture()
            ENABLE_SCREEN -> scope.launch {
                try {
                    val id = runtime.activeId ?: return@launch
                    val mic = runtime.state.value.meeting?.obj("sources")?.optBoolean("mic") == true
                    ServiceCompat.startForeground(this@RecordingService, NOTIFICATION, notification(),
                        (if (mic) ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE else 0) or ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
                    attachProjection(intent, id)
                    ProjectionCapture.capture()
                } catch (error: Throwable) { runtime.failure("capture.projection", error) }
            }
            else -> if (running.compareAndSet(false, true)) {
                val options = JSONObject(intent?.getStringExtra("options") ?: "{}")
                val hasProjection = intent?.hasExtra("projectionData") == true
                var types = if (options.optBoolean("mic", true)) ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE else 0
                if (hasProjection) types = types or ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
                ServiceCompat.startForeground(this, NOTIFICATION, notification(), types)
                scope.launch {
                    try {
                        val id = runtime.beginRecording(options)
                        if (hasProjection) {
                            attachProjection(intent!!, id)
                        }
                        wakeLock = getSystemService(PowerManager::class.java).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "murmur:recording").apply { acquire(12 * 60 * 60 * 1000L) }
                        if (options.optBoolean("mic", true)) startInput("mic", id, options)
                        if (options.optBoolean("system", false)) {
                            require(projection != null)
                            startInput("system", id, options)
                        }
                        require(spools.isNotEmpty())
                        pipeline = launch { consume() }
                        if (options.optBoolean("recordingIndicator")) withContext(Dispatchers.Main) {
                            startService(Intent(this@RecordingService, OverlayService::class.java))
                        }
                        while (running.get()) {
                            delay(1000)
                            getSystemService(NotificationManager::class.java).notify(NOTIFICATION, notification())
                        }
                    } catch (error: Throwable) {
                        runtime.failure("recording.capture", error)
                        finish()
                    }
                }
            }
        }
        return START_NOT_STICKY
    }

    @Suppress("MissingPermission")
    private fun startInput(channel: String, id: String, options: JSONObject) {
        val format = AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT)
            .setSampleRate(16000).setChannelMask(AudioFormat.CHANNEL_IN_MONO).build()
        val builder = AudioRecord.Builder().setAudioFormat(format)
            .setBufferSizeInBytes(maxOf(AudioRecord.getMinBufferSize(16000, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT) * 8, 128_000))
        if (channel == "system") {
            builder.setAudioPlaybackCaptureConfig(AudioPlaybackCaptureConfiguration.Builder(projection!!)
                .addMatchingUsage(AudioAttributes.USAGE_MEDIA).addMatchingUsage(AudioAttributes.USAGE_GAME)
                .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN).build())
        } else builder.setAudioSource(MediaRecorder.AudioSource.VOICE_RECOGNITION)
        val recorder = builder.build()
        require(recorder.state == AudioRecord.STATE_INITIALIZED) { "Audio input unavailable" }
        if (channel == "mic" && !options.isNull("micDevice")) {
            val selected = options.optString("micDevice").toIntOrNull()
            getSystemService(AudioManager::class.java).getDevices(AudioManager.GET_DEVICES_INPUTS)
                .firstOrNull { it.id == selected }?.let { recorder.preferredDevice = it }
        }
        recorder.registerAudioRecordingCallback(mainExecutor, object : AudioManager.AudioRecordingCallback() {
            override fun onRecordingConfigChanged(configs: MutableList<AudioRecordingConfiguration>) {
                if (configs.any { it.clientAudioSessionId == recorder.audioSessionId && it.isClientSilenced }) {
                    runtime.updateState { it.copy(message = getString(R.string.recording_mic_lost)) }
                    runtime.log.event("recording.silenced")
                }
            }
        })
        val spool = File(runtime.dataDir, "capture/$id-$channel.pcm").apply { parentFile!!.mkdirs(); createNewFile() }
        spools[channel] = spool
        records += recorder
        recorder.startRecording()
        captureJobs += scope.launch {
            FileOutputStream(spool).use { output ->
                val buffer = ByteArray(6400)
                var syncedAt = System.currentTimeMillis()
                while (running.get()) {
                    val count = recorder.read(buffer, 0, buffer.size, AudioRecord.READ_BLOCKING)
                    if (count < 0) {
                        if (running.get()) throw IllegalStateException("Audio read failed: $count")
                        break
                    }
                    if (!runtime.paused) output.write(buffer, 0, count)
                    if (System.currentTimeMillis() - syncedAt > 2000) {
                        output.fd.sync(); syncedAt = System.currentTimeMillis()
                    }
                }
                output.fd.sync()
            }
        }
    }

    private fun attachProjection(intent: Intent, id: String) {
        projection?.stop()
        val result = intent.getIntExtra("projectionResult", Activity.RESULT_CANCELED)
        val data = if (Build.VERSION.SDK_INT >= 33) intent.getParcelableExtra("projectionData", Intent::class.java) else @Suppress("DEPRECATION") intent.getParcelableExtra("projectionData")
        require(result == Activity.RESULT_OK && data != null)
        projection = getSystemService(MediaProjectionManager::class.java).getMediaProjection(result, data)
        projection!!.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() {
                ProjectionCapture.close()
                if (running.get()) runtime.updateState { it.copy(message = getString(R.string.audio_restricted)) }
            }
        }, Handler(Looper.getMainLooper()))
        ProjectionCapture.open(this, projection!!, runtime, id)
    }

    private suspend fun consume() {
        val readers = spools.mapValues { RandomAccessFile(it.value, "r") }
        try {
            while (running.get() || readers.any { (_, r) -> r.filePointer < r.length() }) {
                var consumed = false
                for ((channel, input) in readers) {
                    val available = ((input.length() - input.filePointer) / 2).coerceAtMost(3200).toInt()
                    if (available <= 0) continue
                    val bytes = ByteArray(available * 2)
                    input.readFully(bytes)
                    val floats = FloatArray(available) { i ->
                        (((bytes[i * 2].toInt() and 255) or (bytes[i * 2 + 1].toInt() shl 8)).toShort()) / 32768f
                    }
                    // Spools already omit explicit pauses; always consume their queued content.
                    runtime.feed(floats, channel)
                    consumed = true
                }
                if (!consumed) delay(15)
            }
        } finally { readers.values.forEach { it.close() } }
    }

    private val finishLock = kotlinx.coroutines.sync.Mutex()
    private suspend fun finish() {
        finishLock.lock()
        try {
            if (!running.getAndSet(false) && runtime.activeId == null) { stopSelf(); return }
            records.forEach { runCatching { it.stop() } }
            captureJobs.joinAll()
            pipeline?.join()
            runtime.finishRecording()
            spools.values.forEach { it.delete() }
        } catch (error: Throwable) { runtime.failure("recording.finish", error) }
        finally {
            records.forEach { runCatching { it.release() } }; records.clear()
            projection?.stop(); projection = null
            ProjectionCapture.close()
            if (wakeLock?.isHeld == true) wakeLock?.release()
            stopService(Intent(this, OverlayService::class.java))
            if (runtime.activeId == null) runtime.processor.resume()
            stopForeground(STOP_FOREGROUND_REMOVE); stopSelf()
            finishLock.unlock()
        }
    }

    private fun notification(): Notification {
        val open = PendingIntent.getActivity(this, 1, Intent(this, MainActivity::class.java), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        fun action(name: String, request: Int) = PendingIntent.getService(this, request, Intent(this, RecordingService::class.java).setAction(name), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val seconds = runtime.state.value.seconds.toLong()
        return NotificationCompat.Builder(this, CHANNEL).setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle(getString(R.string.app_name)).setContentText("${getString(R.string.recording_notification)} · ${seconds / 60}:${(seconds % 60).toString().padStart(2, '0')}")
            .setContentIntent(open).setOngoing(true).setSilent(true).setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .addAction(0, getString(R.string.recording_mark), action(MARK, 2))
            .addAction(0, getString(R.string.recording_stop), action(STOP, 3)).build()
    }

    override fun onDestroy() {
        running.set(false)
        records.forEach { runCatching { it.stop(); it.release() } }
        if (wakeLock?.isHeld == true) wakeLock?.release()
        projection?.stop()
        scope.cancel()
        super.onDestroy()
    }
    companion object {
        const val CHANNEL = "recording"
        const val NOTIFICATION = 1001
        const val STOP = "dev.murmur.android.STOP"
        const val MARK = "dev.murmur.android.MARK"
        const val CAPTURE = "dev.murmur.android.CAPTURE"
        const val ENABLE_SCREEN = "dev.murmur.android.ENABLE_SCREEN"
    }
}
