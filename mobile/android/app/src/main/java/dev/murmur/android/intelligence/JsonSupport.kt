package dev.murmur.android.intelligence

import android.content.Context
import kotlinx.coroutines.suspendCancellableCoroutine
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale
import java.util.concurrent.Executors
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

fun JSONArray.objects(): List<JSONObject> = (0 until length()).mapNotNull { optJSONObject(it) }
fun JSONArray.strings(): List<String> = (0 until length()).mapNotNull { optString(it).takeIf(String::isNotBlank) }
fun obj(vararg pairs: Pair<String, Any?>): JSONObject = JSONObject().apply { pairs.forEach { (key, value) -> put(key, value ?: JSONObject.NULL) } }
fun arr(items: Iterable<*>): JSONArray = JSONArray().apply { items.forEach { put(it) } }

class IntelligenceException(val code: String, val httpStatus: Int = 0) : Exception(code)

fun resolveUiLanguage(preference: String): String =
    (if (preference == "system") Locale.getDefault().language else preference).takeIf { it in setOf("ca", "es", "en") } ?: "ca"

/** Shared translations are copied from the desktop; no meeting contents are logged. */
class Catalog(private val root: JSONObject) {
    constructor(context: Context, language: String) : this(load(context, language))
    fun text(path: String, vararg values: Pair<String, Any>): String {
        var value: Any = root
        path.split('.').forEach { value = (value as? JSONObject)?.opt(it) ?: path }
        var text = value as? String ?: path
        values.forEach { (key, replacement) -> text = text.replace("{{$key}}", replacement.toString()) }
        return text
    }
    companion object {
        private fun load(context: Context, language: String): JSONObject {
            val selected = resolveUiLanguage(language)
            return context.assets.open("intelligence/$selected.json").bufferedReader().use { JSONObject(it.readText()) }.apply {
                for (namespace in listOf("transcript", "format")) {
                    put(namespace, context.assets.open("locales/$selected/$namespace.json").bufferedReader().use { JSONObject(it.readText()) })
                }
            }
        }
    }
}

/** Native HTTPS only. Redirects never forward credentials to another host. */
object JsonHttp {
    private val executor = Executors.newCachedThreadPool()
    suspend fun request(method: String, url: String, headers: Map<String, String> = emptyMap(), body: JSONObject? = null): JSONObject =
        suspendCancellableCoroutine { continuation ->
            val endpoint = URL(url)
            if (endpoint.protocol != "https") {
                continuation.resumeWithException(IntelligenceException("network_https_required")); return@suspendCancellableCoroutine
            }
            val connection = endpoint.openConnection() as HttpURLConnection
            val work = executor.submit {
                try {
                    connection.requestMethod = method
                    connection.connectTimeout = 15_000
                    connection.readTimeout = 300_000
                    connection.instanceFollowRedirects = false
                    connection.setRequestProperty("Accept", "application/json")
                    headers.forEach { (name, value) -> connection.setRequestProperty(name, value) }
                    if (body != null) {
                        connection.doOutput = true
                        connection.setRequestProperty("Content-Type", "application/json; charset=utf-8")
                        connection.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
                    }
                    val status = connection.responseCode
                    if (status !in 200..299) {
                        // Remote error bodies may repeat the meeting or credentials. Do not expose them.
                        val code = when (status) { 401, 403 -> "cloud_bad_key"; 429 -> "cloud_rate_limited"; in 500..599 -> "cloud_unavailable"; else -> "cloud_request_failed" }
                        throw IntelligenceException(code, status)
                    }
                    val bytes = connection.inputStream.use { input ->
                        val out = java.io.ByteArrayOutputStream()
                        val buffer = ByteArray(8192)
                        while (true) { val size = input.read(buffer); if (size < 0) break; if (out.size() + size > 16 * 1024 * 1024) throw IntelligenceException("cloud_too_long"); out.write(buffer, 0, size) }
                        out.toByteArray()
                    }
                    val result = if (bytes.isEmpty()) JSONObject() else JSONObject(bytes.toString(Charsets.UTF_8))
                    if (continuation.isActive) continuation.resume(result)
                } catch (error: Exception) {
                    if (continuation.isActive) continuation.resumeWithException(if (error is IntelligenceException) error else IntelligenceException("cloud_offline"))
                } finally { connection.disconnect() }
            }
            continuation.invokeOnCancellation { connection.disconnect(); work.cancel(true) }
        }
}
