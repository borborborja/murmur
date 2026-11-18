package dev.murmur.android

import android.content.Intent
import android.net.Uri
import androidx.core.content.ContextCompat
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.net.URL
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import java.util.UUID
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLContext
import javax.net.ssl.X509TrustManager

/** Real Android Keystore TLS, foreground lifecycle, visible pairing and authenticated RPC. */
@RunWith(AndroidJUnit4::class)
class McpAndroidSmokeTest {
    @Test fun localConnectorPairsAndReadsPhoneDatabaseThroughPinnedTls() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        assumeTrue("A private Wi-Fi or Ethernet LAN is required", SyncDiscovery.localNetwork(context))
        val meetingId = "mcp-test-${UUID.randomUUID()}"
        var clientId: String? = null
        val scenario = ActivityScenario.launch(MainActivity::class.java)
        val runtime = AppRuntime.get(context)
        try {
            runBlocking {
                runtime.put(json("id" to meetingId, "title" to "Connector smoke test", "createdAt" to System.currentTimeMillis(),
                    "duration" to 1, "status" to "done", "speakerNames" to json("0" to "Test speaker"),
                    "utterances" to JSONArray().put(json("id" to "$meetingId-0", "start" to 0, "end" to 1, "speaker" to 0, "text" to "A meeting stored only on this phone."))))
            }
            scenario.onActivity { ContextCompat.startForegroundService(it, Intent(it, McpService::class.java).setAction(McpService.PAIR)) }
            val status = waitFor { McpService.status().takeIf { it.optBoolean("running") && !it.isNull("pairingUri") } }
            val address = status.getString("address")
            val pin = status.getString("fingerprint")
            val secret = Uri.parse(status.getString("pairingUri")).getQueryParameter("secret")!!
            val start = post(address, pin, "/pair", json("secret" to secret, "name" to "Instrumentation client"))
            assertEquals(200, start.first)
            val requestId = start.second.getString("requestId")
            assertEquals("pending", post(address, pin, "/pair/status", json("requestId" to requestId, "secret" to secret)).second.getString("status"))
            scenario.onActivity { ContextCompat.startForegroundService(it, Intent(it, McpService::class.java).setAction(McpService.APPROVE).putExtra("requestId", requestId)) }
            val approved = waitFor { post(address, pin, "/pair/status", json("requestId" to requestId, "secret" to secret)).second.takeIf { it.optString("status") == "approved" } }
            val token = approved.getString("token")
            clientId = approved.getString("clientId")
            assertEquals(43, token.length)
            assertEquals(200, post(address, pin, "/pair/ack", json("requestId" to requestId, "secret" to secret)).first)
            val initialize = json("jsonrpc" to "2.0", "id" to 1, "method" to "initialize", "params" to json("protocolVersion" to "2025-11-25", "capabilities" to JSONObject(), "clientInfo" to json("name" to "test", "version" to "1")))
            assertEquals(401, post(address, pin, "/mcp", initialize).first)
            assertEquals(403, post(address, pin, "/mcp", initialize, token, "https://untrusted.example").first)
            assertEquals("2025-11-25", post(address, pin, "/mcp", initialize, token).second.getJSONObject("result").getString("protocolVersion"))
            assertEquals(202, post(address, pin, "/mcp", json("jsonrpc" to "2.0", "method" to "notifications/initialized"), token).first)
            val tools = post(address, pin, "/mcp", json("jsonrpc" to "2.0", "id" to 2, "method" to "tools/list"), token).second.getJSONObject("result").getJSONArray("tools")
            assertEquals(5, tools.length())
            val get = post(address, pin, "/mcp", json("jsonrpc" to "2.0", "id" to 3, "method" to "tools/call", "params" to json("name" to "get_meeting", "arguments" to json("id" to meetingId))), token).second
            assertTrue(get.getJSONObject("result").getJSONArray("content").getJSONObject(0).getString("text").contains("A meeting stored only on this phone."))
            val files = post(address, pin, "/uploads", byteArrayOf(1, 2, 3, 4), token)
            assertEquals(200, files.first)
            assertTrue(files.second.getString("upload_id").matches(Regex("[a-f0-9-]{36}")))
            scenario.onActivity { ContextCompat.startForegroundService(it, Intent(it, McpService::class.java).setAction(McpService.REVOKE).putExtra("clientId", clientId)) }
            waitFor { McpService.status().takeIf { it.array("clients").objects().none { client -> client.optString("id") == clientId } } }
            assertEquals(401, post(address, pin, "/mcp", initialize, token).first)
        } finally {
            scenario.onActivity { it.startService(Intent(it, McpService::class.java).setAction(McpService.STOP)) }
            runBlocking { runtime.native("delete", json("id" to meetingId)) }
            scenario.close()
        }
    }

    private fun waitFor(block: () -> JSONObject?): JSONObject {
        val end = System.currentTimeMillis() + 30_000
        while (System.currentTimeMillis() < end) { block()?.let { return it }; Thread.sleep(100) }
        throw AssertionError("Android connector did not reach the expected state")
    }

    private fun post(address: String, pin: String, endpoint: String, data: Any, token: String? = null, origin: String? = null): Pair<Int, JSONObject> {
        val trust = object : X509TrustManager {
            override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
            override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) = Unit
            override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) {
                val actual = MessageDigest.getInstance("SHA-256").digest(chain[0].encoded).joinToString("") { "%02x".format(it) }
                if (actual != pin) throw CertificateException("Certificate pin mismatch")
            }
        }
        val tls = SSLContext.getInstance("TLS").apply { init(null, arrayOf(trust), SecureRandom()) }
        val connection = URL(address + endpoint).openConnection() as HttpsURLConnection
        connection.sslSocketFactory = tls.socketFactory
        connection.hostnameVerifier = javax.net.ssl.HostnameVerifier { _, _ -> true } // Exact certificate pin is the identity.
        connection.connectTimeout = 5000; connection.readTimeout = 5000
        connection.requestMethod = "POST"; connection.doOutput = true
        val bytes = if (data is ByteArray) data else data.toString().toByteArray()
        connection.setRequestProperty("Content-Type", if (data is ByteArray) "application/octet-stream" else "application/json")
        connection.setRequestProperty("MCP-Protocol-Version", "2025-11-25")
        token?.let { connection.setRequestProperty("Authorization", "Bearer $it") }
        origin?.let { connection.setRequestProperty("Origin", it) }
        connection.setFixedLengthStreamingMode(bytes.size)
        try {
            connection.outputStream.use { it.write(bytes) }
            val status = connection.responseCode
            val stream = if (status >= 400) connection.errorStream else connection.inputStream
            val body = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
            return status to if (body.isBlank()) JSONObject() else JSONObject(body)
        } finally { connection.disconnect() }
    }
}
