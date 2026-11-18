package dev.murmur.android

import android.app.*
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.*
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.IBinder
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import androidx.core.app.NotificationCompat
import dev.murmur.android.export.ExportManager
import fi.iki.elonen.NanoHTTPD
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.math.BigInteger
import java.net.Inet4Address
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.MessageDigest
import java.security.Principal
import java.security.PrivateKey
import java.security.Signature
import java.security.cert.X509Certificate
import java.security.spec.ECGenParameterSpec
import java.net.Socket
import java.util.*
import java.util.concurrent.ConcurrentHashMap
import javax.net.ssl.X509ExtendedKeyManager
import javax.net.ssl.SSLEngine
import javax.net.ssl.SSLContext
import javax.security.auth.x500.X500Principal

/** Explicitly activated LAN-only direct MCP. No public endpoint, tunnel, or cloud relay. */
class McpService : Service() {
    private lateinit var runtime: AppRuntime
    private lateinit var authority: McpAccess
    private var server: Server? = null
    private var pairingUri: String? = null
    private var fingerprint = ""
    private var address = ""
    private var network: Network? = null
    private var serviceRegistration: NsdManager.RegistrationListener? = null
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val uploads = ConcurrentHashMap<String, Upload>()
    private val jobs = ConcurrentHashMap<String, String>()
    private val jobsFile by lazy { File(filesDir, "mcp-jobs.json") }
    private var activeUploads = 0
    private data class Upload(val file: File, val owner: String, val created: Long)
    private val attempts = ConcurrentHashMap<String, Pair<Long, Int>>()
    private val connectivity by lazy { getSystemService(ConnectivityManager::class.java) }
    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onLost(lost: Network) { if (lost == network) serviceScope.launch { shutdown() } }
        override fun onCapabilitiesChanged(changed: Network, capabilities: NetworkCapabilities) {
            if (changed == network && !localTransport(capabilities)) serviceScope.launch { shutdown() }
        }
        override fun onLinkPropertiesChanged(changed: Network, properties: LinkProperties) {
            val host = server?.lanHostname ?: return
            if (changed == network && properties.linkAddresses.none { it.address.hostAddress == host }) serviceScope.launch { shutdown() }
        }
    }

    override fun onCreate() {
        super.onCreate()
        runtime = AppRuntime.get(this)
        val file = File(filesDir, "mcp-clients.json")
        authority = McpAccess(runCatching { JSONObject(file.readText()) }.getOrDefault(JSONObject()), { state ->
            val temp = File(filesDir, "mcp-clients.json.tmp")
            temp.writeText(state.toString()); check(temp.renameTo(file))
        })
        runCatching { JSONObject(jobsFile.readText()) }.getOrNull()?.let { saved ->
            saved.keys().forEach { key -> jobs[key] = saved.getString(key) }
        }
        File(filesDir, "mcp-uploads").apply { mkdirs() }.listFiles()?.forEach { it.delete() }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action ?: START) {
            STOP -> { shutdown(); return START_NOT_STICKY }
            REVOKE -> {
                authority.revoke(intent?.getStringExtra("clientId"))
                publish()
                if (server == null) stopSelf()
                return START_NOT_STICKY
            }
            else -> {
                foreground()
                serviceScope.launch {
                    try {
                        if (server == null) startServer()
                        when (intent?.action) {
                            PAIR -> openPairing()
                            APPROVE -> authority.approve(intent.getStringExtra("requestId").orEmpty())
                            REJECT -> authority.reject(intent.getStringExtra("requestId").orEmpty())
                            REVOKE -> authority.revoke(intent.getStringExtra("clientId"))
                        }
                        publish()
                    } catch (error: Exception) { runtime.failure("mcp.service", error); shutdown() }
                }
            }
        }
        return START_NOT_STICKY
    }

    private fun foreground() {
        val notifications = getSystemService(NotificationManager::class.java)
        notifications.createNotificationChannel(NotificationChannel("mcp", getString(R.string.connector_notification), NotificationManager.IMPORTANCE_LOW))
        val stop = PendingIntent.getService(this, 3001, Intent(this, McpService::class.java).setAction(STOP), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val launch = PendingIntent.getActivity(this, 3002, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val notification = NotificationCompat.Builder(this, "mcp").setSmallIcon(R.drawable.murmur_icon)
            .setContentTitle(getString(R.string.app_name)).setContentText(getString(R.string.connector_notification))
            .setContentIntent(launch).setOngoing(true).addAction(0, getString(R.string.stop), stop).build()
        startForeground(3001, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
    }

    private fun localTransport(capabilities: NetworkCapabilities): Boolean = capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) || capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)
    @Synchronized private fun startServer() {
        if (server != null) return
        val current = connectivity.activeNetwork ?: error("Local network unavailable")
        require(connectivity.getNetworkCapabilities(current)?.let(::localTransport) == true) { "Wi-Fi or Ethernet required" }
        val host = connectivity.getLinkProperties(current)?.linkAddresses?.map { it.address }?.filterIsInstance<Inet4Address>()
            ?.firstOrNull { McpAccess.privateAddress(it.hostAddress.orEmpty()) }?.hostAddress ?: error("Private LAN address unavailable")
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        // Conscrypt delegates ECDSA TLS signatures as NONEwithECDSA (the TLS transcript
        // is already hashed). Authorize that exact operation on a non-exportable EC key.
        // The former RSA key could not perform Conscrypt's raw RSA cipher upcall.
        val alias = "dev.murmur.android.mcp.tls.ec.v2"
        if (!store.containsAlias(alias)) {
            KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, "AndroidKeyStore").run {
                initialize(KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_SIGN or KeyProperties.PURPOSE_VERIFY)
                    .setAlgorithmParameterSpec(ECGenParameterSpec("secp256r1"))
                    .setDigests(KeyProperties.DIGEST_NONE, KeyProperties.DIGEST_SHA256, KeyProperties.DIGEST_SHA384, KeyProperties.DIGEST_SHA512).setCertificateSubject(X500Principal("CN=Murmur Android"))
                    .setCertificateSerialNumber(BigInteger.valueOf(1)).setCertificateNotBefore(Date(System.currentTimeMillis() - 86_400_000))
                    .setCertificateNotAfter(Date(System.currentTimeMillis() + 10L * 365 * 86_400_000)).build())
                generateKeyPair()
            }
        }
        fingerprint = MessageDigest.getInstance("SHA-256").digest(store.getCertificate(alias).encoded).joinToString("") { "%02x".format(it) }
        val key = store.getKey(alias, null) as PrivateKey
        val chain = store.getCertificateChain(alias).map { it as X509Certificate }.toTypedArray()
        // Fail before advertising the endpoint if the device cannot sign with its Keystore key.
        Signature.getInstance("NONEwithECDSA").apply { initSign(key); update(ByteArray(32)); sign() }
        // Only this alias is eligible. A legacy key or another app secret must never be selected.
        val manager = object : X509ExtendedKeyManager() {
            private fun selected(type: String?) = alias.takeIf { type == "EC" }
            override fun getClientAliases(keyType: String?, issuers: Array<out Principal>?): Array<String>? = null
            override fun chooseClientAlias(keyType: Array<out String>?, issuers: Array<out Principal>?, socket: Socket?): String? = null
            override fun getServerAliases(keyType: String?, issuers: Array<out Principal>?): Array<String>? = selected(keyType)?.let { arrayOf(it) }
            override fun chooseServerAlias(keyType: String?, issuers: Array<out Principal>?, socket: Socket?): String? = selected(keyType)
            override fun chooseEngineServerAlias(keyType: String?, issuers: Array<out Principal>?, engine: SSLEngine?): String? = selected(keyType)
            override fun getCertificateChain(requested: String?): Array<X509Certificate>? = chain.takeIf { requested == alias }
            override fun getPrivateKey(requested: String?): PrivateKey? = key.takeIf { requested == alias }
        }
        val ssl = SSLContext.getInstance("TLS").apply { init(arrayOf(manager), null, null) }
        val created = Server(host)
        created.makeSecure(ssl.serverSocketFactory, arrayOf("TLSv1.2", "TLSv1.3"))
        created.start(NanoHTTPD.SOCKET_READ_TIMEOUT, false)
        server = created; network = current
        address = "https://$host:${created.listeningPort}"
        connectivity.registerDefaultNetworkCallback(networkCallback)
        registerService(created.listeningPort)
        runtime.config.update(JSONObject().put("mcpEnabled", true))
        runtime.log.event("mcp.started")
    }

    private fun registerService(port: Int) {
        val nsd = getSystemService(NsdManager::class.java)
        val listener = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(info: NsdServiceInfo) = Unit
            override fun onRegistrationFailed(info: NsdServiceInfo, error: Int) = Unit
            override fun onServiceUnregistered(info: NsdServiceInfo) = Unit
            override fun onUnregistrationFailed(info: NsdServiceInfo, error: Int) = Unit
        }
        runCatching { nsd.registerService(NsdServiceInfo().apply { serviceName = "Murmur Android"; serviceType = "_murmur-mcp._tcp."; setPort(port); setAttribute("pin", fingerprint) }, NsdManager.PROTOCOL_DNS_SD, listener) }
        serviceRegistration = listener
    }

    private fun openPairing() {
        val (secret, _) = authority.openPairing()
        val endpoint = Uri.parse(address)
        pairingUri = Uri.Builder().scheme("murmur-mcp").authority("pair").appendQueryParameter("v", "1")
            .appendQueryParameter("host", endpoint.host).appendQueryParameter("port", endpoint.port.toString())
            .appendQueryParameter("pin", fingerprint).appendQueryParameter("secret", secret).build().toString()
        serviceScope.launch { delay(5 * 60_000); publish() }
    }

    private fun publish() {
        val expires = authority.expiresAt()
        val value = JSONObject().put("running", server != null).put("address", address).put("fingerprint", fingerprint)
            .put("pairingUri", if (expires > 0) pairingUri else JSONObject.NULL).put("expiresAt", expires)
            .put("pending", authority.pending()).put("clients", authority.publicClients())
        currentStatus = value
        runtime.updateState { it.copy(settings = runtime.config.read().put("mcpStatus", JSONObject(value.toString()))) }
    }

    @Synchronized private fun shutdown() {
        server?.stop(); server = null
        if (::authority.isInitialized) authority.closePairing()
        pairingUri = null
        runCatching { connectivity.unregisterNetworkCallback(networkCallback) }
        serviceRegistration?.let { registration -> runCatching { getSystemService(NsdManager::class.java).unregisterService(registration) } }
        serviceRegistration = null
        uploads.values.forEach { it.file.delete() }; uploads.clear()
        if (::runtime.isInitialized) { runtime.config.update(JSONObject().put("mcpEnabled", false)); publish(); runtime.log.event("mcp.stopped") }
        stopForeground(STOP_FOREGROUND_REMOVE); stopSelf()
    }
    override fun onDestroy() { shutdown(); serviceScope.cancel(); super.onDestroy() }
    override fun onBind(intent: Intent?): IBinder? = null

    private inner class Server(val lanHostname: String) : NanoHTTPD(lanHostname, 0) {
        override fun serve(session: IHTTPSession): Response {
            try {
                val origin = session.headers["origin"]
                if (!McpAccess.validOrigin(origin, address)) return response(Response.Status.FORBIDDEN, JSONObject().put("error", "Origin refused"))
                if (session.headers["host"] != "$lanHostname:$listeningPort") return response(Response.Status.FORBIDDEN, JSONObject().put("error", "Host refused"))
                if (session.method != Method.POST) return newFixedLengthResponse(Response.Status.METHOD_NOT_ALLOWED, "text/plain", "POST required")
                if (session.uri.startsWith("/pair")) return pairing(session)
                val raw = session.headers["authorization"]?.takeIf { it.startsWith("Bearer ") }?.removePrefix("Bearer ").orEmpty()
                val client = authority.authenticate(raw) ?: return response(Response.Status.UNAUTHORIZED, JSONObject().put("error", "Unauthorized"))
                return when (session.uri) {
                    "/mcp" -> {
                        if (session.headers["mcp-protocol-version"] != null && session.headers["mcp-protocol-version"] !in PROTOCOLS) return response(Response.Status.BAD_REQUEST, JSONObject().put("error", "Unsupported MCP version"))
                        if (!session.headers["content-type"].orEmpty().startsWith("application/json")) return response(Response.Status.BAD_REQUEST, JSONObject().put("error", "JSON required"))
                        val request = body(session, 2 * 1024 * 1024)
                        val id = request.opt("id")
                        val result = runBlocking(Dispatchers.IO) { handle(request, client) }
                        if (id == null || id == JSONObject.NULL) newFixedLengthResponse(Response.Status.ACCEPTED, "application/json", "")
                        else response(Response.Status.OK, result ?: JSONObject())
                    }
                    "/uploads" -> upload(session, client)
                    else -> response(Response.Status.NOT_FOUND, JSONObject().put("error", "Not found"))
                }
            } catch (error: Exception) {
                runtime.log.event("mcp.request.failed", error)
                return response(Response.Status.BAD_REQUEST, JSONObject().put("error", "Invalid request"))
            }
        }

        private fun pairing(session: IHTTPSession): Response {
            if (session.uri == "/pair") {
                val remote = session.remoteIpAddress
                val now = System.currentTimeMillis()
                val last = attempts[remote]
                if (last != null && now - last.first < 60_000 && last.second >= 10) return response(Response.Status.FORBIDDEN, JSONObject().put("error", "Try later"))
                if (attempts.size > 100) attempts.clear()
                attempts[remote] = if (last == null || now - last.first >= 60_000) now to 1 else last.first to last.second + 1
            }
            val p = body(session, 4096)
            return try {
                val result = when (session.uri) {
                    "/pair" -> JSONObject().put("requestId", authority.request(p.getString("secret"), p.optString("name", "Claude"))).put("status", "pending")
                    "/pair/status" -> authority.result(p.getString("requestId"), p.getString("secret"))
                    "/pair/ack" -> { authority.acknowledge(p.getString("requestId"), p.getString("secret")); JSONObject().put("ok", true) }
                    else -> return response(Response.Status.NOT_FOUND, JSONObject())
                }
                publish()
                response(Response.Status.OK, result)
            } catch (_: Exception) { response(Response.Status.UNAUTHORIZED, JSONObject().put("error", "Pairing unavailable")) }
        }

        private fun upload(session: IHTTPSession, client: String): Response {
            uploads.entries.removeAll { (_, upload) -> if (System.currentTimeMillis() - upload.created > 3_600_000) { upload.file.delete(); true } else false }
            synchronized(uploads) {
                require(uploads.size + activeUploads < 4) { "Upload queue full" }
                activeUploads++
            }
            try {
            val size = session.headers["content-length"]?.toLongOrNull() ?: error("Content-Length required")
            require(size in 1..512L * 1024 * 1024 && runtime.dataDir.usableSpace > size + 64L * 1024 * 1024)
            val name = session.headers["x-file-name"].orEmpty()
            val ext = name.substringAfterLast('.', "bin").lowercase().takeIf { it in setOf("mp3","mp4","m4a","mov","mkv","webm","wav","flac","ogg","opus","aif","aiff") } ?: "bin"
            val id = UUID.randomUUID().toString()
            val file = File(filesDir, "mcp-uploads/$id.$ext")
            try {
                file.outputStream().use { output ->
                    var remaining = size
                    val buffer = ByteArray(256 * 1024)
                    while (remaining > 0) {
                        val count = session.inputStream.read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt())
                        require(count > 0)
                        output.write(buffer, 0, count); remaining -= count
                    }
                }
                uploads[id] = Upload(file, client, System.currentTimeMillis())
                return response(Response.Status.OK, JSONObject().put("upload_id", id))
            } catch (error: Exception) { file.delete(); throw error }
            } finally { synchronized(uploads) { activeUploads-- } }
        }
    }

    private suspend fun handle(request: JSONObject, client: String): JSONObject? {
        val id = request.opt("id") ?: return null
        val method = request.optString("method")
        val params = request.optJSONObject("params") ?: JSONObject()
        val reply = JSONObject().put("jsonrpc", "2.0").put("id", id)
        if (request.optString("jsonrpc") != "2.0") return reply.put("error", JSONObject().put("code", -32600).put("message", "Invalid request"))
        val result = when (method) {
            "initialize" -> JSONObject().put("protocolVersion", params.optString("protocolVersion").takeIf { it in PROTOCOLS } ?: PROTOCOLS.first())
                .put("capabilities", JSONObject().put("tools", JSONObject())).put("serverInfo", JSONObject().put("name", "murmur-android").put("version", BuildConfig.VERSION_NAME))
                .put("instructions", "Meetings are stored and processed on the paired Android phone. Audio uploaded by transcribe_file travels over the local encrypted connection only. Poll get_transcription for long jobs.")
            "ping" -> JSONObject()
            "tools/list" -> JSONObject().put("tools", tools())
            "tools/call" -> {
                try {
                    val text = callTool(params.getString("name"), params.optJSONObject("arguments") ?: JSONObject(), client)
                    JSONObject().put("content", JSONArray().put(JSONObject().put("type", "text").put("text", text)))
                } catch (error: Exception) {
                    runtime.log.event("mcp.tool.failed", error)
                    JSONObject().put("isError", true).put("content", JSONArray().put(JSONObject().put("type", "text").put("text", error.message ?: "Tool failed")))
                }
            }
            else -> return reply.put("error", JSONObject().put("code", -32601).put("message", "Unknown method"))
        }
        return reply.put("result", result)
    }

    private suspend fun callTool(name: String, args: JSONObject, client: String): String = when (name) {
        "list_meetings" -> {
            val tag = args.optString("tag")
            val meetings = runtime.native("meetings").array("meetings").objects().filter { tag.isBlank() || it.array("tags").strings().any { t -> t.equals(tag, true) } }.take(args.optInt("limit", 20).coerceIn(1, 200))
            JSONArray(meetings).toString()
        }
        "search_meetings" -> runtime.native("search", JSONObject().put("query", args.getString("query"))).getJSONArray("hits").toString()
        "get_meeting" -> ExportManager(this).markdown(runtime.get(args.getString("id")) ?: error("Meeting missing"), "all", runtime.config.read().optString("uiLanguage", "ca"))
        "transcribe_file" -> {
            val uploadId = args.optString("upload_id").ifBlank { args.optString("path").removePrefix("upload:") }
            require(uploadId.matches(Regex("[a-f0-9-]{36}"))) { "Upload a local file with the stdio bridge first" }
            val uploaded = uploads[uploadId]?.takeIf { it.owner == client } ?: error("Upload unavailable")
            val language = args.optString("language").takeIf(String::isNotBlank)
            require(language == null || language in listOf("ca", "es", "ca-es", "en"))
            val maxSpeakers = if (args.has("max_speakers")) args.getInt("max_speakers").also { require(it in 1..32) } else null
            val source = File(runtime.dataDir, "mcp-input/${uploaded.file.name}").apply { parentFile?.mkdirs() }
            check(uploaded.file.renameTo(source))
            try {
                val job = runtime.processor.importAudio(source.absolutePath, "", language, maxSpeakers)
                uploads.remove(uploadId); jobs[job] = client; persistJobs()
                JSONObject().put("job_id", job).put("status", "queued").toString()
            } catch (error: Exception) { uploads.remove(uploadId); source.delete(); throw error }
        }
        "get_transcription" -> {
            val job = args.getString("job_id")
            require(jobs[job] == client) { "Job unavailable" }
            val result = runtime.processor.status(job)
            if (result.optString("status") == "done") {
                val meetingId = result.optString("meetingId")
                val meeting = runtime.get(meetingId)
                result.put("meeting_id", meetingId)
                if (meeting != null) result.put("transcript", ExportManager(this).markdown(meeting, "all", runtime.config.read().optString("uiLanguage", "ca")))
            }
            result.toString()
        }
        else -> error("Unknown tool")
    }

    @Synchronized private fun persistJobs() {
        val valid = jobs.entries.toList().takeLast(200)
        val state = JSONObject().apply { valid.forEach { put(it.key, it.value) } }
        val temporary = File(jobsFile.path + ".tmp")
        temporary.writeText(state.toString()); check(temporary.renameTo(jobsFile))
    }

    private fun tools(): JSONArray = JSONArray().apply {
        fun add(name: String, description: String, properties: JSONObject, required: List<String> = emptyList(), readOnly: Boolean = true) {
            put(JSONObject().put("name", name).put("description", description).put("inputSchema", JSONObject().put("type", "object").put("properties", properties).apply { if (required.isNotEmpty()) put("required", JSONArray(required)) })
                .put("annotations", JSONObject().put("readOnlyHint", readOnly).put("openWorldHint", false)))
        }
        fun string(description: String) = JSONObject().put("type", "string").put("description", description)
        add("list_meetings", "List meetings stored on the Android phone, newest first.", JSONObject().put("limit", JSONObject().put("type", "integer")).put("tag", string("Optional tag filter.")))
        add("search_meetings", "Search titles, notes and transcripts on the Android phone.", JSONObject().put("query", string("Search words.")), listOf("query"))
        add("get_meeting", "Read a meeting summary, decisions, tasks, notes and transcript.", JSONObject().put("id", string("Meeting id.")), listOf("id"))
        add("transcribe_file", "Transcribe an audio/video file from this computer on the paired Android phone. The local bridge uploads it over TLS; poll get_transcription with the returned job id.", JSONObject().put("path", string("Absolute local computer file path."))
            .put("language", JSONObject().put("type", "string").put("enum", JSONArray(listOf("ca","es","ca-es","en")))).put("max_speakers", JSONObject().put("type", "integer")), listOf("path"), false)
        add("get_transcription", "Read a transcription job status and its completed transcript.", JSONObject().put("job_id", string("Job id returned by transcribe_file.")), listOf("job_id"))
    }

    private fun body(session: NanoHTTPD.IHTTPSession, maximum: Int): JSONObject {
        val size = session.headers["content-length"]?.toIntOrNull() ?: error("Content-Length required")
        require(size in 1..maximum)
        val bytes = ByteArray(size)
        var at = 0
        while (at < size) { val n = session.inputStream.read(bytes, at, size - at); require(n > 0); at += n }
        return JSONObject(bytes.toString(Charsets.UTF_8))
    }
    private fun response(status: NanoHTTPD.Response.Status, value: JSONObject): NanoHTTPD.Response = NanoHTTPD.newFixedLengthResponse(status, "application/json", value.toString()).apply {
        addHeader("Cache-Control", "no-store"); addHeader("X-Content-Type-Options", "nosniff")
        addHeader("Connection", "close")
    }

    companion object {
        const val START = "dev.murmur.android.mcp.START"
        const val STOP = "dev.murmur.android.mcp.STOP"
        const val PAIR = "dev.murmur.android.mcp.PAIR"
        const val APPROVE = "dev.murmur.android.mcp.APPROVE"
        const val REJECT = "dev.murmur.android.mcp.REJECT"
        const val REVOKE = "dev.murmur.android.mcp.REVOKE"
        private val PROTOCOLS = listOf("2025-11-25", "2025-06-18")
        @Volatile private var currentStatus = JSONObject().put("running", false)
        fun status(): JSONObject = JSONObject(currentStatus.toString())
    }
}
