package dev.murmur.android

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import dev.murmur.android.export.ExportManager
import dev.murmur.android.integrations.NotionManager
import dev.murmur.android.integrations.ShareManager
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class SafTree(private val context: Context, private val tree: Uri) {
    private val root: Uri = DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
    private fun child(parent: Uri, name: String): Uri? {
        val uri = DocumentsContract.buildChildDocumentsUriUsingTree(tree, DocumentsContract.getDocumentId(parent))
        context.contentResolver.query(uri, arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME), null, null, null)?.use { cursor ->
            while (cursor.moveToNext()) if (cursor.getString(1) == name) return DocumentsContract.buildDocumentUriUsingTree(tree, cursor.getString(0))
        }
        return null
    }
    fun write(path: String, mime: String, bytes: ByteArray): Uri {
        val parts = path.split('/').filter(String::isNotBlank)
        require(parts.isNotEmpty() && parts.none { it in listOf(".", "..") })
        var folder = root
        for (name in parts.dropLast(1)) folder = child(folder, name) ?: DocumentsContract.createDocument(context.contentResolver, folder, DocumentsContract.Document.MIME_TYPE_DIR, name) ?: error("Folder unavailable")
        val file = child(folder, parts.last()) ?: DocumentsContract.createDocument(context.contentResolver, folder, mime, parts.last()) ?: error("Destination unavailable")
        context.contentResolver.openOutputStream(file, "wt")!!.use { it.write(bytes) }
        return file
    }
}

class ExportCoordinator(private val runtime: AppRuntime) {
    private val manager = ExportManager(runtime.context)
    fun create(meeting: JSONObject, format: String, content: String): File {
        val settings = runtime.config.read()
        val language = settings.optString("uiLanguage", "ca")
        val ext = if (format == "audio") "ogg" else if (format == "screens") "zip" else format
        val file = File(runtime.context.cacheDir, "exports/${safeName(meeting.optString("title"))}.$ext").apply { parentFile!!.mkdirs() }
        when (format) {
            "md" -> file.writeText(manager.markdown(meeting, content, language))
            "docx" -> file.writeBytes(manager.docx(meeting, content, language))
            "srt", "vtt" -> file.writeText(manager.subtitles(meeting, format, settings.optBoolean("subtitleSpeakers", true), settings.optBoolean("subtitleTranslated"), language))
            "audio" -> {
                val source = privateFile(meeting.getString("audioPath"))
                require(source.isFile); source.copyTo(file, true)
            }
            "screens" -> ZipOutputStream(file.outputStream()).use { zip ->
                meeting.array("screens").objects().forEachIndexed { index, screen ->
                    val source = privateFile(screen.getString("path"))
                    zip.putNextEntry(ZipEntry("${index+1}-${screen.optDouble("t").toInt()}.jpg"))
                    source.inputStream().use { it.copyTo(zip) }; zip.closeEntry()
                }
            }
            else -> error("Unsupported export")
        }
        runtime.analytics.track("exported", json("format" to format))
        return file
    }
    fun html(meeting: JSONObject, content: String): String = manager.html(meeting, content, runtime.config.read().optString("uiLanguage", "ca"))

    suspend fun automatic(meeting: JSONObject) {
        val settings = runtime.config.read()
        val language = settings.optString("uiLanguage", "ca")
        val base = fileBase(meeting)
        if (settings.optBoolean("saveMarkdown") && !settings.isNull("markdownDir")) {
            val tree = SafTree(runtime.context, Uri.parse(settings.getString("markdownDir")))
            val choice = settings.optString("markdownContent", "all")
            if (choice == "all" && meeting.has("cover") && !meeting.isNull("cover")) {
                tree.write("$base.md", "text/markdown", manager.markdown(meeting, "summary", language).toByteArray())
                tree.write("$base-transcript.md", "text/markdown", manager.markdown(meeting, "transcript", language).toByteArray())
            } else tree.write("$base.md", "text/markdown", manager.markdown(meeting, choice, language).toByteArray())
        }
        if (settings.optBoolean("obsidianAuto") && !settings.isNull("obsidianVault")) obsidian(meeting)
        if (settings.optBoolean("notionAuto") && NotionManager(runtime.context).status().optBoolean("connected")) {
            NotionManager(runtime.context).export(meeting, settings.optJSONObject("notionPage")?.optString("id"), language)
        }
    }

    fun obsidian(meeting: JSONObject): String {
        val settings = runtime.config.read()
        val uri = settings.optString("obsidianVault")
        require(uri.startsWith("content://")) { "Choose an Obsidian folder" }
        val tree = SafTree(runtime.context, Uri.parse(uri))
        val folder = settings.optString("obsidianFolder", "Murmur").trim('/')
        val base = fileBase(meeting)
        val exported = meeting.copyJson()
        exported.array("screens").objects().forEachIndexed { index, screen ->
            val name = "${meeting.getString("id")}-${index+1}.jpg"
            tree.write("attachments/$name", "image/jpeg", privateFile(screen.getString("path")).readBytes())
            screen.put("path", "attachments/$name")
        }
        val note = listOf(folder, "$base.md").filter(String::isNotBlank).joinToString("/")
        tree.write(note, "text/markdown", manager.obsidian(exported, settings.optBoolean("obsidianPeople"), settings.optString("uiLanguage", "ca")).toByteArray())
        return Uri.Builder().scheme("obsidian").authority("open").appendQueryParameter("vault", settings.optString("obsidianName"))
            .appendQueryParameter("file", note.removeSuffix(".md")).build().toString()
    }
    private fun privateFile(path: String): File {
        val file = (if (path.startsWith('/')) File(path) else File(runtime.dataDir, path)).canonicalFile
        require(file.path.startsWith(runtime.context.filesDir.canonicalPath + File.separator) || file.path.startsWith(runtime.context.cacheDir.canonicalPath + File.separator))
        return file
    }
    private fun fileBase(meeting: JSONObject): String {
        val day = Instant.ofEpochMilli(meeting.optLong("createdAt")).atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ISO_LOCAL_DATE)
        return "$day ${safeName(meeting.optString("title"))}"
    }
    companion object { fun safeName(title: String) = title.replace(Regex("[/\\\\?%*:|\"<>\\p{Cntrl}]"), "-").trim().take(150).ifBlank { "Murmur" } }
}

/** Keeps the wire-format implementation in the integration package and meeting edits in Rust. */
class CloudShare(private val runtime: AppRuntime) {
    private val manager = ShareManager(runtime.context)
    suspend fun connect(url: String, key: String) { manager.connect(url, key); runtime.refresh() }
    suspend fun setup(token: String, accountId: String) {
        if (accountId.isBlank()) {
            val accounts = manager.accounts(token)
            if (accounts.size == 1) manager.setup(token, accounts[0].getString("id"))
            else {
                runtime.updateState { it.copy(settings = it.settings.copyJson().put("shareAccounts", JSONArray(accounts))) }
                return
            }
        } else manager.setup(token, accountId)
        runtime.refresh()
    }
    suspend fun publish(meeting: JSONObject, options: JSONObject): String {
        val link = manager.publish(meeting, options.copyJson().put("language", dev.murmur.android.intelligence.resolveUiLanguage(runtime.config.read().optString("uiLanguage", "ca"))))
        runtime.edit(meeting.getString("id")) { it.put("shares", JSONArray().put(link).also { links -> it.array("shares").objects().forEach(links::put) }) }
        runtime.analytics.track("shared")
        return link.getString("url")
    }
    suspend fun revoke(meetingId: String, shareId: String) {
        manager.revoke(shareId)
        runtime.edit(meetingId) { it.put("shares", JSONArray(it.array("shares").objects().filter { link -> link.optString("id") != shareId })) }
    }
    suspend fun revokeAll(meeting: JSONObject) { meeting.array("shares").objects().forEach { manager.revoke(it.getString("id")) } }
}
