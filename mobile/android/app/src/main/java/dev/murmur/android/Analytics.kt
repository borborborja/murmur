package dev.murmur.android

import android.content.Context
import android.os.Build
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONObject
import java.io.IOException
import java.time.Instant
import java.util.Locale
import java.util.UUID

/**
 * A small client for Aptabase's public event format, implemented here rather than importing
 * SDK device fingerprint fields. No disk queue, meeting content, user ID or device ID.
 */
class AnonymousAnalytics internal constructor(
    private val key: String,
    private val allowed: () -> Boolean,
    private val system: () -> JSONObject,
    private val send: (String, String, JSONObject) -> Unit,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    constructor(context: Context) : this(
        BuildConfig.APTABASE_KEY,
        { AppSettings(context.applicationContext).read().optBoolean("analytics", false) },
        { JSONObject().put("isDebug", BuildConfig.DEBUG).put("osVersion", Build.VERSION.RELEASE)
            .put("locale", Locale.getDefault().toLanguageTag()).put("appVersion", BuildConfig.VERSION_NAME)
            .put("appBuildNumber", BuildConfig.VERSION_CODE.toString()) },
        { url, appKey, payload ->
            val request = Request.Builder().url(url).header("App-Key", appKey)
                .post(payload.toString().toRequestBody("application/json".toMediaType())).build()
            http.newCall(request).enqueue(object : Callback {
                override fun onFailure(call: Call, error: IOException) = Unit
                override fun onResponse(call: Call, response: Response) { response.close() }
            })
        }
    )

    private var session: String? = null
    private var lastEvent = 0L

    @Synchronized fun track(event: String, properties: JSONObject = JSONObject()) {
        val endpoint = endpoint(key) ?: return
        if (!allowed()) { session = null; lastEvent = 0L; return }
        if (event !in EVENTS) return
        val now = clock()
        if (session == null || now < lastEvent || now - lastEvent >= SESSION_IDLE_MS) session = UUID.randomUUID().toString()
        lastEvent = now
        val source = system()
        val metadata = JSONObject().put("isDebug", source.optBoolean("isDebug", false)).put("osName", "Android")
            .put("sdkVersion", "murmur-android@1")
        for (field in listOf("osVersion", "appVersion")) {
            val value = source.optString(field)
            if (value.matches(Regex("[A-Za-z0-9.+_-]{1,64}"))) metadata.put(field, value)
        }
        source.optString("locale").takeIf { it.matches(Regex("[A-Za-z]{2,8}(?:[-_][A-Za-z0-9]{2,8}){0,4}")) }?.let { metadata.put("locale", it) }
        source.optString("appBuildNumber").takeIf { it.matches(Regex("[0-9]{1,12}")) }?.let { metadata.put("appBuildNumber", it) }
        val props = JSONObject()
        properties.keys().forEach { name ->
            val value = properties.opt(name)
            if (value is String && value in (VALUES[name] ?: emptySet())) props.put(name, value)
        }
        val payload = JSONObject().put("timestamp", Instant.ofEpochMilli(now).toString()).put("sessionId", session)
            .put("eventName", event).put("systemProps", metadata).put("props", props)
        // Usage statistics must never make a recording fail, and errors carry no content logs.
        runCatching { send(endpoint, key, payload) }
    }

    companion object {
        private val http = OkHttpClient()
        private const val SESSION_IDLE_MS = 60 * 60 * 1000L
        private val EVENTS = setOf("app_opened", "recording_started", "recording_finished", "file_transcribed",
            "summary_generated", "translated", "exported", "shared")
        private val VALUES = mapOf(
            "language" to setOf("ca", "es", "ca-es", "en"),
            "engine" to setOf("local", "anthropic", "openai", "openrouter", "ollama", "gemini"),
            "format" to setOf("md", "docx", "pdf", "srt", "vtt", "audio", "screens", "obsidian", "notion"),
            "source" to setOf("mic", "system", "mic+system", "file"),
            "minutes" to setOf("<5", "5-15", "15-30", "30-60", "60-120", ">120"),
            "count" to setOf("0", "1-5", "6-20", "21-50", ">50"),
        )
        internal fun endpoint(key: String): String? {
            val region = Regex("A-(EU|US)-[A-Za-z0-9]{6,64}").matchEntire(key)?.groupValues?.get(1) ?: return null
            return "https://${region.lowercase(Locale.ROOT)}.aptabase.com/api/v0/event"
        }
        fun bucketMinutes(seconds: Double): String = when {
            !seconds.isFinite() || seconds < 5 * 60 -> "<5"
            seconds < 15 * 60 -> "5-15"
            seconds < 30 * 60 -> "15-30"
            seconds < 60 * 60 -> "30-60"
            seconds < 120 * 60 -> "60-120"
            else -> ">120"
        }
        fun bucketCount(n: Int): String = when {
            n <= 0 -> "0"; n <= 5 -> "1-5"; n <= 20 -> "6-20"; n <= 50 -> "21-50"; else -> ">50"
        }
    }
}
