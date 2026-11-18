package dev.murmur.android.integrations

import dev.murmur.android.intelligence.obj
import org.junit.Assert.*
import org.junit.Test
import java.util.Base64
import java.util.zip.GZIPInputStream
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

class ShareCryptoTest {
    @Test fun unicodePasswordKdfMatchesDesktopWebCryptoVector() {
        // Independently generated with Node crypto.pbkdf2Sync: UTF-8 password, SHA256, 600000.
        val salt = ByteArray(16) { it.toByte() }
        val hex = ShareCrypto.derive("contraseña café 🔒", salt).joinToString("") { "%02x".format(it.toInt() and 255) }
        assertEquals("ae8091b8f3a075364141003672e92d406eb2faa74472b613b826006e2e357541", hex)
    }
    @Test fun envelopeCanBeOpenedByOriginalViewerAlgorithm() {
        val secret = ShareCrypto.key(null)
        val payload = obj("title" to "Reunió privada", "v" to 1)
        val envelope = ShareCrypto.seal(payload, secret)
        assertFalse(envelope.toString().contains("Reunió privada"))
        assertEquals(1, envelope.getInt("v")); assertFalse(envelope.has("kdf"))
        val iv = Base64.getDecoder().decode(envelope.getString("iv")); assertEquals(12, iv.size)
        val decodedKey = Base64.getUrlDecoder().decode(secret.fragment)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.DECRYPT_MODE, SecretKeySpec(decodedKey, "AES"), GCMParameterSpec(128, iv)) }
        val gzip = cipher.doFinal(Base64.getDecoder().decode(envelope.getString("data")))
        val text = GZIPInputStream(gzip.inputStream()).bufferedReader().use { it.readText() }
        assertEquals(payload.toString(), text)
        val encryptedFile = ShareCrypto.sealFile(byteArrayOf(1, 2, 3), decodedKey)
        assertEquals(12 + 3 + 16, encryptedFile.size)
    }
    @Test fun longNotionTextSplitsWithoutLosingUtf8OrSurrogatePairs() {
        val source = "català 🔒\n\n".repeat(100)
        val parts = splitMarkdown(source, 73)
        assertEquals(source, parts.joinToString("")); assertTrue(parts.all { it.toByteArray().size <= 73 })
        assertFalse(parts.any { it.endsWith("\uD83D") || it.startsWith("\uDD12") })
    }
}
