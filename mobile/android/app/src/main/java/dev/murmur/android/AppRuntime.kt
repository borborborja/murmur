package dev.murmur.android

import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import dev.murmur.android.intelligence.IntelligenceManager
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/** Process-owned state: the activity can disappear without owning the microphone or jobs. */
class AppRuntime private constructor(context: Context) {
    val context: Context = context.applicationContext
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    val config = AppSettings(context)
    val log = TechnicalLog(context)
    val dataDir = File(context.filesDir, "data").apply { mkdirs() }
    private val nativeEngine by lazy { NativeEngine(this.context) }
    private val nativeLock = Mutex()
    private val editLock = Mutex()
    private val recordingLock = Mutex()
    private val _state = MutableStateFlow(UiState(settings = config.read()))
    val state: StateFlow<UiState> = _state.asStateFlow()
    val platformRequests = MutableSharedFlow<PlatformRequest>(extraBufferCapacity = 32)
    val models = ModelManager(context, ::native)
    val intelligence = IntelligenceManager(context)
    val analytics = AnonymousAnalytics(context)
    val processor by lazy { MeetingProcessor(this) }
    val sync by lazy { SyncCoordinator(this) }
    private val jobs = ConcurrentHashMap<String, Job>()
    private var afterJob: Job? = null
    @Volatile var activeId: String? = null
        private set
    @Volatile var paused = false
    private var lastCheckpoint = 0L
    private var captureOffset = 0.0

    init {
        log.event("app.open")
        analytics.track("app_opened")
        scope.launch {
            try {
                native("set_language", json("language" to config.read().optString("uiLanguage", "ca")))
                recoverRecording()
                refresh()
                processor.recover()
            } catch (error: Throwable) { failure("app.initialize", error) }
        }
    }

    suspend fun native(action: String, payload: JSONObject = JSONObject()): JSONObject =
        nativeLock.withLock { nativeEngine.call(action, payload) }

    fun updateState(change: (UiState) -> UiState) { _state.update(change) }
    fun clearMessage() = updateState { it.copy(message = null) }
    fun failure(operation: String, error: Throwable) {
        if (error is CancellationException) return
        log.event(operation.replace(Regex("[^a-z0-9_.-]"), "_"), error)
        val message = if (error is dev.murmur.android.intelligence.IntelligenceException)
            intelligence.message(error, dev.murmur.android.intelligence.resolveUiLanguage(config.read().optString("uiLanguage", "ca")))
            else error.message ?: error.javaClass.simpleName
        updateState { it.copy(busy = null, progress = null, message = message) }
    }

    suspend fun refresh(openId: String? = state.value.meeting?.optString("id")) {
        val meetings = native("meetings").array("meetings").objects()
        val modelList = models.catalog()
        val meeting = openId?.let { get(it) }
        val settings = config.read().put("cloudStatus", intelligence.status())
        state.value.settings.optJSONArray("providerModels")?.let { settings.put("providerModels", it) }
        state.value.settings.optString("providerModelsProvider").takeIf { it.isNotBlank() }?.let { settings.put("providerModelsProvider", it) }
        val pairing = sync.status()
        settings.put("mcpStatus", McpService.status()).put("pairingStatus", pairing).put("syncStatus", pairing)
            .put("notionStatus", dev.murmur.android.integrations.NotionManager(context).status())
            .put("shareStatus", dev.murmur.android.integrations.ShareManager(context).status() ?: JSONObject.NULL)
            .put("glossary", native("glossary").array("terms"))
            .put("voiceStatus", native("voice_status").opt("savedAt"))
            .put("pendingImports", processor.pendingImports())
        val microphones = context.getSystemService(android.media.AudioManager::class.java).getDevices(android.media.AudioManager.GET_DEVICES_INPUTS)
            .map { json("id" to it.id.toString(), "name" to it.productName.toString()) }
        settings.put("microphones", JSONArray(microphones))
        updateState { it.copy(meetings = meetings, meeting = meeting, models = modelList, settings = settings) }
    }
    suspend fun get(id: String): JSONObject? = native("get", json("id" to id)).optJSONObject("meeting")?.apply {
        optJSONObject("cover")?.let { cover ->
            val expected = cover.optString("basis")
            if (expected.isNotBlank()) put("coverStale", expected != dev.murmur.android.intelligence.basis(intelligence.document(this, cover.optString("lang", "ca"))))
        }
    }
    suspend fun put(meeting: JSONObject) { native("put", json("meeting" to meeting)) }
    suspend fun edit(id: String, change: (JSONObject) -> Unit): JSONObject? = editLock.withLock {
        val meeting = get(id) ?: return@withLock null
        change(meeting)
        put(meeting)
        if (state.value.meeting?.optString("id") == id) updateState { it.copy(meeting = meeting) }
        meeting
    }

    fun dispatch(action: String, payload: JSONObject = JSONObject()) {
        scope.launch {
            try { execute(action, payload) } catch (error: Throwable) { failure(action, error) }
        }
    }

    private suspend fun execute(action: String, p: JSONObject) {
        val id = p.optString("meetingId").ifBlank { state.value.meeting?.optString("id").orEmpty() }
        when (action) {
            "library.refresh" -> refresh()
            "meeting.open" -> {
                refresh(p.getString("id"))
                if (p.has("time")) platformRequests.emit(PlatformRequest("playback.seek", json("time" to p.getDouble("time"))))
            }
            "meeting.close" -> updateState { it.copy(meeting = null) }
            "meeting.rename" -> { edit(id) { it.put("title", p.getString("title")).put("titleEdited", true) }; refresh(id) }
            "meeting.delete" -> {
                val target = p.optString("id", id)
                check(target != activeId)
                val meeting = get(target)
                if (meeting != null) CloudShare(this).revokeAll(meeting)
                native("delete", json("id" to target)); refresh(null)
            }
            "library.search" -> {
                val hits = native("search", p).array("hits").objects()
                updateState { it.copy(searchResults = hits) }
            }
            "transcript.edit" -> {
                edit(id) { meeting -> meeting.array("utterances").objects().forEach {
                    if (it.optString("id") == p.getString("id")) {
                        val names = MeetingEdits.newNames(it.optString("text"), p.getString("text"))
                        if (names.isNotEmpty()) updateState { state -> state.copy(settings = state.settings.copyJson().put("glossarySuggestion", JSONArray(names))) }
                        it.put("text", p.getString("text")).put("edited", true)
                    }
                } }
            }
            "speaker.rename" -> {
                edit(id) { meeting ->
                    val speaker = p.getInt("speaker"); val name = p.getString("name").trim()
                    val names = meeting.obj("speakerNames")
                    val target = names.keys().asSequence().firstOrNull { it != speaker.toString() && names.optString(it).equals(name, true) && name.isNotBlank() }?.toInt()
                    if (target != null) mergeSpeaker(meeting, speaker, target)
                    else {
                        val old = names.optString(speaker.toString())
                        if (name.isBlank()) names.remove(speaker.toString()) else names.put(speaker.toString(), name)
                        meeting.put("speakerNames", names)
                        meeting.optJSONObject("cover")?.let { cover ->
                            val from = if (old.isBlank()) defaultSpeakerNames(meeting, speaker) else listOf(old)
                            meeting.put("cover", MeetingEdits.renameInCover(cover, from.map { it to name }))
                        }
                    }
                }
                refresh(id)
            }
            "speaker.assign" -> edit(id) { meeting -> meeting.array("utterances").objects().firstOrNull { it.optString("id") == p.getString("utteranceId") }?.put("speaker", p.getInt("speaker"))?.put("speakerLocked", true) }
            "speaker.merge" -> { edit(id) { mergeSpeaker(it, p.getInt("from"), p.getInt("to")) }; refresh(id) }
            "notes.save" -> edit(id) { it.put("notesDoc", p.getJSONObject("doc")) }
            "tags.set" -> { edit(id) { it.put("tags", p.getJSONArray("tags")) }; refresh(id) }
            "tag.rename", "tag.delete" -> {
                native(if (action == "tag.rename") "tag_rename" else "tag_delete", p); refresh(id.ifBlank { null })
            }
            "task.toggle", "task.edit", "task.save", "task.add", "task.delete" -> edit(id) { meeting ->
                val tasks = meeting.obj("cover").array("tasks")
                when (action) {
                    "task.toggle" -> tasks.getJSONObject(p.getInt("index")).put("done", p.getBoolean("done"))
                    "task.edit", "task.save" -> tasks.getJSONObject(p.getInt("index")).apply {
                        p.keys().forEach { if (it != "index") put(it, p.get(it)) }; put("manual", true)
                    }
                    "task.add" -> tasks.put(p.copyJson().put("manual", true))
                    "task.delete" -> tasks.remove(p.getInt("index"))
                }
                meeting.obj("cover").put("tasks", tasks)
            }
            "settings.update" -> {
                val settings = config.update(p.optJSONObject("patch") ?: p)
                native("set_language", json("language" to settings.optString("uiLanguage", "ca")))
                updateState { it.copy(settings = it.settings.copyJson().apply { settings.keys().forEach { key -> put(key, settings.get(key)) } }) }
                if (activeId != null && (p.optJSONObject("patch") ?: p).has("recordingIndicator")) {
                    if (!settings.optBoolean("recordingIndicator")) context.stopService(Intent(context, OverlayService::class.java))
                    else if (android.provider.Settings.canDrawOverlays(context)) context.startService(Intent(context, OverlayService::class.java))
                    else platformRequests.emit(PlatformRequest("overlay.permission"))
                }
            }
            "record.stop" -> ContextCompat.startForegroundService(context, Intent(context, RecordingService::class.java).setAction(RecordingService.STOP))
            "record.pause" -> { paused = !paused; updateState { it.copy(paused = paused) }; if (!paused) edit(activeId ?: return) { m ->
                m.put("parts", m.array("parts").put(json("startedAt" to System.currentTimeMillis(), "at" to state.value.seconds)))
            } }
            "record.mark" -> mark()
            "process.run" -> {
                afterJob?.cancel()
                processor.enqueue(id, p)
            }
            "summary.generate" -> processor.enqueue(id, json("cover" to true, "language" to p.optString("language")))
            "translate" -> processor.enqueue(id, json("translation" to p.getString("language")))
            "retranscribe" -> {
                val language = p.getString("language")
                require(language in listOf("ca", "es", "ca-es", "en"))
                edit(id) { it.put("language", language) }
                processor.enqueue(id, json("speakers" to true, "text" to (language != "ca-es"), "punctuation" to (language in listOf("ca", "ca-es"))))
            }
            "process.cancel" -> processor.cancel(id)
            "import.language" -> processor.chooseLanguage(p.getString("jobId"), p.getString("language"))
            "import.cancel" -> processor.cancel(p.getString("jobId"))
            "model.download" -> launchJob("model:${p.getString("id")}") {
                updateState { it.copy(busy = "download", progress = 0f, settings = it.settings.copyJson().put("downloadingModel", p.getString("id"))) }
                models.download(p.getString("id")) { done, total -> updateState { it.copy(progress = if (total > 0) done.toFloat()/total else 0f) } }
                refresh(); updateState { it.copy(busy = null, progress = null, settings = it.settings.copyJson().apply { remove("downloadingModel") }) }
            }
            "model.cancel" -> { jobs.remove("model:${p.getString("id")}")?.cancel(); updateState { it.copy(busy = null, progress = null) } }
            "model.delete" -> { check(activeId == null); models.delete(p.getString("id")); refresh() }
            "cloud.connect" -> launchJob("cloud") {
                updateState { it.copy(busy = "connect") }
                val provider = p.getString("provider")
                val available = intelligence.connect(provider, p.getString("key"))
                updateState { it.copy(busy = null, settings = it.settings.copyJson().put("providerModels", JSONArray(available)).put("providerModelsProvider", provider).put("cloudStatus", intelligence.status())) }
            }
            "cloud.disconnect" -> { intelligence.disconnect(p.getString("provider")); refresh() }
            "cloud.models" -> launchJob("cloud") {
                val provider = p.getString("provider")
                val available = intelligence.listModels(provider)
                updateState { it.copy(settings = it.settings.copyJson().put("providerModels", JSONArray(available)).put("providerModelsProvider", provider).put("cloudStatus", intelligence.status())) }
            }
            "glossary.get" -> {
                val terms = native("glossary").array("terms")
                updateState { it.copy(settings = config.read().put("glossary", terms)) }
            }
            "glossary.set" -> { native("glossary", p); refresh() }
            "glossary.accept" -> {
                val terms = (native("glossary").array("terms").strings() + p.array("terms").strings()).distinctBy { it.lowercase() }
                native("glossary", json("terms" to JSONArray(terms)))
                updateState { it.copy(settings = it.settings.copyJson().apply { remove("glossarySuggestion"); put("glossary", JSONArray(terms)) }) }
            }
            "glossary.dismiss" -> updateState { it.copy(settings = it.settings.copyJson().apply { remove("glossarySuggestion") }) }
            "voice.delete" -> { native("voice_delete"); refresh() }
            "pair.connect" -> { sync.pair(p.getString("uri")); refresh() }
            "pair.sync" -> { sync.sync(); refresh() }
            "pair.disconnect" -> { sync.unpair(); refresh() }
            "share.connect" -> CloudShare(this).connect(p.getString("url"), p.getString("key"))
            "share.setup" -> CloudShare(this).setup(p.getString("token"), p.optString("account"))
            "share.publish" -> launchJob("share") {
                updateState { it.copy(busy = "share") }
                val link = CloudShare(this).publish(get(id) ?: return@launchJob, p)
                platformRequests.emit(PlatformRequest("clipboard", json("text" to link)))
                refresh(id); updateState { it.copy(busy = null) }
            }
            "share.revoke" -> { CloudShare(this).revoke(id, p.getString("id")); refresh(id) }
            "notion.connect", "notion.disconnect", "notion.search", "notion.export" -> platformRequests.emit(PlatformRequest(action, p.put("meetingId", id)))
            "message.clear" -> clearMessage()
            else -> platformRequests.emit(PlatformRequest(action, p))
        }
    }

    private fun launchJob(key: String, block: suspend () -> Unit) {
        if (jobs[key]?.isActive == true) return
        jobs[key] = scope.launch {
            try { block() } catch (error: Throwable) { failure(key.substringBefore(':'), error) }
            finally { jobs.remove(key) }
        }
    }

    suspend fun beginRecording(options: JSONObject): String = recordingLock.withLock {
        check(activeId == null)
        processor.pause()
        val settings = config.read()
        val opts = settings.copyJson().apply { options.keys().forEach { put(it, options.get(it)) } }
        val resumed = opts.optString("resumeId")
        val result = native(if (resumed.isBlank()) "start" else "resume", opts.apply {
            if (resumed.isNotBlank()) put("id", resumed)
        })
        val id = result.getString("id")
        activeId = id
        paused = false
        val meeting = get(id) ?: json("id" to id, "title" to opts.optString("title"), "createdAt" to System.currentTimeMillis(),
            "duration" to 0, "status" to "recording", "utterances" to JSONArray(), "notesDoc" to json("type" to "doc", "content" to JSONArray()), "speakerNames" to JSONObject(), "language" to opts.optString("language", "ca-es"))
        captureOffset = meeting.optDouble("duration", 0.0)
        val voiceSaved = !native("voice_status").isNull("savedAt")
        if (opts.optBoolean("mic", true) && (!opts.optBoolean("roomSpeakers") || voiceSaved)) {
            val you = when (dev.murmur.android.intelligence.resolveUiLanguage(opts.optString("uiLanguage", "ca"))) { "en" -> "You"; "es" -> "Tú"; else -> "Tu" }
            meeting.put("speakerNames", meeting.obj("speakerNames").put("0", you))
        }
        options.optJSONArray("guests")?.let { meeting.put("guests", it) }
        put(meeting)
        updateState { it.copy(recording = true, paused = false, meeting = meeting, seconds = meeting.optDouble("duration"), partial = "", busy = null) }
        checkpoint(meeting)
        log.event("recording.started")
        analytics.track("recording_started", json("language" to opts.optString("language"), "source" to if (opts.optBoolean("system")) { if (opts.optBoolean("mic", true)) "mic+system" else "system" } else "mic"))
        id
    }

    suspend fun feed(samples: FloatArray, channel: String = "mic") {
        val id = activeId ?: return
        val response = nativeLock.withLock { nativeEngine.push(samples, channel) }
        val fresh = response.array("finals").objects()
        val glossary = if (fresh.isNotEmpty()) native("glossary").array("terms").strings() else emptyList()
        editLock.withLock {
            val current = if (state.value.meeting?.optString("id") == id) state.value.meeting!!.copyJson() else get(id) ?: return
            val lines = current.array("utterances")
            val known = lines.objects().map { it.optString("id") }.toHashSet()
            fresh.forEach { if (known.add(it.getString("id"))) lines.put(it) }
            current.put("utterances", lines).put("duration", response.optDouble("seconds", state.value.seconds))
            for (line in fresh) {
                val names = current.obj("speakerNames")
                val name = MeetingEdits.introducedName(line.optString("text"), current.array("guests").strings(),
                    names.keys().asSequence().map { names.optString(it) }.toList(), glossary) ?: continue
                val speaker = line.getInt("speaker")
                val target = names.keys().asSequence().firstOrNull { names.optString(it) == name }?.toInt()
                if (target != null && target != speaker) mergeSpeaker(current, speaker, target)
                else {
                    names.put(speaker.toString(), name); current.put("speakerNames", names)
                    lines.objects().firstOrNull { it.optString("id") == line.optString("id") }?.put("speakerLocked", true)
                }
            }
            updateState { state -> state.copy(
                meeting = if (state.meeting?.optString("id") == id) current else state.meeting,
                seconds = response.optDouble("seconds", state.seconds), level = response.optDouble("level", 0.0).toFloat(),
                partial = if (response.isNull("partial")) state.partial else response.optString("partial")) }
            if (fresh.isNotEmpty() || System.currentTimeMillis() - lastCheckpoint > 3000) {
                put(current); checkpoint(current)
                lastCheckpoint = System.currentTimeMillis()
            }
        }
    }

    suspend fun finishRecording() = recordingLock.withLock {
        val id = activeId ?: return@withLock
        val before = get(id) ?: JSONObject()
        val result = native("stop", json("extra" to before))
        var meeting = result.optJSONObject("meeting") ?: get(id) ?: before
        val manual = before.array("utterances").objects().associateBy { it.optString("id") }
        meeting.array("utterances").objects().forEach { line ->
            manual[line.optString("id")]?.let { old ->
                if (old.optBoolean("edited")) line.put("text", old.getString("text")).put("edited", true)
                if (old.optBoolean("speakerLocked")) line.put("speaker", old.getInt("speaker")).put("speakerLocked", true)
            }
        }
        for (key in listOf("notesDoc", "speakerNames", "screens", "tags", "parts", "guests")) {
            if (before.has(key)) meeting.put(key, before.get(key))
        }
        meeting.put("status", "done")
        val settings = config.read()
        val choices = processor.defaultOptions(settings, meeting.optString("language", "ca-es"), false)
        val stages = JSONArray(listOf("speakers", "text", "punctuation", "cover").filter { choices.optBoolean(it) })
        meeting.put("pending", stages)
        put(meeting)
        File(dataDir, "active.json").delete()
        activeId = null; paused = false
        native("unload")
        updateState { it.copy(recording = false, paused = false, partial = "", level = 0f) }
        refresh(id)
        log.event("recording.finished")
        analytics.track("recording_finished", json("language" to meeting.optString("language"), "minutes" to AnonymousAnalytics.bucketMinutes(meeting.optDouble("duration"))))
        processor.resume()
        processor.ensureService()
        afterJob?.cancel()
        afterJob = scope.launch {
            if (stages.length() > 0 && settings.optBoolean("askAfter", true)) delay(10_000)
            // Export and audio-retention choices also apply when every optional AI stage is off.
            processor.enqueue(id, choices)
        }
    }

    private suspend fun mark() {
        val id = activeId ?: return
        edit(id) { m ->
            val doc = m.optJSONObject("notesDoc") ?: json("type" to "doc", "content" to JSONArray())
            val content = doc.array("content")
            content.put(json("type" to "paragraph", "attrs" to json("t" to state.value.seconds),
                "content" to JSONArray().put(json("type" to "text", "text" to "★"))))
            m.put("notesDoc", doc.put("content", content))
        }
    }
    private fun checkpoint(meeting: JSONObject) {
        val target = File(dataDir, "active.json")
        val temp = File(dataDir, "active.json.tmp")
        temp.writeText(meeting.copyJson().put("_captureOffset", captureOffset).toString()); check(temp.renameTo(target))
    }
    private fun defaultSpeakerNames(meeting: JSONObject, speaker: Int): List<String> {
        val index = meeting.array("utterances").objects().map { it.optInt("speaker") }.distinct().sorted().indexOf(speaker).coerceAtLeast(0) + 1
        return listOf("Parlant $index", "Hablante $index", "Speaker $index")
    }
    private fun mergeSpeaker(meeting: JSONObject, from: Int, to: Int) {
        if (from == to) return
        val names = meeting.obj("speakerNames")
        val old = names.optString(from.toString())
        val fromVariants = if (old.isBlank()) defaultSpeakerNames(meeting, from) else listOf(old)
        val next = names.optString(to.toString()).ifBlank { defaultSpeakerNames(meeting, to).first() }
        meeting.array("utterances").objects().filter { it.optInt("speaker") == from }.forEach { it.put("speaker", to).put("speakerLocked", true) }
        names.remove(from.toString()); meeting.put("speakerNames", names)
        meeting.optJSONObject("cover")?.let { cover -> meeting.put("cover", MeetingEdits.renameInCover(cover, fromVariants.map { it to next })) }
    }
    private suspend fun recoverRecording() {
        val file = File(dataDir, "active.json")
        if (!file.exists()) return
        var meeting = JSONObject(file.readText()).put("status", "done").put("recovered", true)
        val original = meeting.copyJson()
        put(meeting)
        try {
            meeting = native("recover_capture", json("id" to meeting.getString("id"), "offset" to original.optDouble("_captureOffset", 0.0))).optJSONObject("meeting") ?: original
        } catch (error: Throwable) { log.event("recording.recovery_audio", error) }
        put(meeting); file.delete(); log.event("recording.recovered")
    }

    companion object {
        @Volatile private var instance: AppRuntime? = null
        fun get(context: Context): AppRuntime = instance ?: synchronized(this) {
            instance ?: AppRuntime(context).also { instance = it }
        }
    }
}
