package dev.murmur.android

import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.UUID

/** Pure authentication policy, independent of HTTP and Android lifecycle. Only hashes persist. */
internal class McpAccess(
    initial: JSONObject = JSONObject(),
    private val save: (JSONObject) -> Unit = {},
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val random = SecureRandom()
    private val clients = initial.optJSONArray("clients")?.let { array ->
        (0 until array.length()).map { array.getJSONObject(it) }.toMutableList()
    } ?: mutableListOf()
    private var pairing: Window? = null
    private data class Window(val secret: String, val expires: Long, var request: Pending? = null)
    private data class Pending(val id: String, val name: String, var approved: Boolean = false, var token: String? = null, var client: String? = null)

    @Synchronized fun openPairing(): Pair<String, Long> {
        val now = clock()
        val window = Window(token(), now + 5 * 60_000)
        pairing = window
        return window.secret to window.expires
    }
    @Synchronized fun closePairing() { pairing = null }

    @Synchronized fun request(secret: String, name: String): String {
        val window = validWindow(secret)
        require(window.request == null) { "Pairing code already used" }
        val cleanName = name.filter { !it.isISOControl() }.trim().take(80).ifBlank { "MCP client" }
        val id = UUID.randomUUID().toString()
        window.request = Pending(id, cleanName)
        return id
    }
    @Synchronized fun approve(id: String) {
        val window = pairing?.takeIf { it.expires > clock() } ?: error("Pairing expired")
        val pending = window.request?.takeIf { it.id == id } ?: error("Pairing request missing")
        if (pending.approved) return
        val raw = token()
        val client = UUID.randomUUID().toString()
        clients += JSONObject().put("id", client).put("name", pending.name).put("createdAt", clock()).put("verifier", sha256(raw))
        pending.approved = true; pending.token = raw; pending.client = client
        persist()
    }
    @Synchronized fun reject(id: String) { if (pairing?.request?.id == id) pairing = null }
    @Synchronized fun result(id: String, secret: String): JSONObject {
        val window = validWindow(secret)
        val request = window.request?.takeIf { it.id == id } ?: error("Pairing request missing")
        return if (!request.approved) JSONObject().put("status", "pending")
        else JSONObject().put("status", "approved").put("clientId", request.client).put("token", request.token)
    }
    @Synchronized fun acknowledge(id: String, secret: String) {
        val window = validWindow(secret)
        require(window.request?.id == id && window.request?.approved == true)
        pairing = null
    }
    @Synchronized fun authenticate(raw: String): String? {
        if (raw.length !in 40..100) return null
        val digest = sha256(raw).toByteArray(Charsets.US_ASCII)
        return clients.firstOrNull { MessageDigest.isEqual(it.optString("verifier").toByteArray(Charsets.US_ASCII), digest) }?.getString("id")
    }
    @Synchronized fun revoke(client: String?) {
        if (client.isNullOrBlank()) clients.clear() else clients.removeAll { it.optString("id") == client }
        persist()
    }
    @Synchronized fun publicClients(): JSONArray = JSONArray(clients.map { JSONObject(it.toString()).apply { remove("verifier") } })
    @Synchronized fun pending(): JSONArray {
        val window = pairing?.takeIf { it.expires > clock() } ?: return JSONArray()
        val request = window.request?.takeIf { !it.approved } ?: return JSONArray()
        return JSONArray().put(JSONObject().put("id", request.id).put("name", request.name))
    }
    @Synchronized fun expiresAt(): Long = pairing?.takeIf { it.expires > clock() }?.expires ?: 0
    private fun validWindow(secret: String): Window {
        val window = pairing?.takeIf { it.expires > clock() } ?: error("Pairing expired")
        require(MessageDigest.isEqual(sha256(secret).toByteArray(), sha256(window.secret).toByteArray())) { "Invalid pairing code" }
        return window
    }
    private fun persist() = save(JSONObject().put("clients", JSONArray(clients)))
    private fun token(): String = java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(32).also(random::nextBytes))
    companion object {
        fun sha256(value: String): String = MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
        fun validOrigin(origin: String?, address: String): Boolean = origin == null || origin == address
        fun privateAddress(address: String): Boolean {
            val parts = address.split('.').map { it.toIntOrNull() ?: return false }
            if (parts.size != 4 || parts.any { it !in 0..255 }) return false
            return parts[0] == 10 || (parts[0] == 172 && parts[1] in 16..31) ||
                (parts[0] == 192 && parts[1] == 168) || (parts[0] == 169 && parts[1] == 254)
        }
    }
}
