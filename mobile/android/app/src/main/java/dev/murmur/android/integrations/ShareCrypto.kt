package dev.murmur.android.integrations

import dev.murmur.android.intelligence.obj
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.security.SecureRandom
import java.util.Base64
import java.util.zip.GZIPOutputStream
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

data class ShareKey(val bytes: ByteArray, val fragment: String?, val kdf: JSONObject?)

/** Wire-compatible with the original Cloudflare viewer: envelope v1 and IV || ciphertext files. */
object ShareCrypto {
    private val random = SecureRandom()
    fun key(password: String?): ShareKey {
        if (!password.isNullOrBlank()) {
            val salt = ByteArray(16).also(random::nextBytes)
            val key = derive(password, salt)
            return ShareKey(key, null, obj("name" to "PBKDF2", "hash" to "SHA-256", "iterations" to 600_000, "salt" to b64(salt)))
        }
        val key = ByteArray(32).also(random::nextBytes)
        return ShareKey(key, Base64.getUrlEncoder().withoutPadding().encodeToString(key), null)
    }
    internal fun derive(password: String, salt: ByteArray): ByteArray {
        val spec = PBEKeySpec(password.toCharArray(), salt, 600_000, 256)
        return try { SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded } finally { spec.clearPassword() }
    }
    fun seal(content: JSONObject, key: ShareKey): JSONObject {
        val gzip = ByteArrayOutputStream().also { output -> GZIPOutputStream(output).use { it.write(content.toString().toByteArray(Charsets.UTF_8)) } }.toByteArray()
        val encrypted = sealFile(gzip, key.bytes)
        return obj("v" to 1, "iv" to b64(encrypted.copyOfRange(0, 12)), "data" to b64(encrypted.copyOfRange(12, encrypted.size))).apply { key.kdf?.let { put("kdf", it) } }
    }
    fun sealFile(bytes: ByteArray, key: ByteArray): ByteArray {
        val iv = ByteArray(12).also(random::nextBytes)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, iv)) }
        return iv + cipher.doFinal(bytes)
    }
    private fun b64(bytes: ByteArray): String = Base64.getEncoder().encodeToString(bytes)
}
