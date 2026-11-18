package dev.murmur.android.integrations

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Base64
import dev.murmur.android.export.ExportManager
import dev.murmur.android.intelligence.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.InetAddress
import java.net.ServerSocket
import java.net.SocketTimeoutException
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.concurrent.atomic.AtomicBoolean

/** Notion's hosted MCP public-client OAuth flow, with PKCE and rotating refresh tokens. */
class NotionManager(private val context: Context) {
    private val store = SecretStore(context)
    private val cancelled = AtomicBoolean(false)
    private var listener: ServerSocket? = null
    private val endpoint = "https://mcp.notion.com/mcp"
    private val issuer = "https://mcp.notion.com"
    private fun grant(): JSONObject? = store.get("notion.grant")?.let { runCatching { JSONObject(it) }.getOrNull() }
    fun status(): JSONObject = obj("connected" to (grant() != null), "workspace" to grant()?.optString("workspace"))
    fun disconnect() = store.remove("notion.grant")
    fun cancel() { cancelled.set(true); runCatching { listener?.close() } }
    // A manual export and an automatic export may use different manager instances. Rotating
    // refresh tokens must nevertheless be consumed serially for the whole application.
    companion object { private val refreshMutex = Mutex() }

    suspend fun connect(openBrowser: (String) -> Unit = { url -> context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }): String {
        cancelled.set(false)
        val metadata = IntegrationHttp.json("GET", "$issuer/.well-known/oauth-authorization-server")
        require(metadata.getString("issuer") == issuer)
        fun secureEndpoint(name: String): String = metadata.getString(name).also { require(Uri.parse(it).scheme == "https" && Uri.parse(it).host == "mcp.notion.com") }
        val authorize = secureEndpoint("authorization_endpoint"); val token = secureEndpoint("token_endpoint"); val register = secureEndpoint("registration_endpoint")
        val socket = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).apply { soTimeout = 1000 }
        listener = socket
        try {
            val redirect = "http://127.0.0.1:${socket.localPort}/callback"
            val registration = IntegrationHttp.json("POST", register, body = obj("client_name" to "Murmur Android", "redirect_uris" to arr(listOf(redirect)), "grant_types" to arr(listOf("authorization_code", "refresh_token")), "response_types" to arr(listOf("code")), "token_endpoint_auth_method" to "none"))
            val clientId = registration.getString("client_id")
            val verifier = random(32); val state = random(24)
            val challenge = Base64.encodeToString(MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray()), Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
            val authUri = Uri.parse(authorize).buildUpon().appendQueryParameter("response_type", "code").appendQueryParameter("client_id", clientId).appendQueryParameter("redirect_uri", redirect).appendQueryParameter("state", state).appendQueryParameter("code_challenge", challenge).appendQueryParameter("code_challenge_method", "S256").appendQueryParameter("resource", endpoint).appendQueryParameter("prompt", "consent").build().toString()
            withContext(Dispatchers.Main) { openBrowser(authUri) }
            val code = withContext(Dispatchers.IO) {
                val until = System.currentTimeMillis() + 300_000
                var received: String? = null
                while (received == null && System.currentTimeMillis() < until && !cancelled.get()) {
                    currentCoroutineContext().ensureActive()
                    try {
                        socket.accept().use { peer ->
                            peer.soTimeout = 5000
                            val line = peer.getInputStream().bufferedReader().readLine().orEmpty().take(16_384)
                            val target = line.split(' ').getOrNull(1).orEmpty()
                            val uri = Uri.parse("http://127.0.0.1$target")
                            val valid = uri.path == "/callback" && uri.getQueryParameter("state") == state && (uri.getQueryParameter("iss") == null || uri.getQueryParameter("iss") == issuer)
                            val body = "<!doctype html><meta charset=utf-8><title>Murmur</title><p>${if (valid) "Murmur: OK. You can close this page." else "Murmur: invalid callback."}</p>".toByteArray()
                            peer.getOutputStream().write("HTTP/1.1 ${if (valid) "200 OK" else "400 Bad Request"}\r\nContent-Type: text/html; charset=utf-8\r\nContent-Length: ${body.size}\r\nConnection: close\r\n\r\n".toByteArray() + body)
                            if (valid) { if (uri.getQueryParameter("error") != null) throw IntelligenceException("notion_refused"); received = uri.getQueryParameter("code") }
                        }
                    } catch (_: SocketTimeoutException) { /* Check cancellation each second. */ }
                }
                received ?: throw IntelligenceException(if (cancelled.get()) "notion_cancelled" else "notion_timed_out")
            }
            val reply = IntegrationHttp.request("POST", token, body = IntegrationHttp.form("grant_type" to "authorization_code", "code" to code, "redirect_uri" to redirect, "client_id" to clientId, "code_verifier" to verifier, "resource" to endpoint), contentType = "application/x-www-form-urlencoded")
            IntegrationHttp.requireSuccess(reply)
            val tokens = reply.json()
            val grant = obj("client_id" to clientId, "access_token" to tokens.getString("access_token"), "refresh_token" to tokens.optString("refresh_token"), "expires_at" to (System.currentTimeMillis() + tokens.optLong("expires_in", 3600) * 1000), "workspace" to tokens.optString("workspace_name", "Notion"))
            store.put("notion.grant", grant.toString())
            return grant.getString("workspace")
        } finally { socket.close(); listener = null }
    }

    private suspend fun token(): String = refreshMutex.withLock {
        val grant = grant() ?: throw IntelligenceException("notion_not_connected")
        if (grant.optLong("expires_at") - System.currentTimeMillis() > 300_000) return@withLock grant.getString("access_token")
        val metadata = IntegrationHttp.json("GET", "$issuer/.well-known/oauth-authorization-server")
        require(metadata.getString("issuer") == issuer)
        val tokenUrl = metadata.getString("token_endpoint"); require(Uri.parse(tokenUrl).scheme == "https" && Uri.parse(tokenUrl).host == "mcp.notion.com")
        val reply = IntegrationHttp.request("POST", tokenUrl, body = IntegrationHttp.form("grant_type" to "refresh_token", "refresh_token" to grant.getString("refresh_token"), "client_id" to grant.getString("client_id"), "resource" to endpoint), contentType = "application/x-www-form-urlencoded")
        if (reply.status in listOf(400, 401)) { disconnect(); throw IntelligenceException("notion_reconnect") }
        IntegrationHttp.requireSuccess(reply)
        val tokens = reply.json()
        grant.put("access_token", tokens.getString("access_token")).put("expires_at", System.currentTimeMillis() + tokens.optLong("expires_in", 3600) * 1000)
        if (tokens.has("refresh_token")) grant.put("refresh_token", tokens.getString("refresh_token"))
        store.put("notion.grant", grant.toString())
        grant.getString("access_token")
    }

    private suspend fun call(tool: String, arguments: JSONObject): Any {
        val token = token(); var session: String? = null
        suspend fun post(body: JSONObject): JSONObject {
            val headers = mutableMapOf("Authorization" to "Bearer $token", "Accept" to "application/json, text/event-stream", "MCP-Protocol-Version" to "2025-06-18")
            session?.let { headers["Mcp-Session-Id"] = it }
            val reply = IntegrationHttp.request("POST", endpoint, headers, body.toString().toByteArray(), rpcId = if (body.has("id")) body.getInt("id") else null)
            IntegrationHttp.requireSuccess(reply); reply.headers["mcp-session-id"]?.let { session = it }
            return reply.json()
        }
        post(obj("jsonrpc" to "2.0", "id" to 1, "method" to "initialize", "params" to obj("protocolVersion" to "2025-06-18", "capabilities" to JSONObject(), "clientInfo" to obj("name" to "Murmur Android", "version" to "0.16.0"))))
        post(obj("jsonrpc" to "2.0", "method" to "notifications/initialized", "params" to JSONObject()))
        val response = post(obj("jsonrpc" to "2.0", "id" to 2, "method" to "tools/call", "params" to obj("name" to tool, "arguments" to arguments)))
        if (response.has("error")) throw IntelligenceException("notion_request_failed")
        val result = response.getJSONObject("result")
        if (result.optBoolean("isError")) throw IntelligenceException("notion_request_failed")
        result.optJSONObject("structuredContent")?.let { return it }
        val text = result.optJSONArray("content")?.objects().orEmpty().joinToString("\n") { it.optString("text") }
        return runCatching { JSONObject(text) }.getOrNull() ?: text
    }
    suspend fun search(query: String): List<JSONObject> {
        val result = (if (query.isBlank()) call("notion-list-recent-pages", obj("limit" to 30)) else call("notion-search", obj("query" to query))) as? JSONObject ?: return emptyList()
        return (result.optJSONArray("results") ?: result.optJSONArray("pages") ?: JSONArray()).objects().filter { it.optString("type", "page") == "page" }.mapNotNull { page ->
            val id = page.optString("id").ifBlank { Regex("([0-9a-fA-F]{32})(?:\\?|$)").find(page.optString("url"))?.groupValues?.get(1).orEmpty() }
            if (id.isBlank()) null else obj("id" to id, "title" to page.optString("title", "Notion"), "url" to page.optString("url"))
        }
    }
    private suspend fun create(parent: String?, title: String, content: String): JSONObject {
        val args = obj("pages" to arr(listOf(obj("properties" to obj("title" to title), "content" to content))))
        if (!parent.isNullOrBlank()) args.put("parent", obj("page_id" to parent))
        val result = call("notion-create-pages", args) as? JSONObject ?: throw IntelligenceException("notion_export_failed")
        return result.getJSONArray("pages").getJSONObject(0)
    }
    suspend fun export(meeting: JSONObject, parent: String? = null, language: String = "ca"): String {
        val exports = ExportManager(context)
        if (meeting.optJSONObject("cover") == null) {
            // Without a cover, both original export selectors intentionally mean "all".
            // Export once, otherwise a voice note appears twice in the Notion page.
            val pieces = splitMarkdown(exports.markdown(meeting, "all", language), 40_000)
            val page = create(parent, meeting.optString("title"), pieces.firstOrNull().orEmpty())
            pieces.drop(1).forEachIndexed { index, piece ->
                create(page.getString("id"), "${Catalog(context, language).text("export.transcript")} (${index + 2}/${pieces.size})", piece)
            }
            return page.getString("url")
        }
        val summary = exports.markdown(meeting, "summary", language); val transcript = exports.markdown(meeting, "transcript", language)
        if (summary.toByteArray().size + transcript.toByteArray().size < 40_000) return create(parent, meeting.optString("title"), "$summary\n\n$transcript").getString("url")
        val summaryPieces = splitMarkdown(summary, 40_000)
        val page = create(parent, meeting.optString("title"), summaryPieces.firstOrNull().orEmpty())
        summaryPieces.drop(1).forEachIndexed { index, piece -> create(page.getString("id"), "${Catalog(context, language).text("app.viewCover")} (${index + 2}/${summaryPieces.size})", piece) }
        val pieces = splitMarkdown(transcript, 40_000)
        pieces.forEachIndexed { index, piece -> create(page.getString("id"), "${Catalog(context, language).text("export.transcript")} (${index + 1}/${pieces.size})", piece) }
        return page.getString("url")
    }
    private fun random(bytes: Int): String = Base64.encodeToString(ByteArray(bytes).also { SecureRandom().nextBytes(it) }, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
}

/** Split by UTF-8 bytes, including an oversized paragraph, without cutting surrogate pairs. */
internal fun splitMarkdown(text: String, maximum: Int): List<String> {
    val pieces = mutableListOf<String>(); var current = StringBuilder(); var bytes = 0
    text.codePoints().forEachOrdered { point -> val part = String(Character.toChars(point)); val size = part.toByteArray().size; if (bytes + size > maximum && current.isNotEmpty()) { pieces += current.toString(); current = StringBuilder(); bytes = 0 }; current.append(part); bytes += size }
    if (current.isNotEmpty()) pieces += current.toString()
    return pieces
}
