package dev.murmur.android

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class McpAccessTest {
    @Test fun credentialsRequireExplicitApprovalAndPersistOnlyVerifiers() {
        var persisted = JSONObject()
        val access = McpAccess(save = { persisted = it })
        val (secret, _) = access.openPairing()
        val id = access.request(secret, "Claude on laptop")
        assertEquals("pending", access.result(id, secret).getString("status"))
        assertEquals(0, persisted.optJSONArray("clients")?.length() ?: 0)
        access.approve(id)
        val approved = access.result(id, secret)
        val token = approved.getString("token")
        assertEquals(43, token.length)
        assertEquals(approved.getString("clientId"), access.authenticate(token))
        assertFalse(persisted.toString().contains(token))
        assertFalse(persisted.toString().contains(secret))
        assertFalse(access.publicClients().getJSONObject(0).has("verifier"))
        val restored = McpAccess(persisted)
        assertEquals(approved.getString("clientId"), restored.authenticate(token))
        restored.revoke(approved.getString("clientId"))
        assertNull(restored.authenticate(token))
        access.acknowledge(id, secret)
        assertEquals(0, access.expiresAt())
    }

    @Test fun pairingCodesAreOneUseExpireAndCannotBypassApproval() {
        var now = 1000L
        val access = McpAccess(clock = { now })
        val (secret, _) = access.openPairing()
        val request = access.request(secret, "Laptop")
        assertThrows(IllegalArgumentException::class.java) { access.request(secret, "Other laptop") }
        assertThrows(IllegalArgumentException::class.java) { access.result(request, "bad") }
        now += 5 * 60_000L + 1
        assertThrows(IllegalStateException::class.java) { access.approve(request) }
        assertEquals(0, access.pending().length())
        assertNull(access.authenticate("x".repeat(43)))
    }

    @Test fun rejectedCodesAndRevokedCredentialsStopWorking() {
        val access = McpAccess()
        val (secret, _) = access.openPairing()
        val request = access.request(secret, "Laptop")
        access.reject(request)
        assertThrows(IllegalStateException::class.java) { access.result(request, secret) }
        val (second, _) = access.openPairing()
        val next = access.request(second, "Laptop")
        access.approve(next)
        val token = access.result(next, second).getString("token")
        access.revoke(null)
        assertNull(access.authenticate(token))
    }

    @Test fun allowedOriginsAndAddressesAreRestrictedToThePrivateEndpoint() {
        assertTrue(McpAccess.validOrigin(null, "https://192.168.1.2:4000"))
        assertTrue(McpAccess.validOrigin("https://192.168.1.2:4000", "https://192.168.1.2:4000"))
        assertFalse(McpAccess.validOrigin("https://attacker.example", "https://192.168.1.2:4000"))
        assertFalse(McpAccess.validOrigin("null", "https://192.168.1.2:4000"))
        assertTrue(McpAccess.privateAddress("10.0.0.1"))
        assertTrue(McpAccess.privateAddress("172.16.0.1"))
        assertTrue(McpAccess.privateAddress("192.168.1.2"))
        assertFalse(McpAccess.privateAddress("172.32.0.1"))
        assertFalse(McpAccess.privateAddress("127.0.0.1"))
        assertFalse(McpAccess.privateAddress("8.8.8.8"))
    }
}
