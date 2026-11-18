package dev.murmur.android

import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest

/** A separate Android channel; a desktop Murmur release is never treated as an APK update. */
class ApkUpdater(private val runtime: AppRuntime) {
    private val client = OkHttpClient()
    suspend fun check(): JSONObject? = withContext(Dispatchers.IO) {
        val url = runtime.config.read().optString("updateManifest")
        require(url.startsWith("https://")) { "Configure the Android update manifest URL" }
        val manifest = client.newCall(Request.Builder().url(url).build()).execute().use {
            require(it.isSuccessful); JSONObject(it.body!!.string())
        }
        if (manifest.getLong("versionCode") <= BuildConfig.VERSION_CODE) return@withContext null
        require(manifest.getString("url").startsWith("https://"))
        require(manifest.getString("sha256").matches(Regex("[a-fA-F0-9]{64}")))
        manifest
    }
    suspend fun download(manifest: JSONObject): File = withContext(Dispatchers.IO) {
        val file = File(runtime.context.cacheDir, "updates/murmur-${manifest.getLong("versionCode")}.apk").apply { parentFile!!.mkdirs() }
        val digest = MessageDigest.getInstance("SHA-256")
        client.newCall(Request.Builder().url(manifest.getString("url")).build()).execute().use { response ->
            require(response.isSuccessful)
            response.body!!.byteStream().use { input -> file.outputStream().use { output ->
                val buffer = ByteArray(128 * 1024)
                var size = 0L
                while (true) {
                    val count = input.read(buffer); if (count < 0) break
                    size += count; require(size < 1024L * 1024 * 1024)
                    digest.update(buffer, 0, count); output.write(buffer, 0, count)
                }
            } }
        }
        val hash = digest.digest().joinToString("") { "%02x".format(it) }
        if (!hash.equals(manifest.getString("sha256"), true)) { file.delete(); error("Update checksum mismatch") }
        val pm = runtime.context.packageManager
        val archive = pm.getPackageArchiveInfo(file.path, PackageManager.GET_SIGNING_CERTIFICATES) ?: error("Invalid APK")
        require(archive.packageName == runtime.context.packageName && archive.longVersionCode > BuildConfig.VERSION_CODE)
        val current = pm.getPackageInfo(runtime.context.packageName, PackageManager.GET_SIGNING_CERTIFICATES)
        val installed = current.signingInfo!!.apkContentsSigners.map { it.toCharsString() }.toSet()
        val incoming = archive.signingInfo!!.apkContentsSigners.map { it.toCharsString() }.toSet()
        require(incoming == installed) { "Update signing certificate does not match" }
        file
    }
    fun install(file: File) {
        val uri = FileProvider.getUriForFile(runtime.context, "${runtime.context.packageName}.files", file)
        runtime.context.startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(uri, "application/vnd.android.package-archive")
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION))
    }
}
