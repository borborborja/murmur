package dev.murmur.android.intelligence

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Only ciphertext is persisted; the non-exportable wrapping key stays in Android Keystore. */
class SecretStore(context: Context) {
    private val preferences = context.getSharedPreferences("murmur.encrypted-secrets", Context.MODE_PRIVATE)
    private val alias = "dev.murmur.android.secrets.v1"
    private fun key(): SecretKey = synchronized(keyLock) {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(alias, null) as? SecretKey)?.let { return@synchronized it }
        KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").run {
            init(KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
            generateKey()
        }
    }
    fun put(name: String, secret: String) {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key()) }
        val encoded = Base64.encodeToString(cipher.iv + cipher.doFinal(secret.toByteArray(Charsets.UTF_8)), Base64.NO_WRAP)
        check(preferences.edit().putString(name, encoded).commit())
    }
    fun get(name: String): String? {
        val raw = preferences.getString(name, null) ?: return null
        return runCatching {
            val bytes = Base64.decode(raw, Base64.NO_WRAP)
            require(bytes.size > 28)
            Cipher.getInstance("AES/GCM/NoPadding").run {
                init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, bytes.copyOfRange(0, 12)))
                doFinal(bytes.copyOfRange(12, bytes.size)).toString(Charsets.UTF_8)
            }
        }.getOrNull()
    }
    fun remove(name: String) { check(preferences.edit().remove(name).commit()) }
    companion object { private val keyLock = Any() }
}
