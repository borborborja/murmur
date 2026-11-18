package dev.murmur.android.integrations

import dev.murmur.android.intelligence.IntelligenceException
import kotlinx.coroutines.suspendCancellableCoroutine
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.concurrent.Executors
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

data class HttpReply(val status: Int, val body: String, val headers: Map<String, String>) {
    fun json(): JSONObject = if (body.isBlank()) JSONObject() else JSONObject(body)
}

internal object IntegrationHttp {
    private val executor = Executors.newCachedThreadPool()
    suspend fun request(method: String, url: String, headers: Map<String, String> = emptyMap(), body: ByteArray? = null, contentType: String = "application/json", rpcId: Int? = null): HttpReply = suspendCancellableCoroutine { continuation ->
        val endpoint = URL(url)
        if (endpoint.protocol != "https") { continuation.resumeWithException(IntelligenceException("network_https_required")); return@suspendCancellableCoroutine }
        val connection = endpoint.openConnection() as HttpURLConnection
        val task = executor.submit {
            try {
                connection.requestMethod = method; connection.connectTimeout = 15_000; connection.readTimeout = 120_000; connection.instanceFollowRedirects = false
                connection.setRequestProperty("Accept", "application/json")
                headers.forEach { (name, value) -> connection.setRequestProperty(name, value) }
                if (body != null) { connection.doOutput = true; connection.setRequestProperty("Content-Type", contentType); connection.setFixedLengthStreamingMode(body.size); connection.outputStream.use { it.write(body) } }
                val status = connection.responseCode
                val responseHeaders = connection.headerFields.filterKeys { it != null }.mapKeys { it.key.lowercase() }.mapValues { it.value.firstOrNull().orEmpty() }
                val stream = if (status in 200..299) connection.inputStream else connection.errorStream
                val text = stream?.use { input ->
                    if (rpcId != null && connection.contentType.orEmpty().contains("text/event-stream")) {
                        var result = ""
                        input.bufferedReader().use { reader ->
                            var bytes = 0
                            while (true) {
                                val line = reader.readLine() ?: break
                                bytes += line.length; require(bytes < 16 * 1024 * 1024)
                                if (!line.startsWith("data:")) continue
                                val data = line.removePrefix("data:").trim()
                                val json = runCatching { JSONObject(data) }.getOrNull()
                                if (json?.optInt("id", -1) == rpcId) { result = data; break }
                            }
                        }
                        result
                    } else {
                        val out = ByteArrayOutputStream(); val buffer = ByteArray(8192)
                        while (true) { val n = input.read(buffer); if (n < 0) break; require(out.size() + n < 16 * 1024 * 1024); out.write(buffer, 0, n) }
                        out.toString("UTF-8")
                    }
                }.orEmpty()
                if (continuation.isActive) continuation.resume(HttpReply(status, text, responseHeaders))
            } catch (error: Exception) { if (continuation.isActive) continuation.resumeWithException(IntelligenceException("cloud_offline")) }
            finally { connection.disconnect() }
        }
        continuation.invokeOnCancellation { connection.disconnect(); task.cancel(true) }
    }
    suspend fun json(method: String, url: String, headers: Map<String, String> = emptyMap(), body: JSONObject? = null): JSONObject {
        val reply = request(method, url, headers, body?.toString()?.toByteArray())
        requireSuccess(reply)
        return reply.json()
    }
    fun requireSuccess(reply: HttpReply) {
        if (reply.status !in 200..299) throw IntelligenceException(when (reply.status) { 401, 403 -> "cloud_bad_key"; 429 -> "cloud_rate_limited"; in 500..599 -> "cloud_unavailable"; else -> "cloud_request_failed" }, reply.status)
    }
    fun encode(value: String): String = URLEncoder.encode(value, "UTF-8").replace("+", "%20")
    fun form(vararg pairs: Pair<String, String>): ByteArray = pairs.joinToString("&") { "${encode(it.first)}=${encode(it.second)}" }.toByteArray()
}
