package dev.murmur.android

import android.content.Intent
import androidx.core.content.ContextCompat
import dev.murmur.android.intelligence.InferenceClient
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.RandomAccessFile
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

/** A journaled, serial processing queue. Only the process-owned runtime writes meeting data. */
class MeetingProcessor(private val runtime: AppRuntime) {
    private val journal = File(runtime.dataDir, "jobs.json")
    private val wake = Channel<Unit>(Channel.CONFLATED)
    private val paused = AtomicBoolean(false)
    @Volatile private var current: Job? = null
    @Volatile private var currentId: String? = null
    private val worker = runtime.scope.launch { workLoop() }

    @Synchronized private fun entries(): MutableList<JSONObject> =
        runCatching { JSONArray(journal.readText()).objects().toMutableList() }.getOrDefault(mutableListOf())
    @Synchronized private fun write(entries: List<JSONObject>) {
        val temp = File(journal.path + ".tmp")
        temp.writeText(JSONArray(entries).toString()); check(temp.renameTo(journal))
    }
    @Synchronized private fun change(id: String, change: (JSONObject) -> Unit) {
        val entries = entries(); entries.firstOrNull { it.optString("job") == id }?.let(change); write(entries)
    }

    fun ensureService() {
        if (McpService.status().optBoolean("running")) return
        ContextCompat.startForegroundService(runtime.context, Intent(runtime.context, ProcessingService::class.java))
    }
    suspend fun pause() { paused.set(true); current?.cancelAndJoin() }
    fun resume() { paused.set(false); wake.trySend(Unit) }
    suspend fun recover() {
        val tasks = entries()
        tasks.filter { it.optString("status") == "running" }.forEach { it.put("status", "waiting") }
        write(tasks)
        publishPendingImports()
        // No foreground service is started from a background boot receiver.
        wake.trySend(Unit)
    }
    suspend fun defaultOptions(settings: JSONObject, language: String, file: Boolean): JSONObject {
        val installed = runtime.models.catalog().filter { it.optBoolean("installed") }.map { it.getString("id") }.toSet()
        val source = if (file) settings.obj("fileSteps") else settings
        val whisper = if (settings.optString("whisperProfile") == "small") "whisper-small" else if (language == "ca") "whisper-ca" else "whisper"
        val provider = settings.optString("coverEngine", "local")
        val summaryReady = if (provider == "local") settings.optString("localModel", "llm-mobile") in installed
            else !runtime.intelligence.status().isNull(provider) && settings.obj("cloudModels").optString(provider).isNotBlank()
        return json("speakers" to (source.optBoolean("verifySpeakers", true) && "transcription" in installed),
            "text" to (source.optBoolean("finalPass", true) && language != "ca-es" && whisper in installed),
            "punctuation" to (source.optBoolean("punctuate") && language in listOf("ca", "ca-es") && "punctuation" in installed),
            "cover" to (source.optBoolean("summarize") && summaryReady))
    }
    suspend fun enqueue(id: String, options: JSONObject): String {
        if (runtime.activeId == null) paused.set(false)
        require(id.isNotBlank())
        val task = json("job" to UUID.randomUUID().toString(), "meetingId" to id, "options" to options,
            "status" to "waiting", "createdAt" to System.currentTimeMillis(), "kind" to "process")
        synchronized(this) {
            val tasks = entries()
            tasks.filter { it.optString("meetingId") == id && it.optString("status") == "waiting" }.forEach { it.put("status", "superseded") }
            tasks += task; write(tasks.takeLast(200))
        }
        ensureService(); wake.trySend(Unit)
        return task.getString("job")
    }
    suspend fun importAudio(path: String, title: String = "", language: String? = null, maxSpeakers: Int? = null): String {
        if (runtime.activeId == null) paused.set(false)
        require(maxSpeakers == null || maxSpeakers in 1..50)
        val file = File(path).canonicalFile
        require(file.isFile && (file.path.startsWith(runtime.context.filesDir.canonicalPath + File.separator) || file.path.startsWith(runtime.context.cacheDir.canonicalPath + File.separator)))
        val task = json("job" to UUID.randomUUID().toString(), "kind" to "import", "path" to file.path,
            "title" to title, "language" to language, "maxSpeakers" to maxSpeakers, "status" to "waiting", "createdAt" to System.currentTimeMillis())
        synchronized(this) { val tasks = entries(); tasks += task; write(tasks.takeLast(200)) }
        ensureService(); wake.trySend(Unit)
        return task.getString("job")
    }
    fun status(jobId: String): JSONObject = entries().firstOrNull { it.optString("job") == jobId }?.copyJson()?.apply {
        remove("path"); remove("title"); remove("options")
    } ?: json("job" to jobId, "status" to "unknown")
    fun pendingImports(): JSONArray = JSONArray(entries().filter { it.optString("status") == "language" }.map {
        json("job" to it.getString("job"), "title" to it.optString("title"))
    })
    fun chooseLanguage(job: String, language: String) {
        require(language in listOf("ca", "es", "ca-es", "en"))
        change(job) { if (it.optString("status") == "language") it.put("language", language).put("status", "waiting") }
        publishPendingImports(); ensureService(); wake.trySend(Unit)
    }
    private fun publishPendingImports() { runtime.updateState { it.copy(settings = it.settings.copyJson().put("pendingImports", pendingImports())) } }
    fun cancel(id: String) {
        synchronized(this) {
            val tasks = entries()
            tasks.filter { it.optString("job") == id || it.optString("meetingId") == id }.forEach { it.put("status", "cancelled") }
            write(tasks)
        }
        if (id == currentId || entries().any { it.optString("job") == currentId && it.optString("meetingId") == id }) current?.cancel()
        publishPendingImports()
    }
    fun hasWork(): Boolean = entries().any { it.optString("status") in listOf("waiting", "running") }
    fun foregroundStopped() { paused.set(true); current?.cancel() }

    private suspend fun workLoop() {
        for (signal in wake) {
            while (true) {
                while (paused.get() || runtime.activeId != null) delay(250)
                val task = entries().firstOrNull { it.optString("status") == "waiting" } ?: break
                val jobId = task.getString("job")
                currentId = jobId
                change(jobId) { it.put("status", "running").remove("error") }
                current = runtime.scope.launch {
                    try {
                        if (task.optString("kind") == "import") importTask(task) else processTask(task)
                        change(jobId) { if (it.optString("status") == "running") it.put("status", "done").put("finishedAt", System.currentTimeMillis()) }
                    } catch (cancelled: CancellationException) {
                        change(jobId) { if (it.optString("status") == "running") it.put("status", "waiting") }
                    } catch (error: Throwable) {
                        change(jobId) { it.put("status", "failed").put("error", error.message ?: error.javaClass.simpleName) }
                        runtime.failure("processing.failed", error)
                        task.optString("meetingId").takeIf(String::isNotBlank)?.let { id -> runtime.edit(id) { it.put("status", "done") } }
                    }
                }
                current!!.join(); current = null; currentId = null
            }
            runtime.updateState { it.copy(busy = null, progress = null) }
        }
    }

    private suspend fun importTask(task: JSONObject) {
        runtime.updateState { it.copy(busy = "importing", progress = 0f) }
        val settings = runtime.config.read()
        task.optInt("maxSpeakers", 0).takeIf { it > 0 }?.let { settings.put("maxSpeakers", it) }
        val cache = File(runtime.context.cacheDir, "imports").apply { mkdirs() }
        val wav = File(cache, task.getString("job") + ".wav")
        runtime.native("decode_audio", json("path" to task.getString("path"), "dest" to wav.path))
        var language = task.optString("language").takeIf { it in listOf("ca", "es", "ca-es", "en") }
        if (language == null && runtime.models.catalog().any { it.optString("id") == "whisper" && it.optBoolean("installed") }) {
            val spans = runtime.native("speech_spans", json("path" to wav.path)).array("spans")
            val detected = InferenceClient(runtime.context).detectLanguage(runtime.models.path("whisper"), wav.path, spans)
            if (!detected.isNull("language")) language = detected.getString("language")
        }
        if (language == null) {
            change(task.getString("job")) { it.put("status", "language") }
            publishPendingImports(); wav.delete(); return
        }
        val result = runtime.native("start", json("title" to task.optString("title"), "language" to language, "roomSpeakers" to true,
            "maxSpeakers" to settings.optInt("maxSpeakers", 5), "threshold" to settings.optDouble("threshold", .7), "partialEvery" to 0, "mic" to true, "system" to false))
        val id = result.getString("id")
        change(task.getString("job")) { it.put("meetingId", id) }
        var completed = false
        try {
            RandomAccessFile(wav, "r").use { input ->
                input.seek(44)
                val buffer = ByteArray(32000)
                while (input.filePointer < input.length()) {
                    currentCoroutineContext().ensureActive()
                    val read = input.read(buffer).let { it - it % 2 }
                    if (read <= 0) break
                    val samples = JSONArray()
                    for (i in 0 until read / 2) samples.put((((buffer[2*i].toInt() and 255) or (buffer[2*i+1].toInt() shl 8)).toShort()) / 32768.0)
                    runtime.native("push", json("samples" to samples, "channel" to "mic"))
                    runtime.updateState { it.copy(progress = input.filePointer.toFloat() / input.length()) }
                }
            }
            runtime.native("stop", JSONObject())
            completed = true
            runtime.analytics.track("file_transcribed", json("language" to language, "source" to "file"))
            runtime.refresh(id)
            val options = defaultOptions(settings, language, true)
            val updated = task.copyJson().put("meetingId", id).put("options", options)
            change(task.getString("job")) { it.put("kind", "process").put("meetingId", id).put("options", options).remove("path") }
            File(task.getString("path")).delete()
            processTask(updated)
        } finally {
            withContext(NonCancellable) {
                if (!completed) {
                    runCatching { runtime.native("discard") }
                    runCatching { runtime.native("delete", json("id" to id)) }
                }
                runtime.native("unload")
                wav.delete()
            }
        }
    }

    private suspend fun processTask(task: JSONObject) {
        val id = task.getString("meetingId")
        val options = task.obj("options")
        val settings = runtime.config.read()
        task.optInt("maxSpeakers", 0).takeIf { it > 0 }?.let { settings.put("maxSpeakers", it) }
        val selectedLanguage = options.optString("language")
        if (selectedLanguage in listOf("ca", "es", "en")) settings.put("coverLanguage", selectedLanguage)
        if (settings.optString("coverEngine", "local") == "local" && (options.optBoolean("cover") || options.has("translation"))) {
            settings.put("localModelPath", runtime.models.path(settings.optString("localModel", "llm-mobile")))
        }
        settings.put("modelsDir", runtime.models.root.path)
        settings.put("glossary", runtime.native("glossary").array("terms"))
        val initial = runtime.get(id) ?: return
        runtime.edit(id) { it.put("status", "processing") }
        val stages = listOf("speakers", "text", "punctuation", "cover").filter { options.optBoolean(it) }
        val pending = (initial.array("pending").strings() + stages).distinct().toMutableList()
        if (!settings.optBoolean("saveAudio")) pending.removeAll { stage -> stage in listOf("speakers", "text") && !options.optBoolean(stage) }
        runtime.edit(id) { it.put("pending", JSONArray(pending)) }
        val wavs = mutableListOf<File>()
        val errors = mutableListOf<Throwable>()
        try {
            for (stage in stages) {
                currentCoroutineContext().ensureActive()
                while (paused.get() || runtime.activeId != null) delay(250)
                runtime.updateState { it.copy(busy = stage, progress = null) }
                try {
                    runtime.native("unload")
                    var meeting = runtime.get(id) ?: return
                    val language = meeting.optString("language", "ca-es")
                    if (stage in listOf("speakers", "text")) {
                        if (stage == "text" && language == "ca-es") {
                            pending.remove(stage); runtime.edit(id) { it.put("pending", JSONArray(pending)) }; continue
                        }
                        val source = File(meeting.optString("audioPath"))
                        require(source.isFile) { "Saved audio is required" }
                        val dual = meeting.obj("sources").optBoolean("mic") && meeting.obj("sources").optBoolean("system")
                        val bases = if (dual) listOf(0, 100) else listOf(0)
                        for (base in bases) {
                            val snapshot = meeting.array("utterances").objects().filter { !dual || (it.optInt("speaker") >= 100) == (base == 100) }
                            if (snapshot.isEmpty()) continue
                            val wav = File(runtime.context.cacheDir, "final/$id-$base.wav").apply { parentFile!!.mkdirs() }
                            wavs += wav
                            runtime.native("decode_audio", json("path" to source.path, "dest" to wav.path, "channel" to if (dual) base / 100 else JSONObject.NULL))
                            if (stage == "speakers") {
                                val assignments = runtime.native("diarize", json("path" to wav.path, "spans" to JSONArray(snapshot), "base" to base, "maxSpeakers" to settings.optInt("maxSpeakers", 5))).array("assignments").objects()
                                applySpeakers(id, snapshot, assignments)
                            } else {
                                val model = if (settings.optString("whisperProfile") == "small") "whisper-small" else if (language == "ca") "whisper-ca" else "whisper"
                                val spans = JSONArray(snapshot.filterNot { it.optBoolean("edited") }.map { json("id" to it.getString("id"), "start" to it.getDouble("start"), "end" to it.getDouble("end")) })
                                val revised = InferenceClient(runtime.context).transcribeSpans(runtime.models.path(model), wav.path, language, spans).objects()
                                val corrected = runtime.native("glossary_correct", json("texts" to JSONArray(revised.map { it.optString("text") }), "guests" to meeting.array("guests"))).array("texts")
                                revised.forEachIndexed { index, line -> if (index < corrected.length()) line.put("text", corrected.getString(index)) }
                                val texts = revised.filter { it.optString("text").isNotBlank() }.associateBy { it.getString("id") }
                                runtime.edit(id) { fresh ->
                                    fresh.array("utterances").objects().forEach { line ->
                                        texts[line.optString("id")]?.let { result ->
                                            val old = snapshot.find { it.optString("id") == line.optString("id") }
                                            if (!line.optBoolean("edited") && old?.optString("text") == line.optString("text")) line.put("text", result.getString("text"))
                                        }
                                    }
                                }
                            }
                        }
                    } else if (stage == "punctuation") {
                        if (language !in listOf("ca", "ca-es")) {
                            pending.remove(stage); runtime.edit(id) { it.put("pending", JSONArray(pending)) }; continue
                        }
                        val snapshot = meeting.array("utterances").objects()
                        val texts = runtime.native("punctuate", json("texts" to JSONArray(snapshot.map { it.optString("text") }), "speakers" to JSONArray(snapshot.map { it.optInt("speaker") }))).array("texts")
                        runtime.edit(id) { fresh ->
                            val byId = fresh.array("utterances").objects().associateBy { it.optString("id") }
                            snapshot.forEachIndexed { index, old -> byId[old.optString("id")]?.let { line ->
                                if (!line.optBoolean("edited") && line.optString("text") == old.optString("text") && index < texts.length()) line.put("text", texts.getString(index))
                            } }
                        }
                    } else if (stage == "cover") {
                        val result = runtime.intelligence.summarize(meeting, settings) { _, done, total -> runtime.updateState { it.copy(progress = if (total > 0) done.toFloat()/total else 0f) } }
                        runtime.edit(id) { fresh ->
                            val cover = result.getJSONObject("cover")
                            val next = cover.array("tasks").objects().toMutableList()
                            val currentTasks = fresh.obj("cover").array("tasks").objects()
                            currentTasks.filter { it.optBoolean("manual") }.forEach { task ->
                                val previous = next.indexOfFirst { it.optString("text").equals(task.optString("text"), true) }
                                if (previous >= 0) next[previous] = task else next += task
                            }
                            val checked = currentTasks.filter { it.optBoolean("done") }.map { it.optString("text").lowercase() }.toSet()
                            next.forEach { if (it.optString("text").lowercase() in checked) it.put("done", true) }
                            cover.put("tasks", JSONArray(next)); fresh.put("cover", cover)
                            if (!result.isNull("title") && fresh.optString("title") == meeting.optString("title") && isDefaultTitle(fresh.optString("title"))) fresh.put("title", result.getString("title"))
                        }
                        runtime.analytics.track("summary_generated", json("engine" to settings.optString("coverEngine", "local")))
                    }
                    pending.remove(stage)
                    runtime.edit(id) { it.put("pending", JSONArray(pending)) }
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (error: Throwable) { errors += error; runtime.log.event("processing.$stage", error) }
            }
            if (options.has("translation")) {
                runtime.updateState { it.copy(busy = "translation", progress = 0f) }
                val result = runtime.intelligence.translate(runtime.get(id) ?: return, options.getString("translation"), settings,
                    onPartial = { partial -> runtime.edit(id) { it.put("translation", partial) } },
                    onProgress = { _, done, total -> runtime.updateState { it.copy(progress = if (total > 0) done.toFloat()/total else 0f) } })
                runtime.edit(id) { it.put("translation", result) }
                runtime.analytics.track("translated", json("language" to options.getString("translation"), "engine" to settings.optString("coverEngine", "local")))
            }
            runtime.edit(id) { it.put("status", "done") }
            runtime.refresh(id)
            if (errors.isNotEmpty()) throw errors.first()
            runtime.get(id)?.let { ExportCoordinator(runtime).automatic(it) }
            if (!settings.optBoolean("saveAudio") && pending.isEmpty() && !runtime.sync.status().optBoolean("paired")) {
                runtime.edit(id) { meeting ->
                    meeting.optString("audioPath").takeIf(String::isNotBlank)?.let { File(it).delete() }
                    meeting.put("audioPath", JSONObject.NULL)
                }
            }
        } finally {
            withContext(NonCancellable) {
                wavs.distinctBy { it.path }.forEach { it.delete() }
                runtime.native("unload")
            }
        }
    }

    private suspend fun applySpeakers(id: String, old: List<JSONObject>, assignments: List<JSONObject>) {
        runtime.edit(id) { meeting ->
            val names = meeting.obj("speakerNames")
            val fresh = meeting.array("utterances").objects().associateBy { it.optString("id") }
            val anchors = mutableMapOf<Int, MutableMap<Int, Double>>()
            for (assignment in assignments) {
                val before = old.minByOrNull { kotlin.math.abs(it.optDouble("start") - assignment.optDouble("start")) } ?: continue
                val speaker = before.optInt("speaker")
                if (names.optString(speaker.toString()).isNotBlank() || before.optBoolean("speakerLocked")) {
                    val weights = anchors.getOrPut(assignment.getInt("speaker")) { mutableMapOf() }
                    weights[speaker] = (weights[speaker] ?: 0.0) + (before.optDouble("end") - before.optDouble("start"))
                }
            }
            val mapped = anchors.mapValues { (_, weights) -> weights.maxByOrNull { it.value }!!.key }.toMutableMap()
            val reserved = (mapped.values + fresh.values.filter { it.optBoolean("speakerLocked") }.map { it.optInt("speaker") } + names.keys().asSequence().mapNotNull { it.toIntOrNull() }.toList()).toMutableSet()
            val base = if (old.all { it.optInt("speaker") >= 100 }) 100 else 0
            for (cluster in assignments.map { it.getInt("speaker") }.distinct()) {
                if (cluster !in mapped) {
                    var next = base
                    while (next in reserved) next++
                    mapped[cluster] = next; reserved += next
                }
            }
            for (assignment in assignments) {
                val before = old.minByOrNull { kotlin.math.abs(it.optDouble("start") - assignment.optDouble("start")) } ?: continue
                val line = fresh[before.optString("id")] ?: continue
                if (!line.optBoolean("speakerLocked")) line.put("speaker", mapped[assignment.getInt("speaker")] ?: assignment.getInt("speaker"))
            }
            meeting.put("speakersVersion", meeting.optInt("speakersVersion") + 1)
        }
    }
    private fun isDefaultTitle(title: String) = title.isBlank() || title.matches(Regex("^(Reunió|Reunión|Meeting)(\\s+\\d.*)?$", RegexOption.IGNORE_CASE))
}
