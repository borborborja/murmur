package dev.murmur.android

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import kotlin.coroutines.coroutineContext

class ModelManager(context: Context, private val native: suspend (String, JSONObject) -> JSONObject) {
    val root = File(context.filesDir, "models").apply { mkdirs() }
    private val client = OkHttpClient.Builder().connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(90, TimeUnit.SECONDS).build()

    suspend fun catalog(): List<JSONObject> = native("model_catalog", JSONObject()).array("models").objects().onEach { model ->
        val receipt = File(root, ".installed-${model.getString("id")}.json")
        val current = model.array("files").objects().map { it.getString("sha256") }
        val previous = runCatching { JSONObject(receipt.readText()).array("hashes").strings() }.getOrNull()
        val update = previous != null && previous != current
        val intactSize = model.array("files").objects().all { resolve(it.getString("path")).length() == it.getLong("bytes") }
        model.put("updateAvailable", update).put("installed", model.optBoolean("installed") && !update && intactSize)
    }
    suspend fun path(id: String): String {
        val model = catalog().firstOrNull { it.optString("id") == id } ?: error("Unknown model")
        check(model.optBoolean("installed")) { "Model not installed: $id" }
        return resolve(model.array("files").getJSONObject(0).getString("path")).absolutePath
    }
    fun resolve(relative: String): File {
        val file = File(root, relative).canonicalFile
        require(file.path.startsWith(root.canonicalPath + File.separator))
        return file
    }

    suspend fun download(id: String, progress: (Long, Long) -> Unit) = withContext(Dispatchers.IO) {
        val model = catalog().firstOrNull { it.optString("id") == id } ?: error("Unknown model")
        val files = model.array("files").objects()
        val total = files.sumOf { it.getLong("bytes") }
        var completed = 0L
        require(root.usableSpace > files.sumOf { f ->
            val dest = resolve(f.getString("path"))
            if (dest.exists() && dest.length() == f.getLong("bytes")) 0L else
                (f.getLong("bytes") - File(dest.path + ".part").length()).coerceAtLeast(0L)
        } + 64L * 1024 * 1024) { "Insufficient storage" }
        for (spec in files) {
            coroutineContext.ensureActive()
            val dest = resolve(spec.getString("path"))
            val size = spec.getLong("bytes")
            val hash = spec.getString("sha256")
            if (dest.exists() && dest.length() == size && sha256(dest) == hash) {
                completed += size
                progress(completed, total)
                continue
            }
            dest.parentFile?.mkdirs()
            val part = File(dest.path + ".part")
            if (part.length() > size) part.delete()
            var offset = part.length()
            val url = spec.getString("url")
            require(url.startsWith("https://"))
            val request = Request.Builder().url(url).apply {
                if (offset > 0) header("Range", "bytes=$offset-")
            }.build()
            client.newCall(request).execute().use { response ->
                if (response.code == 416 && offset == size) {
                    // A previously completed partial file still needs checksum verification.
                } else {
                    check(response.isSuccessful) { "Model download HTTP ${response.code}" }
                    if (response.code != 206) offset = 0
                    if (response.code == 206) {
                        require(response.header("Content-Range")?.startsWith("bytes $offset-") == true)
                    }
                    val body = response.body ?: error("Empty model download")
                    body.byteStream().use { input ->
                        FileOutputStream(part, offset > 0).use { output ->
                            val buffer = ByteArray(256 * 1024)
                            var downloaded = offset
                            while (true) {
                                coroutineContext.ensureActive()
                                val count = input.read(buffer)
                                if (count < 0) break
                                require(downloaded + count <= size) { "Model exceeds catalog size" }
                                output.write(buffer, 0, count)
                                downloaded += count
                                progress(completed + downloaded, total)
                            }
                            output.fd.sync()
                        }
                    }
                }
            }
            require(part.length() == size) { "Incomplete model download" }
            if (sha256(part) != hash) {
                part.delete()
                error("Model checksum mismatch")
            }
            check(part.renameTo(dest))
            completed += size
            progress(completed, total)
        }
        val receipt = File(root, ".installed-$id.json")
        val stagedReceipt = File(receipt.path + ".tmp")
        stagedReceipt.writeText(json("hashes" to org.json.JSONArray(files.map { it.getString("sha256") })).toString())
        check(stagedReceipt.renameTo(receipt))
    }

    suspend fun delete(id: String) {
        native("unload", JSONObject())
        val model = catalog().firstOrNull { it.optString("id") == id } ?: return
        model.array("files").objects().forEach {
            val dest = resolve(it.getString("path"))
            dest.delete()
            File(dest.path + ".part").delete()
        }
        File(root, ".installed-$id.json").delete()
    }

    private suspend fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().buffered().use { input ->
            val buffer = ByteArray(256 * 1024)
            while (true) {
                coroutineContext.ensureActive()
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}
