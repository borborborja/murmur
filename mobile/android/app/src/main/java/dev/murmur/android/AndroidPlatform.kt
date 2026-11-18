package dev.murmur.android

import android.Manifest
import android.app.*
import android.content.*
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaPlayer
import android.media.MediaRecorder
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.DocumentsContract
import android.provider.Settings
import android.webkit.WebView
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.browser.customtabs.CustomTabsIntent
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.lifecycle.lifecycleScope
import dev.murmur.android.integrations.NotionManager
import dev.murmur.android.integrations.ShareManager
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

/** Activity-result and permission boundary. All actual capture/inference lives outside the UI. */
class AndroidPlatform(private val activity: ComponentActivity, private val viewModel: MurmurViewModel) {
    private val runtime = viewModel.runtime
    private var pendingRecord = JSONObject()
    private var pendingPermission: (() -> Unit)? = null
    private var projectionForScreen = false
    private var folderKind = "markdown"
    private var pendingExport: File? = null
    private var pendingPhoto: File? = null
    private var pendingPhotoMeeting: String? = null
    private var player: MediaPlayer? = null
    private var playerId: String? = null
    private var playbackPoll: Job? = null
    private var voiceJob: Job? = null
    private var pairingConfirmation: AlertDialog? = null
    private val notion = NotionManager(activity)
    private val permissions = activity.registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { grants ->
        val callback = pendingPermission; pendingPermission = null
        if (grants.filterKeys { it != Manifest.permission.POST_NOTIFICATIONS }.values.all { it }) callback?.invoke()
        else runtime.updateState { it.copy(message = activity.getString(R.string.operation_failed)) }
    }
    private val projectionResult = activity.registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK && result.data != null) {
            val intent = Intent(activity, RecordingService::class.java)
                .putExtra("projectionResult", result.resultCode).putExtra("projectionData", result.data)
            if (projectionForScreen) intent.setAction(RecordingService.ENABLE_SCREEN)
            else intent.putExtra("options", pendingRecord.toString())
            ContextCompat.startForegroundService(activity, intent)
        }
    }
    private val files = activity.registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        uris.forEach { importUri(it) }
    }
    private val saveFile = activity.registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val source = pendingExport; pendingExport = null
        val uri = result.data?.data
        if (result.resultCode == Activity.RESULT_OK && source != null && uri != null) io("export.file") {
            activity.contentResolver.openOutputStream(uri, "wt")!!.use { output -> source.inputStream().use { it.copyTo(output) } }
        }
    }
    private val tree = activity.registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) {
            activity.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
            val patch = if (folderKind == "obsidian") {
                var name = ""
                val document = DocumentsContract.buildDocumentUriUsingTree(uri, DocumentsContract.getTreeDocumentId(uri))
                activity.contentResolver.query(document, arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME), null, null, null)?.use { if (it.moveToFirst()) name = it.getString(0) }
                json("obsidianVault" to uri.toString(), "obsidianName" to name)
            } else json("markdownDir" to uri.toString())
            runtime.dispatch("settings.update", patch)
        }
    }
    private val cameraPermission = activity.registerForActivityResult(ActivityResultContracts.RequestPermission()) { allowed -> if (allowed) launchPhoto() }
    private val photo = activity.registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val file = pendingPhoto; val id = pendingPhotoMeeting
        pendingPhoto = null; pendingPhotoMeeting = null
        if (result.resultCode == Activity.RESULT_OK && file?.isFile == true && id != null) io("capture.photo") {
            runtime.edit(id) { m -> m.put("screens", m.array("screens").put(json("t" to runtime.state.value.seconds, "path" to file.path))) }
        } else file?.delete()
    }
    private val barcode = activity.registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        result.data?.getStringExtra("pairingUri")?.let { runtime.dispatch("pair.connect", json("uri" to it)) }
    }

    fun handle(action: String, p: JSONObject): Boolean {
        when (action) {
            "record.start", "record.resume" -> {
                pendingRecord = runtime.config.read().apply { p.keys().forEach { put(it, p.get(it)) } }
                if (action == "record.resume") pendingRecord.put("resumeId", p.getString("id"))
                requirePermissions(listOfNotNull(if (pendingRecord.optBoolean("mic", true)) Manifest.permission.RECORD_AUDIO else null,
                    if (Build.VERSION.SDK_INT >= 33) Manifest.permission.POST_NOTIFICATIONS else null)) { startRecording() }
            }
            "record.open" -> runtime.activeId?.let { runtime.dispatch("meeting.open", json("id" to it)) }
            "import.file" -> files.launch(arrayOf("audio/*", "video/*"))
            "folder.choose" -> { folderKind = p.optString("kind", "markdown"); tree.launch(null) }
            "export" -> export(p)
            "export.auto" -> io("export.automatic") { runtime.get(p.getString("meetingId"))?.let { ExportCoordinator(runtime).automatic(it) } }
            "clipboard", "clipboard.copy" -> activity.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("Murmur", p.getString("text")))
            "calendar.connect" -> requirePermissions(listOf(Manifest.permission.READ_CALENDAR)) {
                io("calendar.connect") { runtime.config.update(json("calendarEnabled" to true)); runtime.refresh(); refreshCalendar() }
            }
            "calendar.refresh" -> refreshCalendar()
            "calendar.record" -> {
                val event = p.getJSONObject("event")
                pendingRecord = runtime.config.read().put("title", event.optString("title")).put("guests", event.array("guests"))
                AlertDialog.Builder(activity).setTitle(event.optString("title")).setMessage(activity.getString(R.string.consent_recording))
                    .setPositiveButton(android.R.string.ok) { _, _ -> requirePermissions(listOf(Manifest.permission.RECORD_AUDIO)) { startRecording() } }
                    .setNegativeButton(android.R.string.cancel, null).show()
            }
            "screen.capture" -> if (runtime.activeId != null) {
                if (ProjectionCapture.active) ProjectionCapture.capture()
                else { projectionForScreen = true; projectionResult.launch(activity.getSystemService(MediaProjectionManager::class.java).createScreenCaptureIntent()) }
            }
            "photo.take" -> {
                if (ContextCompat.checkSelfPermission(activity, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) launchPhoto()
                else cameraPermission.launch(Manifest.permission.CAMERA)
            }
            "screen.open" -> openPrivateFile(p.getString("path"), "image/jpeg")
            "playback.play", "playback.seek", "playback.speed" -> playback(action, p)
            "overlay.permission" -> activity.startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${activity.packageName}")))
            "calendar.exact" -> if (Build.VERSION.SDK_INT >= 31) activity.startActivity(Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.parse("package:${activity.packageName}")))
            "pair.scan" -> {
                barcode.launch(Intent(activity, CameraCaptureActivity::class.java).putExtra("scanQr", true))
            }
            "notion.connect" -> io("notion.connect") {
                notion.connect { url -> CustomTabsIntent.Builder().build().launchUrl(activity, Uri.parse(url)) }
                refreshIntegrationStatus()
            }
            "notion.cancel" -> notion.cancel()
            "notion.disconnect" -> { notion.disconnect(); io("notion.status") { refreshIntegrationStatus() } }
            "notion.search" -> io("notion.search") {
                val pages = notion.search(p.optString("query"))
                runtime.updateState { it.copy(settings = it.settings.copyJson().put("notionPages", JSONArray(pages))) }
            }
            "notion.export" -> export(p.copyJson().put("format", "notion"))
            "share.disconnect" -> { ShareManager(activity).disconnect(); io("share.status") { refreshIntegrationStatus() } }
            "share.token_url" -> activity.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(ShareManager(activity).tokenUrl())))
            "mcp.start", "mcp.stop", "mcp.pair", "mcp.approve", "mcp.reject", "mcp.revoke" -> {
                val command = when (action) {
                    "mcp.start" -> McpService.START; "mcp.stop" -> McpService.STOP; "mcp.pair" -> McpService.PAIR
                    "mcp.approve" -> McpService.APPROVE; "mcp.reject" -> McpService.REJECT; else -> McpService.REVOKE
                }
                val intent = Intent(activity, McpService::class.java).setAction(command)
                    .putExtra("requestId", p.optString("requestId")).putExtra("clientId", p.optString("clientId"))
                ContextCompat.startForegroundService(activity, intent)
            }
            "voice.record" -> requirePermissions(listOf(Manifest.permission.RECORD_AUDIO)) { recordVoice(p.getString("sentence")) }
            "voice.cancel" -> voiceJob?.cancel()
            "updates.check" -> io("updates.check") {
                val updater = ApkUpdater(runtime)
                val available = updater.check()
                withContext(Dispatchers.Main) {
                    AlertDialog.Builder(activity).setTitle(activity.getString(R.string.app_name))
                        .setMessage(available?.optString("versionName") ?: BuildConfig.VERSION_NAME)
                        .apply { if (available != null) setPositiveButton(android.R.string.ok) { _, _ -> io("updates.install") { val apk = updater.download(available); withContext(Dispatchers.Main) { updater.install(apk) } } } }
                        .setNegativeButton(android.R.string.cancel, null).show()
                }
            }
            "log.export" -> {
                val log = File(activity.filesDir, "logs/murmur.log")
                if (log.exists()) offerSave(log, "text/plain")
            }
            "licenses.show" -> activity.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/quicodomingo/murmur#models")))
            else -> return false
        }
        return true
    }

    private fun requirePermissions(requested: List<String>, action: () -> Unit) {
        val missing = requested.filter { ContextCompat.checkSelfPermission(activity, it) != PackageManager.PERMISSION_GRANTED }
        if (missing.isEmpty()) action() else { pendingPermission = action; permissions.launch(missing.toTypedArray()) }
    }
    private fun startRecording() {
        if (!pendingRecord.optBoolean("mic", true) && !pendingRecord.optBoolean("system", false)) {
            runtime.updateState { it.copy(message = activity.getString(R.string.operation_failed)) }; return
        }
        val installed = runtime.state.value.models.firstOrNull { it.optString("id") == "transcription" }?.optBoolean("installed") == true
        if (!installed) { runtime.updateState { it.copy(message = activity.getString(R.string.models_missing)) }; return }
        if (pendingRecord.optString("language") == "en" && runtime.state.value.models.none { it.optString("id") == "english" && it.optBoolean("installed") }) {
            runtime.updateState { it.copy(message = activity.getString(R.string.models_missing)) }; return
        }
        if (pendingRecord.optBoolean("system")) {
            projectionForScreen = false
            projectionResult.launch(activity.getSystemService(MediaProjectionManager::class.java).createScreenCaptureIntent())
        } else ContextCompat.startForegroundService(activity, Intent(activity, RecordingService::class.java).putExtra("options", pendingRecord.toString()))
    }
    private fun importUri(uri: Uri) = io("import.copy") {
        require(uri.scheme in listOf("content", "file"))
        var displayName = ""
        activity.contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)?.use { if (it.moveToFirst()) displayName = it.getString(0).orEmpty() }
        val file = File(runtime.dataDir, "imports/${UUID.randomUUID()}-${ExportCoordinator.safeName(displayName)}").apply { parentFile!!.mkdirs() }
        activity.contentResolver.openInputStream(uri)!!.use { input -> file.outputStream().use { input.copyTo(it) } }
        runtime.processor.importAudio(file.path, displayName.substringBeforeLast('.'), null)
    }
    private fun launchPhoto() {
        val id = runtime.activeId ?: runtime.state.value.meeting?.optString("id") ?: return
        val file = File(runtime.dataDir, "screens/$id/${System.currentTimeMillis()}.jpg").apply { parentFile!!.mkdirs() }
        pendingPhoto = file; pendingPhotoMeeting = id
        val uri = FileProvider.getUriForFile(activity, "${activity.packageName}.files", file)
        photo.launch(Intent().setClassName(activity, "dev.murmur.android.CameraCaptureActivity").putExtra("outputUri", uri.toString()))
    }
    private fun refreshCalendar() = io("calendar.refresh") {
        val events = CalendarAccess.events(activity)
        val calendars = CalendarAccess.calendars(activity)
        runtime.updateState { it.copy(calendarEvents = events, settings = runtime.config.read().put("calendars", JSONArray(calendars))) }
    }
    private suspend fun refreshIntegrationStatus() {
        runtime.updateState { it.copy(settings = it.settings.copyJson().put("notionStatus", notion.status())
            .put("shareStatus", ShareManager(activity).status() ?: JSONObject.NULL)) }
    }
    private fun export(options: JSONObject) = io("export.prepare") {
        val id = options.optString("meetingId").ifBlank { runtime.state.value.meeting?.optString("id").orEmpty() }
        val meeting = runtime.get(id) ?: return@io
        val format = options.optString("format", "md")
        val content = options.optString("content", "all")
        val coordinator = ExportCoordinator(runtime)
        when (format) {
            "pdf" -> withContext(Dispatchers.Main) {
                val web = WebView(activity).apply { settings.javaScriptEnabled = false; settings.blockNetworkLoads = true }
                web.webViewClient = object : android.webkit.WebViewClient() {
                    override fun onPageFinished(view: WebView, url: String) {
                        activity.getSystemService(android.print.PrintManager::class.java).print(ExportCoordinator.safeName(meeting.optString("title")), web.createPrintDocumentAdapter("Murmur"), android.print.PrintAttributes.Builder().build())
                    }
                }
                web.loadDataWithBaseURL(null, coordinator.html(meeting, content), "text/html", "UTF-8", null)
            }
            "obsidian" -> {
                val url = coordinator.obsidian(meeting)
                withContext(Dispatchers.Main) { runCatching { activity.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) } }
            }
            "notion" -> {
                val settings = runtime.config.read()
                val url = notion.export(meeting, settings.optJSONObject("notionPage")?.optString("id"), settings.optString("uiLanguage", "ca"))
                withContext(Dispatchers.Main) { activity.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
            }
            else -> {
                val file = coordinator.create(meeting, format, content)
                withContext(Dispatchers.Main) { offerSave(file, when(format) { "md" -> "text/markdown"; "docx" -> "application/vnd.openxmlformats-officedocument.wordprocessingml.document"; "srt" -> "application/x-subrip"; "vtt" -> "text/vtt"; "audio" -> "audio/ogg"; else -> "application/zip" }) }
            }
        }
    }
    private fun offerSave(file: File, mime: String) {
        pendingExport = file
        saveFile.launch(Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType(mime).putExtra(Intent.EXTRA_TITLE, file.name))
    }
    private fun openPrivateFile(path: String, mime: String) {
        val file = (if (path.startsWith('/')) File(path) else File(runtime.dataDir, path)).canonicalFile
        require(file.path.startsWith(activity.filesDir.canonicalPath + File.separator))
        val uri = FileProvider.getUriForFile(activity, "${activity.packageName}.files", file)
        activity.startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(uri, mime).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
    }
    private fun playback(action: String, p: JSONObject) {
        try {
            val meeting = runtime.state.value.meeting ?: return
            val id = meeting.getString("id")
            if (playerId != id) {
                player?.release(); player = MediaPlayer().apply { setDataSource(meeting.getString("audioPath")); prepare() }; playerId = id
                player!!.setOnCompletionListener { runtime.updateState { it.copy(playbackPlaying = false) } }
            }
            when (action) {
                "playback.play" -> if (player!!.isPlaying) player!!.pause() else player!!.start()
                "playback.seek" -> player!!.seekTo((p.getDouble("time")*1000).toInt())
                "playback.speed" -> { player!!.playbackParams = player!!.playbackParams.setSpeed(p.getDouble("value").toFloat()); runtime.updateState { it.copy(playbackSpeed = p.getDouble("value").toFloat()) } }
            }
            playbackPoll?.cancel()
            playbackPoll = activity.lifecycleScope.launch {
                while (isActive) { runtime.updateState { it.copy(playbackPosition = (player?.currentPosition ?: 0) / 1000.0, playbackPlaying = player?.isPlaying == true) }; delay(250) }
            }
        } catch (error: Throwable) { runtime.failure("playback.failed", error) }
    }
    @Suppress("MissingPermission") private fun recordVoice(sentence: String) {
        if (voiceJob?.isActive == true) return
        voiceJob = activity.lifecycleScope.launch(Dispatchers.IO) {
            var record: AudioRecord? = null
            try {
                check(runtime.activeId == null && sentence.isNotBlank())
                runtime.processor.pause()
                runtime.updateState { it.copy(busy = "voice", progress = 0f, settings = it.settings.copyJson().put("voiceSentence", sentence)) }
                record = AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION, 16000, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, 64000)
                val samples = JSONArray(); val buffer = ShortArray(3200)
                val totalWords = sentence.trim().split(Regex("\\s+")).size
                var said = 0
                record.startRecording()
                for (step in 0 until 100) {
                    currentCoroutineContext().ensureActive()
                    val count = record.read(buffer, 0, buffer.size, AudioRecord.READ_BLOCKING)
                    require(count > 0)
                    for (i in 0 until count) samples.put(buffer[i] / 32768.0)
                    if (step % 3 == 2) {
                        said = runtime.native("voice_progress", json("samples" to samples, "sentence" to sentence)).optInt("said")
                        runtime.updateState { it.copy(progress = (said.toFloat()/totalWords).coerceIn(0f, 1f)) }
                    }
                    if (said >= totalWords && step > 20) break
                }
                runtime.native("voice_enroll", json("samples" to samples))
                runtime.refresh()
            } catch (error: Throwable) { runtime.failure("voice.enroll", error) }
            finally {
                runCatching { record?.stop() }; record?.release()
                withContext(NonCancellable) { runCatching { runtime.native("unload") } }
                runtime.processor.resume()
                runtime.updateState { it.copy(busy = null, progress = null, settings = it.settings.copyJson().apply { remove("voiceSentence") }) }
            }
        }
    }
    fun onIntent(intent: Intent?) {
        if (intent == null) return
        // MainActivity is exported. Only a user click inside this process can authorize
        // an immediate microphone or capture action; caller-controlled extras cannot.
        val internalAction = InternalPlatformActions.consume(intent)
        intent.removeExtra("platformAction")
        intent.removeExtra("startRecording")
        internalAction?.let { handle(it, JSONObject()) }
        intent.getStringExtra("calendarEvent")?.let { event ->
            intent.removeExtra("calendarEvent")
            runCatching { JSONObject(event) }.getOrNull()?.let {
                // calendar.record already asks for explicit recording consent.
                handle("calendar.record", json("event" to it))
            }
        }
        val data = intent.data
        if (data?.scheme == "murmur" && data.host == "pair") {
            intent.data = null
            confirmExternalPairing(data.toString())
            return
        }
        when (intent.action) {
            Intent.ACTION_VIEW -> if (data != null) { importUri(data); intent.data = null }
            Intent.ACTION_SEND -> {
                val uri = if (Build.VERSION.SDK_INT >= 33) intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java) else @Suppress("DEPRECATION") intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)
                uri?.let(::importUri); intent.removeExtra(Intent.EXTRA_STREAM)
            }
            Intent.ACTION_SEND_MULTIPLE -> {
                @Suppress("DEPRECATION") val uris = intent.getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM)
                uris?.forEach(::importUri); intent.removeExtra(Intent.EXTRA_STREAM)
            }
        }
    }
    private fun confirmExternalPairing(uri: String) {
        if (uri.length > 4096 || activity.isFinishing || activity.isDestroyed) return
        pairingConfirmation?.dismiss()
        val dialog = AlertDialog.Builder(activity)
            .setTitle(R.string.pair)
            .setMessage(R.string.pair_hint)
            .setPositiveButton(R.string.pair) { _, _ -> runtime.dispatch("pair.connect", json("uri" to uri)) }
            .setNegativeButton(android.R.string.cancel, null)
            .create()
        pairingConfirmation = dialog
        dialog.setOnDismissListener { if (pairingConfirmation === dialog) pairingConfirmation = null }
        dialog.show()
    }
    fun close() { pairingConfirmation?.dismiss(); pairingConfirmation = null; voiceJob?.cancel(); playbackPoll?.cancel(); player?.release(); player = null; notion.cancel() }
    private fun io(operation: String, action: suspend () -> Unit) { activity.lifecycleScope.launch(Dispatchers.IO) { try { action() } catch (error: Throwable) { runtime.failure(operation, error) } } }
}

/** Non-persistent, action-bound grants for actual quick-tile and overlay clicks. */
internal object InternalPlatformActions {
    private const val ACTION = "dev.murmur.android.internal.action"
    private const val CAPABILITY = "dev.murmur.android.internal.capability"
    private const val TTL_MS = 60_000L
    private data class Grant(val action: String, val expires: Long)
    private val grants = linkedMapOf<String, Grant>()

    @Synchronized fun intent(context: Context, action: String): Intent {
        require(action == "record.start" || action == "screen.capture")
        val now = android.os.SystemClock.elapsedRealtime()
        grants.entries.removeAll { it.value.expires <= now }
        while (grants.size >= 16) grants.remove(grants.keys.first())
        val token = UUID.randomUUID().toString()
        grants[token] = Grant(action, now + TTL_MS)
        return Intent(context, MainActivity::class.java).putExtra(ACTION, action).putExtra(CAPABILITY, token)
    }

    @Synchronized fun consume(intent: Intent): String? {
        val action = intent.getStringExtra(ACTION)
        val token = intent.getStringExtra(CAPABILITY)
        intent.removeExtra(ACTION)
        intent.removeExtra(CAPABILITY)
        val grant = token?.let { grants.remove(it) } ?: return null
        return grant.action.takeIf { it == action && grant.expires > android.os.SystemClock.elapsedRealtime() }
    }
}
