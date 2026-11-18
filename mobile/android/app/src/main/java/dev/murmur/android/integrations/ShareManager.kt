package dev.murmur.android.integrations

import android.content.Context
import dev.murmur.android.intelligence.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.URI
import java.security.SecureRandom
import java.text.DateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.roundToInt

class ShareManager(private val context: Context) {
    private val secrets = SecretStore(context)
    private val cloudflare = "https://api.cloudflare.com/client/v4"
    private fun connection(): JSONObject = secrets.get("share.connection")?.let(::JSONObject) ?: throw IntelligenceException("share_not_connected")
    fun status(): JSONObject? = runCatching { val c = connection(); obj("url" to c.getString("url"), "hint" to c.getString("key").takeLast(4), "managed" to c.has("cloudflare")) }.getOrNull()
    fun disconnect() = secrets.remove("share.connection")
    private fun headers(c: JSONObject): Map<String, String> = mapOf("Authorization" to "Bearer ${c.getString("key")}")
    suspend fun connect(url: String, key: String): JSONObject {
        val normalized = normalize(url)
        val c = obj("url" to normalized, "key" to key.trim())
        require(IntegrationHttp.json("GET", "$normalized/api/ping", headers(c)).optBoolean("ok"))
        secrets.put("share.connection", c.toString())
        return status()!!
    }
    private suspend fun cf(method: String, path: String, token: String, body: JSONObject? = null): Any {
        val reply = IntegrationHttp.json(method, cloudflare + path, mapOf("Authorization" to "Bearer $token"), body)
        if (!reply.optBoolean("success", false)) throw IntelligenceException("share_cf_failed")
        return reply.get("result")
    }
    suspend fun accounts(token: String): List<JSONObject> = ((cf("GET", "/accounts?per_page=50", token.trim()) as? JSONArray) ?: JSONArray()).objects().map { obj("id" to it.getString("id"), "name" to it.optString("name")) }
    fun tokenUrl(): String = "https://dash.cloudflare.com/profile/api-tokens?permissionGroupKeys=" + IntegrationHttp.encode("[{\"key\":\"workers_scripts\",\"type\":\"edit\"},{\"key\":\"workers_kv_storage\",\"type\":\"edit\"},{\"key\":\"account_settings\",\"type\":\"read\"}]") + "&accountId=*&zoneId=all&name=Murmur"

    suspend fun setup(token: String, accountId: String, onProgress: (String) -> Unit = {}): JSONObject {
        require(accountId.matches(Regex("[a-fA-F0-9]{32}")))
        val account = accounts(token).find { it.optString("id") == accountId } ?: throw IntelligenceException("share_cf_bad_token")
        onProgress("storage")
        val namespaces = cf("GET", "/accounts/$accountId/storage/kv/namespaces?per_page=100", token) as JSONArray
        val namespace = namespaces.objects().find { it.optString("title") == "murmur-shares" }?.getString("id")
            ?: (cf("POST", "/accounts/$accountId/storage/kv/namespaces", token, obj("title" to "murmur-shares")) as JSONObject).getString("id")
        val config = obj("token" to token.trim(), "account" to accountId, "namespace" to namespace)
        val key = "mm_" + random(40)
        onProgress("worker"); uploadWorker(config, key)
        onProgress("address")
        val existing = runCatching { cf("GET", "/accounts/$accountId/workers/subdomain", token) as JSONObject }.getOrNull()?.optString("subdomain").orEmpty()
        var subdomain = existing
        if (subdomain.isBlank()) {
            val base = account.optString("name").lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-').take(24).ifBlank { "murmur" }
            for (name in listOf(base, "$base-${random(4).lowercase()}")) {
                val made = runCatching { cf("PUT", "/accounts/$accountId/workers/subdomain", token, obj("subdomain" to name)) as JSONObject }.getOrNull()
                if (made != null) { subdomain = made.optString("subdomain", name); break }
            }
            if (subdomain.isBlank()) throw IntelligenceException("share_cf_subdomain")
        }
        cf("POST", "/accounts/$accountId/workers/scripts/murmur-share/subdomain", token, obj("enabled" to true, "previews_enabled" to false))
        val c = obj("url" to "https://murmur-share.$subdomain.workers.dev", "key" to key, "cloudflare" to config)
        secrets.put("share.connection", c.toString())
        onProgress("check")
        var ready = false
        repeat(20) { if (!ready) { ready = runCatching { IntegrationHttp.json("GET", "${c.getString("url")}/api/ping", headers(c)).optBoolean("ok") }.getOrDefault(false); if (!ready) delay(3000) } }
        return obj("status" to status(), "ready" to ready)
    }

    private suspend fun uploadWorker(config: JSONObject, key: String) {
        val boundary = "murmur${random(24)}"
        val metadata = obj("main_module" to "worker.js", "compatibility_date" to "2026-10-01", "bindings" to arr(listOf(obj("type" to "kv_namespace", "name" to "SHARES", "namespace_id" to config.getString("namespace")), obj("type" to "secret_text", "name" to "API_KEY", "text" to key))))
        val output = ByteArrayOutputStream()
        fun part(name: String, type: String, bytes: ByteArray) { output.write("--$boundary\r\nContent-Disposition: form-data; name=\"$name\"; filename=\"$name\"\r\nContent-Type: $type\r\n\r\n".toByteArray()); output.write(bytes); output.write("\r\n".toByteArray()) }
        part("metadata", "application/json", metadata.toString().toByteArray())
        listOf("worker.js", "viewer.html", "viewer.css", "viewer-script.js").forEach { name -> part(name, if (name == "worker.js") "application/javascript+module" else "text/plain", context.assets.open("share/$name").use { it.readBytes() }) }
        output.write("--$boundary--\r\n".toByteArray())
        val reply = IntegrationHttp.request("PUT", "$cloudflare/accounts/${config.getString("account")}/workers/scripts/murmur-share", mapOf("Authorization" to "Bearer ${config.getString("token")}"), output.toByteArray(), "multipart/form-data; boundary=$boundary")
        IntegrationHttp.requireSuccess(reply)
        if (!reply.json().optBoolean("success")) throw IntelligenceException("share_cf_failed")
    }

    suspend fun publish(meeting: JSONObject, options: JSONObject = JSONObject(), onProgress: (String, Int, Int) -> Unit = { _, _, _ -> }): JSONObject {
        val c = connection()
        val ping = IntegrationHttp.json("GET", "${c.getString("url")}/api/ping", headers(c))
        if (ping.optInt("version", 1) < 6 && c.has("cloudflare")) uploadWorker(c.getJSONObject("cloudflare"), c.getString("key"))
        val selected = options.optJSONArray("parts")?.strings()?.toSet() ?: setOf("cover", "notes", "transcript", "screens")
        require(selected.all { it in setOf("cover", "notes", "transcript", "screens", "audio") })
        val ttl = when (options.optString("expiry", "7d")) { "1d" -> 86400L; "7d" -> 604800L; "30d" -> 2592000L; "never" -> null; else -> throw IllegalArgumentException("expiry") }
        val expires = ttl?.let { System.currentTimeMillis() + it * 1000 }
        val key = ShareCrypto.key(options.optString("password").takeIf(String::isNotEmpty))
        val directory = File(context.cacheDir, "share/${UUID.randomUUID()}").apply { mkdirs() }
        val uploads = mutableListOf<Pair<String, File>>()
        val files = JSONObject()
        val language = options.optString("language", "ca").takeIf { it in listOf("ca", "es", "en") } ?: "ca"
        val catalog = Catalog(context, language)
        try {
            onProgress("reading", 0, 1)
            if ("screens" in selected) {
                val screens = JSONArray()
                meeting.optJSONArray("screens")?.objects().orEmpty().sortedBy { it.optDouble("t") }.forEachIndexed { index, screen ->
                    currentCoroutineContext().ensureActive()
                    val source = ownFile(screen.getString("path")); require(source.length() < 20 * 1024 * 1024)
                    val name = "s$index"; val encrypted = File(directory, name).apply { writeBytes(ShareCrypto.sealFile(source.readBytes(), key.bytes)) }; uploads += name to encrypted
                    screens.put(obj("file" to name, "at" to clock(screen.optDouble("t")), "alt" to catalog.text("share.page.screenAt", "time" to clock(screen.optDouble("t"))), "type" to if (source.extension.lowercase() == "png") "image/png" else "image/jpeg"))
                }
                files.put("screens", screens)
            }
            if ("audio" in selected && !meeting.isNull("audioPath") && meeting.optString("audioPath").isNotBlank()) {
                val audio = ownFile(meeting.getString("audioPath")); val count = max(1, ceil(audio.length() / (8.0 * 1024 * 1024)).toInt())
                audio.inputStream().use { input ->
                    repeat(count) { index ->
                        currentCoroutineContext().ensureActive()
                        val buffer = ByteArray(minOf(8L * 1024 * 1024, max(0L, audio.length() - index * 8L * 1024 * 1024)).toInt()); var filled = 0
                        while (filled < buffer.size) { val n = input.read(buffer, filled, buffer.size - filled); if (n < 0) throw IntelligenceException("share_file_missing"); filled += n }
                        val name = "audio.$index"; uploads += name to File(directory, name).apply { writeBytes(ShareCrypto.sealFile(buffer, key.bytes)) }
                    }
                }
                files.put("audio", obj("parts" to count, "type" to if (audio.extension == "m4a") "audio/mp4" else "audio/ogg", "bytes" to audio.length()))
            }
            onProgress("meeting", 0, 1)
            val envelope = ShareCrypto.seal(page(meeting, selected, expires, files, language), key)
            val suffix = ttl?.let { "?ttl=$it" }.orEmpty()
            val published = IntegrationHttp.json("POST", "${c.getString("url")}/api/shares$suffix", headers(c), envelope)
            val id = published.getString("id"); require(id.matches(Regex("[A-Za-z0-9]{12}")))
            try {
                uploads.forEachIndexed { index, (name, file) ->
                    onProgress("files", index, uploads.size)
                    val reply = IntegrationHttp.request("PUT", "${c.getString("url")}/api/shares/$id/$name$suffix", headers(c), file.readBytes(), "application/octet-stream")
                    IntegrationHttp.requireSuccess(reply)
                }
            } catch (error: Exception) { runCatching { revoke(id) }; throw error }
            onProgress("done", uploads.size, uploads.size)
            return obj("id" to id, "url" to (published.getString("url") + key.fragment?.let { "#$it" }.orEmpty()), "createdAt" to System.currentTimeMillis(), "expiresAt" to (if (published.isNull("expiresAt")) expires else published.optLong("expiresAt")), "password" to (key.kdf != null), "parts" to arr(selected))
        } finally { key.bytes.fill(0); directory.deleteRecursively() }
    }
    suspend fun revoke(id: String) {
        require(id.matches(Regex("[A-Za-z0-9]{12}")))
        val c = connection(); val reply = IntegrationHttp.request("DELETE", "${c.getString("url")}/api/shares/$id", headers(c))
        if (reply.status != 404) IntegrationHttp.requireSuccess(reply)
    }

    private fun ownFile(path: String): File {
        val data = File(context.filesDir, "data")
        val file = (if (File(path).isAbsolute) File(path) else File(data, path)).canonicalFile
        require(file.path.startsWith(context.filesDir.canonicalPath + File.separator) && file.isFile)
        return file
    }
    private fun page(m: JSONObject, selected: Set<String>, expires: Long?, files: JSONObject, language: String): JSONObject {
        val catalog = Catalog(context, language); val lines = m.optJSONArray("utterances")?.objects().orEmpty()
        val ids = lines.map { it.optInt("speaker") }.distinct().sorted()
        val source = m.optJSONObject("sources")
        val user = if (source?.optBoolean("mic") == true && source.optBoolean("system")) 0 else null
        val order = ids.filter { it != user }
        val speakers = JSONObject().apply { ids.forEach { put(it.toString(), obj("name" to speakerName(m, it, language), "tone" to if (it == user) "you" else order.indexOf(it) % 4 + 1)) } }
        val labels = JSONObject()
        listOf("tone", "talk", "summary", "decisions", "tasks", "parts").forEach { labels.put(it, catalog.text(it)) }
        listOf("cover", "taskDone", "taskOpen", "loadingAudio", "audioFailed", "screenFailed", "playFrom", "markdown").forEach { labels.put(it, catalog.text("share.page.$it")) }
        labels.put("notes", catalog.text("export.notes")).put("transcript", catalog.text("export.transcript")).put("screens", catalog.text("share.parts.screens"))
        labels.put("listen", files.optJSONObject("audio")?.let { catalog.text("share.page.listen", "size" to "${max(1, (it.optLong("bytes") / 1048576.0).roundToInt())} MB") }.orEmpty())
        val date = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT, Locale.forLanguageTag(language))
        val page = obj("v" to 1, "lang" to language, "title" to m.optString("title"), "meta" to arr(listOf(date.format(Date(m.optLong("createdAt"))), clock(m.optDouble("duration")))), "labels" to labels, "files" to files, "speakers" to speakers,
            "footer" to if (expires == null) catalog.text("share.page.footer", "date" to date.format(Date())) else catalog.text("share.page.footerExpires", "date" to date.format(Date()), "expires" to date.format(Date(expires))))
        if ("cover" in selected) m.optJSONObject("cover")?.let { original ->
            val cover = JSONObject(original.toString())
            cover.optJSONObject("tone")?.let { it.put("label", catalog.text("tones.${it.optString("label")}")) }
            val totals = lines.groupBy { it.optInt("speaker") }.mapValues { (_, turns) -> turns.sumOf { max(0.0, it.optDouble("end") - it.optDouble("start")) } }; val total = totals.values.sum().coerceAtLeast(1.0)
            cover.put("talk", arr(totals.entries.sortedByDescending { it.value }.map { obj("speaker" to it.key, "name" to speakerName(m, it.key, language), "fraction" to it.value / total, "share" to if (it.value / total < 0.01) "<1%" else "${(it.value / total * 100).roundToInt()}%", "time" to if (it.value < 60) catalog.text("talkUnderMinute") else catalog.text("talkMinutes", "minutes" to (it.value / 60).roundToInt())) }))
            listOf("decisions", "tasks", "parts").forEach { field -> cover.optJSONArray(field)?.objects()?.forEach { item -> if (item.has("t") && !item.isNull("t")) item.put("at", clock(item.optDouble("t"))) } }
            page.put("cover", cover)
        }
        if ("notes" in selected) page.put("notes", blocks(m.optJSONObject("notesDoc")?.optJSONArray("content") ?: arr(m.optJSONArray("notes")?.objects().orEmpty().map { obj("type" to "paragraph", "attrs" to obj("t" to it.optDouble("t")), "content" to arr(listOf(obj("type" to "text", "text" to it.optString("text"))))) })))
        if ("transcript" in selected) page.put("transcript", arr(lines.map { obj("s" to it.optInt("speaker"), "at" to clock(it.optDouble("start")), "text" to it.optString("text")) }))
        return page
    }
    private fun random(length: Int): String { val chars = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789"; val rng = SecureRandom(); return (1..length).map { chars[rng.nextInt(chars.length)] }.joinToString("") }
    private fun normalize(input: String): String { val url = input.trim().trimEnd('/').let { if (it.contains("://")) it else "https://$it" }; val uri = URI(url); require(uri.scheme == "https" && uri.host != null && uri.userInfo == null && uri.rawQuery == null && uri.fragment == null && uri.path.orEmpty().isEmpty()); return url }
}

private fun blocks(nodes: JSONArray): JSONArray {
    fun runs(children: JSONArray?): JSONArray = arr(children?.objects().orEmpty().map { node ->
        if (node.optString("type") == "hardBreak") obj("br" to true) else obj("t" to node.optString("text")).apply { node.optJSONArray("marks")?.objects()?.forEach { mark -> when (mark.optString("type")) { "bold" -> put("b", true); "italic" -> put("i", true); "underline" -> put("u", true); "strike" -> put("s", true); "code" -> put("code", true); "link" -> put("href", mark.optJSONObject("attrs")?.optString("href")) } } }
    })
    return arr(nodes.objects().flatMap { node ->
        val attrs = node.optJSONObject("attrs"); val children = node.optJSONArray("content") ?: JSONArray()
        fun stamp(block: JSONObject, original: JSONObject): JSONObject = block.apply { original.optJSONObject("attrs")?.let { attributes -> if (attributes.has("t") && !attributes.isNull("t")) put("at", clock(attributes.optDouble("t"))); if (attributes.optInt("indent") > 0) put("indent", attributes.optInt("indent")) } }
        when (node.optString("type")) {
            "heading" -> listOf(stamp(obj("type" to "h", "level" to (attrs?.optInt("level", 1) ?: 1), "runs" to runs(children)), node))
            "paragraph" -> if (children.length() == 0) emptyList() else listOf(stamp(obj("type" to "p", "runs" to runs(children)), node))
            "blockquote" -> listOf(obj("type" to "quote", "blocks" to blocks(children)))
            "bulletList", "orderedList", "taskList" -> listOf(obj("type" to when (node.optString("type")) { "orderedList" -> "ol"; "taskList" -> "todo"; else -> "ul" }, "items" to arr(children.objects().map { item -> val content = item.optJSONArray("content")?.objects().orEmpty(); stamp(obj("checked" to (item.optJSONObject("attrs")?.optBoolean("checked") ?: false), "runs" to runs(content.firstOrNull()?.optJSONArray("content")), "children" to blocks(arr(content.drop(1)))), item) })))
            else -> blocks(children).objects()
        }
    })
}
