package dev.murmur.android.intelligence

import android.content.Context
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.DateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

class IntelligenceManager(private val context: Context) {
    private val secrets = SecretStore(context)
    private val cloud = CloudClient()
    private val local = InferenceClient(context)
    suspend fun listModels(provider: String, key: String? = null): List<JSONObject> = cloud.listModels(provider, key ?: secrets.get("cloud.$provider") ?: throw IntelligenceException("cloud_no_key"))
    suspend fun connect(provider: String, key: String): List<JSONObject> {
        require(provider in CloudClient.providers)
        val models = cloud.listModels(provider, key.trim())
        if (provider == "ollama") cloud.validateOllamaKey(key.trim(), models.firstOrNull()?.getString("id") ?: throw IntelligenceException("cloud_no_model"))
        secrets.put("cloud.$provider", key.trim())
        return models
    }
    fun disconnect(provider: String) = secrets.remove("cloud.$provider")
    fun status(): JSONObject = JSONObject().apply { CloudClient.providers.forEach { provider -> put(provider, secrets.get("cloud.$provider")?.takeLast(4) ?: JSONObject.NULL) } }
    fun message(error: Throwable, language: String = "ca"): String = Catalog(context, language).text("errors.${(error as? IntelligenceException)?.code ?: "local_model_failed"}")

    private suspend fun answer(settings: JSONObject, system: String, document: String, prompt: String, schema: JSONObject, maxTokens: Int): JSONObject {
        val provider = settings.optString("coverEngine", "local")
        val selectedModel = settings.optJSONObject("cloudModels")?.optString(provider).orEmpty()
        val path = settings.optString("localModelPath").ifBlank {
            File(settings.optString("modelsDir", File(context.filesDir, "models").path), "llm-mobile/gemma-4-E2B-it-Q4_0.gguf").path
        }
        if (provider == "local" && !File(path).isFile) throw IntelligenceException("local_model_missing")
        if (provider != "local" && selectedModel.isBlank()) throw IntelligenceException("cloud_no_model")
        val key = if (provider == "local") "" else secrets.get("cloud.$provider") ?: throw IntelligenceException("cloud_no_key")
        var question = prompt
        repeat(2) { attempt ->
            currentCoroutineContext().ensureActive()
            val text = if (provider == "local") local.generate(path, system, document, question, maxTokens, schema.toString())
                else cloud.ask(provider, selectedModel, key, system, document, question, schema, maxTokens)
            val clean = text.trim().removePrefix("```json").removePrefix("```").removeSuffix("```").trim()
            val result = runCatching { JSONObject(clean) }.getOrNull()
            if (result != null && validateJson(result, schema)) return result
            if (attempt == 0) question = "$prompt\nThe previous response was not valid for the required JSON schema. Return only a corrected JSON object, with all required fields and no explanation.\nSchema: $schema\nPrevious response: ${clean.take(12000)}"
        }
        throw IntelligenceException("cloud_invalid_json")
    }

    /** Returns { cover, title }; replacing a user title remains the caller's decision. */
    suspend fun summarize(meeting: JSONObject, settings: JSONObject, onProgress: (String, Int, Int) -> Unit = { _, _, _ -> }): JSONObject {
        if (meeting.optJSONArray("utterances")?.length() == 0) throw IntelligenceException("empty_transcript")
        val language = when (settings.optString("coverLanguage", "interface")) {
            "meeting" -> meeting.optString("language").let { if (it == "ca-es") "ca" else it.takeIf { value -> value in listOf("ca", "es", "en") } ?: resolveUiLanguage(settings.optString("uiLanguage", "system")) }
            "ca", "es", "en" -> settings.optString("coverLanguage")
            else -> resolveUiLanguage(settings.optString("uiLanguage", "system"))
        }
        val catalog = Catalog(context, language)
        val tones = listOf("constructive", "relaxed", "neutral", "formal", "tense", "conflictive")
        val toneNames = tones.map { catalog.text("tones.$it") }
        val overrides = settings.optJSONObject("prompts") ?: JSONObject()
        fun prompt(id: String) = overrides.optString(id).trim().ifBlank { catalog.text("prompts.$id", "tones" to toneNames.joinToString(", ")) }
        val source = document(meeting, language)
        val spellings = coverTerms(meeting, source, settings.optJSONArray("glossary")?.strings().orEmpty())
        val system = prompt("system") + if (spellings.isEmpty()) "" else "\n\n${catalog.text("spelling", "terms" to spellings.joinToString(", "))}"
        var document = source
        val localMode = settings.optString("coverEngine", "local") == "local"
        if (localMode && document.length > 6500) {
            val chunks = splitDocument(document, 6000)
            val reduced = mutableListOf<String>()
            val schema = obj("type" to "object", "properties" to obj("notes" to obj("type" to "string")), "required" to arr(listOf("notes")))
            for ((index, chunk) in chunks.withIndex()) {
                onProgress("reading", index, chunks.size)
                val data = answer(settings, system, chunk,
                    "Create compact faithful meeting notes in $language. Retain every explicit decision, task, assignee, date, name, and timestamp, including the end of this fragment. Do not invent anything. Return {\"notes\":\"...\"}.", schema, 1100)
                reduced += data.getString("notes")
            }
            document = reduced.joinToString("\n\n")
            // Hierarchical reduction keeps the context bounded even for multi-hour meetings.
            while (document.length > 6500) {
                val before = document
                val pieces = splitDocument(document, 6000)
                document = pieces.mapIndexed { index, piece ->
                    onProgress("reading", index, pieces.size)
                    answer(settings, system, piece,
                        "Condense these meeting notes to at most 450 words in $language. Preserve all decisions, assigned tasks, dates, names and their original timestamps. Do not invent facts.", schema, 900).getString("notes")
                }.joinToString("\n\n")
                if (document.length >= before.length) throw IntelligenceException("cloud_too_long")
            }
        }
        val cover = meeting.optJSONObject("cover")?.let { JSONObject(it.toString()) } ?: JSONObject()
        cover.put("generatedAt", System.currentTimeMillis()).put("lang", language).put("basis", basis(source))
        val provider = settings.optString("coverEngine", "local")
        if (provider == "local") cover.remove("writtenBy") else cover.put("writtenBy", settings.optJSONObject("cloudModels")?.optString(provider))
        var proposedTitle: String? = null
        val errors = JSONObject()
        val ids = listOf("title", "tone", "summary", "decisions", "tasks", "parts")
        for ((index, id) in ids.withIndex()) {
            onProgress(id, index, ids.size)
            try {
                val data = answer(settings, system, document, prompt(id), summarySchema(id, toneNames), if (id == "parts") 1400 else 1000)
                val duration = meeting.optDouble("duration", 0.0)
                when (id) {
                    "title" -> { proposedTitle = data.getString("title").trim().trimEnd('.', '。'); cover.put("excerpt", data.getString("excerpt").trim()) }
                    "tone" -> { val label = data.getString("tone"); cover.put("tone", obj("label" to tones.getOrElse(toneNames.indexOf(label)) { label }, "reason" to data.getString("reason"))) }
                    "summary" -> cover.put("summary", arr(data.getJSONArray("paragraphs").strings().map(String::trim).filter(String::isNotBlank)))
                    "decisions" -> cover.put("decisions", arr(data.getJSONArray("decisions").objects().map { obj("text" to it.getString("text").trim(), "t" to parseMinute(it.optString("minute"), duration)) }.filter { it.getString("text").isNotBlank() }))
                    "tasks" -> {
                        val previous = meeting.optJSONObject("cover")?.optJSONArray("tasks")?.objects().orEmpty()
                        val done = previous.filter { it.optBoolean("done") }.map { it.optString("text").trim().lowercase() }.toSet()
                        val next = data.getJSONArray("tasks").objects().map { task -> obj("text" to task.getString("task").trim(), "owner" to optional(task.optString("owner")), "when" to optional(task.optString("when")), "t" to parseMinute(task.optString("minute"), duration)).apply { if (optString("text").lowercase() in done) put("done", true) } }.filter { it.optString("text").isNotBlank() }.toMutableList()
                        val known = next.map { it.optString("text").lowercase() }.toSet()
                        next += previous.filter { it.optBoolean("manual") && it.optString("text").trim().lowercase() !in known }
                        cover.put("tasks", arr(next))
                    }
                    "parts" -> cover.put("parts", arr(data.getJSONArray("parts").objects().mapNotNull { part -> parseMinute(part.optString("minute"), duration)?.let { time -> obj("t" to time, "title" to part.getString("title").trim(), "points" to part.getJSONArray("points")) } }.sortedBy { it.optDouble("t") }))
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) { errors.put(id, (error as? IntelligenceException)?.code ?: "local_model_failed") }
        }
        onProgress("done", ids.size, ids.size)
        if (errors.length() == ids.size) throw IntelligenceException(errors.optString("title", "cloud_invalid_json"))
        if (errors.length() > 0) cover.put("errors", errors) else cover.remove("errors")
        return obj("cover" to cover, "title" to proposedTitle)
    }

    /** Partial results are durable through the caller; original utterances are never mutated. */
    suspend fun translate(meeting: JSONObject, language: String, settings: JSONObject, onPartial: suspend (JSONObject) -> Unit = {}, onProgress: (String, Int, Int) -> Unit = { _, _, _ -> }): JSONObject {
        require(language in listOf("ca", "es", "en"))
        val lines = meeting.optJSONArray("utterances")?.objects().orEmpty()
        val texts = JSONObject()
        val sources = JSONObject().apply { lines.forEach { put(it.getString("id"), it.optString("text")) } }
        val translation = obj("lang" to language, "texts" to texts, "sources" to sources, "done" to false)
        if (settings.optString("coverEngine", "local") != "local") translation.put("writtenBy", settings.optJSONObject("cloudModels")?.optString(settings.optString("coverEngine")))
        val schema = obj("type" to "object", "properties" to obj("lines" to obj("type" to "array", "items" to obj("type" to "object", "properties" to obj("n" to obj("type" to "integer"), "text" to obj("type" to "string")), "required" to arr(listOf("n", "text"))))), "required" to arr(listOf("lines")))
        val system = "You translate meeting transcripts. Each numbered line is one utterance. Translate faithfully and naturally; retain the meaning, tone, names, figures and dates. Never summarise, merge, split, omit or add lines."
        onPartial(JSONObject(translation.toString()))
        // Bound by both utterance count and text size, including unusually long utterances.
        var offset = 0
        while (offset < lines.size) {
            currentCoroutineContext().ensureActive()
            var end = minOf(offset + 25, lines.size)
            while (end > offset + 1 && lines.subList(offset, end).sumOf { it.optString("text").length } > 5500) end--
            val input = lines.subList(offset, end).mapIndexed { index, line -> "${offset + index + 1}. ${speakerName(meeting, line.optInt("speaker"), language)}: ${line.optString("text")}" }.joinToString("\n")
            val prompt = "Translate lines ${offset + 1} to $end into ${mapOf("ca" to "Catalan", "es" to "Spanish", "en" to "English")[language]}. Return one entry per line, its number and only the translated words, without the speaker name. A line already in that language stays as it is."
            val result = answer(settings, system, input, prompt, schema, maxOf(1000, input.length / 2 + 200))
            result.getJSONArray("lines").objects().forEach { line ->
                val index = line.optInt("n", 0) - 1
                if (index in offset until end && line.optString("text").isNotBlank()) texts.put(lines[index].getString("id"), line.getString("text").trim())
            }
            offset = end
            onPartial(JSONObject(translation.toString())); onProgress("translation", offset, lines.size)
        }
        translation.put("done", texts.length() == lines.size)
        onPartial(JSONObject(translation.toString()))
        return translation
    }

    fun document(meeting: JSONObject, language: String = "ca"): String {
        val catalog = Catalog(context, language)
        val utterances = meeting.optJSONArray("utterances")?.objects().orEmpty()
        val names = utterances.map { speakerName(meeting, it.optInt("speaker"), language) }.distinct()
        val recorded = Date(meeting.optLong("createdAt"))
        val locale = Locale.forLanguageTag(language)
        val day = DateFormat.getDateInstance(DateFormat.LONG, locale).format(recorded).replace('’', '\'').let {
            if (language == "ca") it.replace(Regex(" del (\\d{4})$"), " de $1") else it
        }
        val time = DateFormat.getTimeInstance(DateFormat.SHORT, locale).format(recorded)
        val date = catalog.text("format.dateTime", "date" to day, "time" to time)
        val minutes = (meeting.optDouble("duration") / 60).roundToInt()
        val duration = if (minutes < 60) catalog.text("format.minutes", "m" to minutes)
            else catalog.text("format.hoursMinutes", "h" to minutes / 60, "m" to (minutes % 60).toString().padStart(2, '0'))
        val lines = mutableListOf(catalog.text("header", "date" to date, "duration" to duration, "names" to names.joinToString(", ")), "")
        var previous: Int? = null
        utterances.forEach { utterance ->
            val speaker = utterance.optInt("speaker")
            val text = utterance.optString("text")
            if (speaker == previous && lines.isNotEmpty()) lines[lines.lastIndex] += " $text"
            else lines += "[${clock(utterance.optDouble("start"))}] ${speakerName(meeting, speaker, language)}: $text"
            previous = speaker
        }
        val notes = noteBlocks(meeting)
        if (notes.isNotEmpty()) { lines += ""; lines += catalog.text("notesHeading"); notes.forEach { (time, text) -> lines += "[${clock(time)}] $text" } }
        return lines.joinToString("\n")
    }
}

fun clock(seconds: Double): String {
    val total = maxOf(0, seconds.toInt())
    return if (total >= 3600) "%d:%02d:%02d".format(Locale.ROOT, total / 3600, total / 60 % 60, total % 60) else "%d:%02d".format(Locale.ROOT, total / 60, total % 60)
}
fun speakerName(meeting: JSONObject, speaker: Int, language: String = "ca"): String = meeting.optJSONObject("speakerNames")?.optString(speaker.toString()).orEmpty().ifBlank {
    if (speaker == 0 && meeting.optJSONObject("sources")?.optBoolean("system") == true && !meeting.optBoolean("roomSpeakers")) mapOf("ca" to "Tu", "es" to "Tú", "en" to "You")[language] ?: "Tu"
    else "${mapOf("ca" to "Parlant", "es" to "Hablante", "en" to "Speaker")[language] ?: "Parlant"} ${if (speaker >= 100) speaker - 99 else speaker + 1}"
}
fun basis(document: String): String { var h = 5381; document.forEach { h = (h shl 5) + h + it.code }; return "${document.length}:${h.toUInt().toString(36)}" }
internal fun coverTerms(meeting: JSONObject, document: String, glossary: List<String>): List<String> {
    fun fold(value: String) = java.text.Normalizer.normalize(value, java.text.Normalizer.Form.NFD).replace(Regex("\\p{M}"), "").lowercase(Locale.ROOT)
    val text = fold(document)
    val guests = meeting.optJSONArray("guests")?.strings().orEmpty().filter { Regex("\\p{Lu}").containsMatchIn(it) }
    return (guests + glossary.filter { it.isNotBlank() && text.contains(fold(it)) }).distinctBy { it.lowercase(Locale.ROOT) }
}
fun parseMinute(value: String, duration: Double): Double? { val parts = value.trim().replace("[", "").replace("]", "").split(':'); if (parts.size !in 2..3) return null; val numbers = parts.map { it.toDoubleOrNull() ?: return null }; if (numbers.any { !it.isFinite() || it < 0 }) return null; val seconds = numbers.fold(0.0) { result, next -> result * 60 + next }; return seconds.takeIf { it <= duration + 1 } }
private fun optional(text: String): String? = text.trim().takeIf { it.isNotBlank() && !Regex("(?i)^(buit|buida|cap|ningú|no es diu|no s'especifica|desconegut|sense data|vacío|vacía|ninguno|nadie|no se dice|no se especifica|desconocido|sin fecha|empty|none|nobody|no one|not specified|unknown|no date|-|n/a)\\.?$").matches(it) }
fun noteBlocks(meeting: JSONObject): List<Pair<Double, String>> {
    val notes = mutableListOf<Pair<Double, String>>()
    fun text(node: JSONObject): String = node.optString("text").ifBlank { node.optJSONArray("content")?.objects()?.joinToString(if (node.optString("type") == "paragraph") "" else " ") { text(it) }.orEmpty() }
    fun walk(node: JSONObject, insideItem: Boolean) {
        val type = node.optString("type")
        val attrs = node.optJSONObject("attrs")
        if (attrs?.has("t") == true && !attrs.isNull("t") && type in listOf("paragraph", "heading", "listItem", "taskItem") && !(insideItem && type in listOf("paragraph", "heading"))) notes += attrs.optDouble("t") to text(node).trim()
        node.optJSONArray("content")?.objects()?.forEach { walk(it, insideItem || type in listOf("listItem", "taskItem")) }
    }
    meeting.optJSONObject("notesDoc")?.let { walk(it, false) } ?: meeting.optJSONArray("notes")?.objects()?.forEach { notes += it.optDouble("t") to it.optString("text") }
    return notes.filter { it.second.isNotBlank() }
}
private fun splitDocument(document: String, limit: Int): List<String> {
    val chunks = mutableListOf<String>(); var current = StringBuilder()
    document.lineSequence().forEach { line ->
        val pieces = if (line.length > limit) line.chunked(limit) else listOf(line)
        pieces.forEach { piece -> if (current.length + piece.length > limit && current.isNotEmpty()) { chunks += current.toString(); current = StringBuilder() }; current.append(piece).append('\n') }
    }
    if (current.isNotEmpty()) chunks += current.toString()
    return chunks
}
private fun summarySchema(id: String, tones: List<String>): JSONObject {
    val string = obj("type" to "string")
    val minute = obj("type" to "string", "description" to "m:ss")
    fun objectOf(vararg properties: Pair<String, Any?>): JSONObject = obj("type" to "object", "properties" to obj(*properties), "required" to arr(properties.map { it.first }))
    fun arrayOf(item: JSONObject): JSONObject = obj("type" to "array", "items" to item)
    return when (id) {
        "title" -> objectOf("title" to string, "excerpt" to string)
        "tone" -> objectOf("tone" to obj("type" to "string", "enum" to arr(tones)), "reason" to string)
        "summary" -> objectOf("paragraphs" to arrayOf(string))
        "decisions" -> objectOf("decisions" to arrayOf(objectOf("text" to string, "minute" to minute)))
        "tasks" -> objectOf("tasks" to arrayOf(objectOf("task" to string, "owner" to string, "when" to string, "minute" to minute)))
        "parts" -> objectOf("parts" to arrayOf(objectOf("minute" to minute, "title" to string, "points" to arrayOf(string))))
        else -> error("Unknown part")
    }
}
