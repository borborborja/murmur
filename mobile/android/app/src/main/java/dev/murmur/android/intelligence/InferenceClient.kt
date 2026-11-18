package dev.murmur.android.intelligence

import android.app.Service
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Message
import android.os.Messenger
import kotlinx.coroutines.suspendCancellableCoroutine
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** Each native engine lives in a separate process to prevent ggml symbol collisions. */
class InferenceClient(private val context: Context) {
    suspend fun generate(modelPath: String, system: String, document: String, prompt: String, maxTokens: Int, schema: String?): String = request(LlmService::class.java,
        obj("modelPath" to modelPath, "system" to system, "document" to document, "prompt" to prompt, "maxTokens" to maxTokens.coerceAtMost(2048), "schema" to schema))

    suspend fun transcribe(modelPath: String, wavPath: String, language: String): JSONArray = JSONArray(request(WhisperService::class.java,
        obj("modelPath" to modelPath, "wavPath" to wavPath, "language" to language)))

    suspend fun transcribeSpans(modelPath: String, wavPath: String, language: String, spans: JSONArray): JSONArray {
        val result = JSONArray(request(WhisperService::class.java,
            obj("modelPath" to modelPath, "wavPath" to wavPath, "language" to language, "spans" to spans)))
        // Unicode-aware final filtering also catches all-capitals Catalan/Spanish phrases.
        return arr(result.objects().filter { acceptedWhisperRevision(it.optString("text")) })
    }

    suspend fun detectLanguage(modelPath: String, wavPath: String, spans: JSONArray): JSONObject {
        val probabilities = JSONArray(request(WhisperService::class.java,
            obj("modelPath" to modelPath, "wavPath" to wavPath, "detect" to true, "windows" to LanguageDetection.windows(spans))))
        return LanguageDetection.decide(probabilities)
    }

    private suspend fun request(service: Class<out Service>, arguments: JSONObject): String = suspendCancellableCoroutine { continuation ->
        // Long meetings can have thousands of spans. Binder carries only a private filename,
        // keeping both input and output below its transaction limit.
        val requests = File(context.cacheDir, "inference/requests").apply { mkdirs() }
        requests.listFiles()?.filter { it.isFile && System.currentTimeMillis() - it.lastModified() > 3_600_000 }?.forEach { it.delete() }
        val input = File(requests, UUID.randomUUID().toString() + ".json")
        try { input.writeText(arguments.toString()) }
        catch (error: Exception) { continuation.resumeWithException(IntelligenceException("local_model_failed")); return@suspendCancellableCoroutine }
        val finished = AtomicBoolean(false)
        var bound = false
        var remote: Messenger? = null
        lateinit var connection: ServiceConnection
        fun close() {
            input.delete()
            if (bound) { runCatching { context.unbindService(connection) }; bound = false }
        }
        val callback = Messenger(Handler(Looper.getMainLooper()) { reply ->
            if (!finished.compareAndSet(false, true)) return@Handler true
            val result = reply.data.getString("file")
            val error = reply.data.getString("error")
            try {
                if (!continuation.isActive) return@Handler true
                if (error != null) continuation.resumeWithException(IntelligenceException(error))
                else {
                    val file = File(result ?: throw IntelligenceException("local_model_failed")).canonicalFile
                    require(file.path.startsWith(File(context.cacheDir, "inference").canonicalPath + File.separator))
                    val text = file.readText(); file.delete(); continuation.resume(text)
                }
            } catch (failure: Exception) { if (continuation.isActive) continuation.resumeWithException(failure) }
            finally { close() }
            true
        })
        connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName, binder: IBinder) {
                if (finished.get()) { close(); return }
                remote = Messenger(binder)
                try { remote!!.send(Message.obtain(null, 1).apply { replyTo = callback; data = Bundle().apply { putString("argsPath", input.path) } }) }
                catch (error: Exception) { if (finished.compareAndSet(false, true) && continuation.isActive) continuation.resumeWithException(IntelligenceException("local_model_failed")); close() }
            }
            override fun onServiceDisconnected(name: ComponentName) {
                if (finished.compareAndSet(false, true) && continuation.isActive) continuation.resumeWithException(IntelligenceException("local_model_failed"))
                close()
            }
            override fun onBindingDied(name: ComponentName) = onServiceDisconnected(name)
            override fun onNullBinding(name: ComponentName) = onServiceDisconnected(name)
        }
        try {
            bound = context.bindService(Intent(context, service), connection, Context.BIND_AUTO_CREATE)
            if (!bound && finished.compareAndSet(false, true)) { input.delete(); continuation.resumeWithException(IntelligenceException("local_model_failed")) }
        } catch (error: Exception) { finished.set(true); input.delete(); continuation.resumeWithException(IntelligenceException("local_model_failed")) }
        continuation.invokeOnCancellation {
            finished.set(true)
            runCatching { remote?.send(Message.obtain(null, 2)) }
            Handler(Looper.getMainLooper()).post { close() }
        }
    }
}

abstract class InferenceService : Service() {
    private val executor = Executors.newSingleThreadExecutor()
    private val busy = AtomicBoolean(false)
    protected abstract fun execute(arguments: JSONObject): String
    protected abstract fun cancel()
    private val messenger by lazy { Messenger(Handler(Looper.getMainLooper()) { message ->
        if (message.what == 2) { runCatching { cancel() }; return@Handler true }
        val client = message.replyTo ?: return@Handler true
        if (!busy.compareAndSet(false, true)) { reply(client, null, "local_model_busy"); return@Handler true }
        val inputPath = message.data.getString("argsPath")
        executor.execute {
            try {
                val input = File(inputPath ?: throw IllegalArgumentException("missing arguments")).canonicalFile
                require(input.path.startsWith(File(cacheDir, "inference/requests").canonicalPath + File.separator))
                require(input.isFile && input.length() <= 64 * 1024 * 1024)
                val args = try { JSONObject(input.readText()) } finally { input.delete() }
                val model = File(args.getString("modelPath")).canonicalFile
                require(model.path.startsWith(filesDir.canonicalPath + File.separator))
                require(model.isFile)
                val output = execute(args)
                val directory = File(cacheDir, "inference").apply { mkdirs() }
                // Bounded lifetime; a process death cannot leave a meeting in the log.
                directory.listFiles()?.filter { System.currentTimeMillis() - it.lastModified() > 3_600_000 }?.forEach { it.delete() }
                val file = File(directory, UUID.randomUUID().toString() + ".json").apply { writeText(output) }
                reply(client, file.path, null)
            } catch (failure: Throwable) { reply(client, null, if (failure is UnsatisfiedLinkError) "native_library_missing" else "local_model_failed") }
            finally { busy.set(false) }
        }
        true
    }) }
    override fun onBind(intent: Intent?): IBinder = messenger.binder
    private fun reply(client: Messenger, file: String?, error: String?) { runCatching { client.send(Message.obtain(null, 3).apply { data = Bundle().apply { file?.let { putString("file", it) }; error?.let { putString("error", it) } } }) } }
    override fun onDestroy() { runCatching { cancel() }; executor.shutdownNow(); super.onDestroy() }
}

class LlmService : InferenceService() {
    override fun execute(arguments: JSONObject): String = NativeLlm.generate(arguments.getString("modelPath"), arguments.optString("system"), arguments.optString("document"), arguments.optString("prompt"), arguments.optInt("maxTokens", 1000), arguments.optString("schema", ""))
    override fun cancel() = NativeLlm.cancel()
}
class WhisperService : InferenceService() {
    override fun execute(arguments: JSONObject): String {
        val input = File(arguments.getString("wavPath")).canonicalFile
        require(input.path.startsWith(filesDir.canonicalPath + File.separator) || input.path.startsWith(cacheDir.canonicalPath + File.separator))
        return if (arguments.optBoolean("detect")) NativeWhisper.detectLanguage(arguments.getString("modelPath"), input.path, arguments.getJSONArray("windows").toString())
            else if (arguments.has("spans")) NativeWhisper.transcribeSpans(arguments.getString("modelPath"), input.path, arguments.optString("language", "ca"), arguments.getJSONArray("spans").toString())
            else NativeWhisper.transcribe(arguments.getString("modelPath"), input.path, arguments.optString("language", "ca"))
    }
    override fun cancel() = NativeWhisper.cancel()
}
object NativeLlm {
    init { System.loadLibrary("murmur_llm") }
    external fun generate(model: String, system: String, document: String, prompt: String, maxTokens: Int, schema: String): String
    external fun cancel()
}
object NativeWhisper {
    init { System.loadLibrary("murmur_whisper") }
    external fun transcribe(model: String, input: String, language: String): String
    external fun transcribeSpans(model: String, input: String, language: String, spans: String): String
    external fun detectLanguage(model: String, input: String, windows: String): String
    external fun cancel()
}

internal fun acceptedWhisperRevision(text: String): Boolean {
    val lower = text.trim().lowercase(java.util.Locale.ROOT)
    return lower.isNotEmpty() && listOf("subtítols realitzats per", "subtítulos realizados por", "subtítulos por la comunidad", "gràcies per veure", "gracias por ver", "thanks for watching", "thank you for watching", "amara.org").none(lower::contains)
}
