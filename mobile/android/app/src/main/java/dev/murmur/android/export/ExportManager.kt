package dev.murmur.android.export

import android.content.Context
import dev.murmur.android.intelligence.*
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.text.DateFormat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** Pure exports take snapshots and never mutate the meeting or flatten its stored rich notes. */
class ExportManager(private val context: Context, private val catalogFactory: ((String) -> Catalog)? = null) {
    companion object { fun safeName(title: String): String = title.replace(Regex("[/\\\\?%*:|\"<>]"), "-").trim().ifBlank { "Murmur" } }
    private fun catalog(language: String): Catalog = catalogFactory?.invoke(language) ?: Catalog(context, language)
    private fun date(meeting: JSONObject, language: String): String = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT, Locale.forLanguageTag(language)).format(Date(meeting.optLong("createdAt")))

    fun markdown(meeting: JSONObject, content: String = "all", language: String = "ca"): String = markdownBody(meeting, content, resolveUiLanguage(language), false, true)
    private fun markdownBody(meeting: JSONObject, content: String, language: String, people: Boolean, title: Boolean): String {
        val catalog = catalog(language)
        val cover = meeting.optJSONObject("cover")
        val selected = if (cover == null) "all" else content
        val lines = mutableListOf<String>()
        if (title) lines += listOf("# ${meeting.optString("title")}", "")
        lines += listOf(date(meeting, language), "")
        if (selected != "transcript") {
            if (cover != null) {
                lines += listOf("## ${catalog.text("app.viewCover")}", "")
                if (cover.optString("excerpt").isNotBlank()) lines += listOf(cover.getString("excerpt"), "")
                cover.optJSONObject("tone")?.let { tone -> lines += listOf("**${catalog.text("tone")}:** ${catalog.text("tones.${tone.optString("label")}")}. ${tone.optString("reason")}", "") }
                val utterances = meeting.optJSONArray("utterances")?.objects().orEmpty()
                val totals = utterances.groupBy { it.optInt("speaker") }.mapValues { (_, turns) -> turns.sumOf { max(0.0, it.optDouble("end") - it.optDouble("start")) } }
                if (totals.size > 1) {
                    val total = totals.values.sum().coerceAtLeast(1.0)
                    lines += listOf("**${catalog.text("talk")}:** " + totals.entries.sortedByDescending { it.value }.joinToString(", ") { "${speakerName(meeting, it.key, language)} ${(it.value * 100 / total).roundToInt()}%" }, "")
                }
                fun section(key: String, values: List<String>) { if (values.isNotEmpty()) lines += listOf("### ${catalog.text(key)}", "") + values + "" }
                section("summary", cover.optJSONArray("summary")?.strings().orEmpty().map { "- $it" })
                section("decisions", cover.optJSONArray("decisions")?.objects().orEmpty().map { "- ${if (!it.isNull("t")) "[${clock(it.optDouble("t"))}] " else ""}${it.optString("text")}" })
                section("tasks", cover.optJSONArray("tasks")?.objects().orEmpty().map { task ->
                    val extra = listOf(task.optString("owner", ""), task.optString("when", "")).filter { it.isNotBlank() && it != "null" }.joinToString(", ")
                    "- [${if (task.optBoolean("done")) "x" else " "}] ${task.optString("text")}${if (extra.isBlank()) "" else " ($extra)"}"
                })
                val parts = cover.optJSONArray("parts")?.objects().orEmpty()
                if (parts.isNotEmpty()) { lines += listOf("### ${catalog.text("parts")}", ""); parts.forEach { part -> lines += "**[${clock(part.optDouble("t"))}] ${part.optString("title")}**"; lines += part.optJSONArray("points")?.strings().orEmpty().map { "- $it" }; lines += "" } }
            }
            val notes = meeting.optJSONObject("notesDoc")?.let { notesMarkdown(it, headingOffset = 2) }.orEmpty().ifBlank { meeting.optJSONArray("notes")?.objects().orEmpty().joinToString("\n\n") { "[${clock(it.optDouble("t"))}] ${it.optString("text")}" } }
            if (notes.isNotBlank()) lines += listOf("## ${catalog.text("export.notes")}", "", notes, "")
        }
        if (selected != "summary") {
            lines += listOf("## ${catalog.text("export.transcript")}", "")
            val parts = meeting.optJSONArray("parts")?.objects().orEmpty().toMutableList()
            var previousPartAt = 0.0
            var previousPartClock = meeting.optLong("createdAt")
            for (line in meeting.optJSONArray("utterances")?.objects().orEmpty()) {
                while (parts.isNotEmpty() && line.optDouble("start") >= parts.first().optDouble("at") - 0.01) {
                    val part = parts.removeAt(0)
                    val from = previousPartClock + ((part.optDouble("at") - previousPartAt).coerceAtLeast(0.0) * 1000).toLong()
                    val to = part.optLong("startedAt")
                    val formatter = DateFormat.getTimeInstance(DateFormat.SHORT, Locale.forLanguageTag(language))
                    val minutes = ((to - from).coerceAtLeast(0L) / 60_000.0).roundToInt()
                    val duration = if (minutes < 60) catalog.text("format.minutes", "m" to minutes)
                        else catalog.text("format.hoursMinutes", "h" to minutes / 60, "m" to (minutes % 60).toString().padStart(2, '0'))
                    lines += listOf("*${catalog.text("transcript.pause", "from" to formatter.format(Date(from)), "to" to formatter.format(Date(to)), "duration" to duration)}*", "")
                    previousPartAt = part.optDouble("at")
                    previousPartClock = to
                }
                val name = speakerName(meeting, line.optInt("speaker"), language).let { if (people) "[[$it]]" else it }
                lines += listOf("**[${clock(line.optDouble("start"))}] $name:** ${line.optString("text")}", "")
            }
        }
        return lines.joinToString("\n").replace(Regex("\n{3,}"), "\n\n").trimEnd() + "\n"
    }

    fun obsidian(meeting: JSONObject, people: Boolean = true, language: String = "ca"): String {
        fun yaml(text: String) = JSONObject.quote(text)
        val lines = mutableListOf("---", "date: ${SimpleDateFormat("yyyy-MM-dd'T'HH:mm", Locale.ROOT).format(Date(meeting.optLong("createdAt")))}", "duration: ${max(1, (meeting.optDouble("duration") / 60).roundToInt())}")
        val names = meeting.optJSONObject("speakerNames")?.let { values -> values.keys().asSequence().map { values.optString(it).trim() }.filter(String::isNotBlank).distinct().toList() }.orEmpty()
        if (names.isNotEmpty()) { lines += "participants:"; names.forEach { lines += "  - ${yaml(if (people) "[[$it]]" else it)}" } }
        val tags = meeting.optJSONArray("tags")?.strings().orEmpty().map { it.trim().replace(Regex("\\s+"), "-") }
        if (tags.isNotEmpty()) { lines += "tags:"; tags.forEach { lines += "  - ${yaml(it)}" } }
        if (meeting.optString("language").isNotBlank()) lines += "language: ${yaml(meeting.getString("language"))}"
        lines += "source: ${yaml(meeting.optJSONObject("device")?.optString("name", "Murmur") ?: "Murmur")}"
        lines += listOf("---", "")
        val body = markdownBody(meeting, "all", language, people, false)
        val screens = meeting.optJSONArray("screens")?.objects().orEmpty().sortedBy { it.optDouble("t") }
        val images = if (screens.isEmpty()) "" else "\n## ${catalog(language).text("export.screens")}\n\n" + screens.joinToString("\n\n") {
            "**[${clock(it.optDouble("t"))}]**\n![[${it.getString("path")}]]"
        } + "\n"
        return lines.joinToString("\n") + body + images
    }

    fun subtitles(meeting: JSONObject, format: String, speakers: Boolean = true, translated: Boolean = false, language: String = "ca"): String {
        require(format in listOf("srt", "vtt"))
        val lines = meeting.optJSONArray("utterances")?.objects().orEmpty().filter { it.optString("text").isNotBlank() }.sortedBy { it.optDouble("start") }
        val translation = if (translated) meeting.optJSONObject("translation")?.optJSONObject("texts") else null
        val several = lines.map { it.optInt("speaker") }.distinct().size > 1
        data class Cue(val start: Double, var end: Double, val text: String, val speaker: String?)
        val cues = mutableListOf<Cue>()
        var previous: Int? = null
        lines.forEach { line ->
            val text = translation?.optString(line.optString("id"))?.takeIf(String::isNotBlank) ?: line.optString("text")
            val speaker = line.optInt("speaker")
            val name = if (speakers && several && speaker != previous) speakerName(meeting, speaker, resolveUiLanguage(language)) else null
            previous = speaker
            val pieces = splitText(text.trim(), max(1, 84 - if (name == null) 0 else name.length + 2))
            val total = pieces.sumOf(String::length).coerceAtLeast(1)
            val span = max(line.optDouble("end") - line.optDouble("start"), pieces.size * 0.5)
            var at = line.optDouble("start")
            pieces.forEachIndexed { index, piece -> val length = span * piece.length / total; cues += Cue(at, at + length, piece, if (index == 0) name else null); at += length }
        }
        cues.forEachIndexed { index, cue -> cue.end = max(cue.start + 0.1, min(min(max(cue.end, cue.start + 1), cue.start + 6), cues.getOrNull(index + 1)?.start ?: Double.POSITIVE_INFINITY)) }
        val blocks = cues.mapIndexed { index, cue ->
            val time = "${stamp(cue.start, format)} --> ${stamp(cue.end, format)}"
            if (format == "vtt") "$time\n${cue.speaker?.let { "<v ${it.replace(Regex("[<>]"), "")}>" } ?: ""}${wrap(cue.text)}"
            else "${index + 1}\n$time\n${wrap(if (cue.speaker == null) cue.text else "${cue.speaker}: ${cue.text}")}"
        }
        return (if (format == "vtt") "WEBVTT\n\n" else "") + blocks.joinToString("\n\n") + "\n"
    }

    /** Minimal standards-compliant OOXML, including the notes' inline formatting. */
    fun docx(meeting: JSONObject, content: String = "all", language: String = "ca"): ByteArray {
        val paragraphs = documentParagraphs(meeting, content, language)
        val body = paragraphs.joinToString("") { p ->
            val props = if (p.heading > 0) "<w:pPr><w:pStyle w:val=\"Heading${min(3, p.heading)}\"/></w:pPr>" else ""
            "<w:p>$props${p.runs.joinToString("") { run ->
                val properties = listOf(if (run.bold) "<w:b/>" else "", if (run.italic) "<w:i/>" else "", if (run.underline) "<w:u w:val=\"single\"/>" else "", if (run.strike) "<w:strike/>" else "", if (run.code) "<w:rFonts w:ascii=\"Courier New\" w:hAnsi=\"Courier New\"/>" else "").joinToString("")
                val text = run.text.split('\n').joinToString("<w:br/>") { "<w:t xml:space=\"preserve\">${xml(it)}</w:t>" }
                "<w:r><w:rPr>$properties</w:rPr>$text</w:r>"
            }}</w:p>"
        }
        val output = ByteArrayOutputStream()
        ZipOutputStream(output).use { zip ->
            fun entry(name: String, data: String) { zip.putNextEntry(ZipEntry(name).apply { time = 0 }); zip.write(data.toByteArray(Charsets.UTF_8)); zip.closeEntry() }
            entry("[Content_Types].xml", """<?xml version="1.0" encoding="UTF-8"?><Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types"><Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/><Default Extension="xml" ContentType="application/xml"/><Override PartName="/word/document.xml" ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml"/><Override PartName="/word/styles.xml" ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.styles+xml"/></Types>""")
            entry("_rels/.rels", """<?xml version="1.0" encoding="UTF-8"?><Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"><Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="word/document.xml"/></Relationships>""")
            entry("word/_rels/document.xml.rels", """<?xml version="1.0" encoding="UTF-8"?><Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"><Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/styles" Target="styles.xml"/></Relationships>""")
            entry("word/styles.xml", """<?xml version="1.0" encoding="UTF-8"?><w:styles xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main"><w:docDefaults><w:rPrDefault><w:rPr><w:rFonts w:ascii="Calibri" w:hAnsi="Calibri"/><w:sz w:val="22"/></w:rPr></w:rPrDefault></w:docDefaults>${(1..3).joinToString("") { "<w:style w:type=\"paragraph\" w:styleId=\"Heading$it\"><w:name w:val=\"heading $it\"/><w:pPr><w:keepNext/><w:spacing w:before=\"240\" w:after=\"120\"/></w:pPr><w:rPr><w:b/><w:sz w:val=\"${36 - it * 4}\"/></w:rPr></w:style>" }}</w:styles>""")
            entry("word/document.xml", """<?xml version="1.0" encoding="UTF-8"?><w:document xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main"><w:body>$body<w:sectPr><w:pgSz w:w="11906" w:h="16838"/><w:pgMar w:top="1134" w:right="1134" w:bottom="1134" w:left="1134"/></w:sectPr></w:body></w:document>""")
        }
        return output.toByteArray()
    }

    fun html(meeting: JSONObject, content: String = "all", language: String = "ca"): String {
        val body = documentParagraphs(meeting, content, language).joinToString("\n") { paragraph ->
            val tag = if (paragraph.heading > 0) "h${paragraph.heading.coerceAtMost(3)}" else "p"
            "<$tag>" + paragraph.runs.joinToString("") { run ->
                var text = xml(run.text).replace("\n", "<br>")
                if (run.bold) text = "<strong>$text</strong>"
                if (run.italic) text = "<em>$text</em>"
                if (run.underline) text = "<u>$text</u>"
                if (run.strike) text = "<s>$text</s>"
                if (run.code) text = "<code>$text</code>"
                text
            } + "</$tag>"
        }
        return "<!doctype html><html lang=\"${xml(language)}\"><head><meta charset=\"utf-8\"><title>${xml(meeting.optString("title"))}</title><style>@page{size:A4;margin:20mm}body{font:11pt/1.55 sans-serif;color:black;background:white}h1{font-size:24pt}h2{margin-top:1.3em;font-size:17pt}h3{font-size:13pt}p{margin:.55em 0;white-space:pre-wrap}h1,h2,h3{page-break-after:avoid}code{font-family:monospace}</style></head><body>$body</body></html>"
    }

    private data class Run(val text: String, val bold: Boolean = false, val italic: Boolean = false, val underline: Boolean = false, val strike: Boolean = false, val code: Boolean = false)
    private data class Paragraph(val runs: List<Run>, val heading: Int = 0)
    private fun documentParagraphs(meeting: JSONObject, content: String, language: String): List<Paragraph> {
        // The Markdown section ordering and all content match the plain exports.
        val copy = JSONObject(meeting.toString()).apply { remove("notesDoc"); put("notes", org.json.JSONArray()) }
        val paragraphs = markdown(copy, content, language).lineSequence().filter(String::isNotBlank).map { line ->
            val heading = line.takeWhile { it == '#' }.length.takeIf { it in 1..6 && line.getOrNull(it) == ' ' } ?: 0
            Paragraph(listOf(Run(if (heading > 0) line.drop(heading + 1) else line.replace(Regex("\\*\\*(.*?)\\*\\*"), "$1"))), heading)
        }.toMutableList()
        if (content != "transcript" || meeting.optJSONObject("cover") == null) {
            val notes = meeting.optJSONObject("notesDoc")
            if (notes != null) {
                val rich = mutableListOf(Paragraph(listOf(Run(catalog(language).text("export.notes"))), 2))
                fun runs(node: JSONObject): List<Run> = node.optJSONArray("content")?.objects().orEmpty().map { part ->
                    val marks = part.optJSONArray("marks")?.objects().orEmpty().map { it.optString("type") }
                    Run(if (part.optString("type") == "hardBreak") "\n" else part.optString("text"), "bold" in marks, "italic" in marks, "underline" in marks, "strike" in marks, "code" in marks)
                }
                fun walk(node: JSONObject, depth: Int = 0, marker: String = "") {
                    val type = node.optString("type")
                    val attrs = node.optJSONObject("attrs")
                    when (type) {
                        "paragraph", "heading" -> {
                            val prefix = "  ".repeat((depth + (attrs?.optInt("indent") ?: 0)).coerceIn(0, 12)) + marker + if (attrs?.has("t") == true && !attrs.isNull("t")) "[${clock(attrs.optDouble("t"))}] " else ""
                            rich += Paragraph(listOf(Run(prefix)) + runs(node), if (type == "heading") min(3, (attrs?.optInt("level", 1) ?: 1) + 2) else 0)
                        }
                        "bulletList", "orderedList", "taskList" -> node.optJSONArray("content")?.objects().orEmpty().forEachIndexed { index, item ->
                            val itemMarker = when (type) { "orderedList" -> "${index + 1}. "; "taskList" -> "[${if (item.optJSONObject("attrs")?.optBoolean("checked") == true) "x" else " "}] "; else -> "• " }
                            item.optJSONArray("content")?.objects().orEmpty().forEachIndexed { childIndex, child -> walk(child, depth + 1, if (childIndex == 0) itemMarker else "") }
                        }
                        else -> node.optJSONArray("content")?.objects()?.forEach { walk(it, depth, marker) }
                    }
                }
                walk(notes)
                val transcriptTitle = catalog(language).text("export.transcript")
                val at = paragraphs.indexOfFirst { it.heading == 2 && it.runs.any { run -> run.text == transcriptTitle } }
                paragraphs.addAll(if (at >= 0) at else paragraphs.size, rich)
            }
        }
        return paragraphs
    }
}

private fun xml(value: String): String = value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;").replace("'", "&apos;")
private fun notesMarkdown(node: JSONObject, depth: Int = 0, headingOffset: Int = 0): String {
    fun inline(content: org.json.JSONArray?): String = content?.objects().orEmpty().joinToString("") { part ->
        if (part.optString("type") == "hardBreak") "  \n" else {
            var text = part.optString("text")
            part.optJSONArray("marks")?.objects()?.forEach { mark -> text = when (mark.optString("type")) { "bold" -> "**$text**"; "italic" -> "*$text*"; "underline" -> "<u>$text</u>"; "strike" -> "~~$text~~"; "code" -> "`$text`"; "link" -> "[$text](${mark.optJSONObject("attrs")?.optString("href").orEmpty()})"; else -> text } }
            text
        }
    }
    fun stamp(item: JSONObject): String = item.optJSONObject("attrs")?.takeIf { it.has("t") && !it.isNull("t") }?.let { "[${clock(it.optDouble("t"))}] " }.orEmpty()
    val children = node.optJSONArray("content")?.objects().orEmpty()
    return when (node.optString("type")) {
        "heading" -> "#".repeat(((node.optJSONObject("attrs")?.optInt("level", 1) ?: 1) + headingOffset).coerceAtMost(6)) + " " + inline(node.optJSONArray("content")) + "\n\n"
        "paragraph" -> if (children.isEmpty()) "" else "\u00a0".repeat(4 * (node.optJSONObject("attrs")?.optInt("indent") ?: 0).coerceIn(0, 6)) + stamp(node) + inline(node.optJSONArray("content")) + "\n\n"
        "blockquote" -> children.joinToString("") { notesMarkdown(it, 0, headingOffset) }.lineSequence().joinToString("\n") { if (it.isBlank()) it else "> $it" } + "\n"
        "bulletList", "orderedList", "taskList" -> children.mapIndexed { index, item ->
            val marker = when (node.optString("type")) { "orderedList" -> "${index + 1}."; "taskList" -> "- [${if (item.optJSONObject("attrs")?.optBoolean("checked") == true) "x" else " "}]"; else -> "-" }
            val content = item.optJSONArray("content")?.objects().orEmpty()
            "  ".repeat(depth) + marker + " " + stamp(item) + inline(content.firstOrNull()?.optJSONArray("content")) + "\n" + content.drop(1).joinToString("") { notesMarkdown(it, depth + 1, headingOffset) }
        }.joinToString("") + if (depth == 0) "\n" else ""
        else -> children.joinToString("") { notesMarkdown(it, depth, headingOffset) }
    }
}
internal fun splitText(text: String, maximum: Int = 84): List<String> {
    val words = text.trim().split(Regex("\\s+")).filter(String::isNotBlank)
    val pieces = mutableListOf<String>(); var left = words.joinToString(" ").length
    fun share(): Double = if (left == 0) 0.0 else left / ceil(left.toDouble() / maximum)
    var target = share(); val current = mutableListOf<String>()
    words.forEachIndexed { index, word ->
        current += word
        val next = words.getOrNull(index + 1) ?: return@forEachIndexed
        val piece = current.joinToString(" ")
        if (piece.length + 1 + next.length > maximum || (Regex("[.!?…]$").containsMatchIn(word) && piece.length >= target * 0.5) || (Regex("[,;:]$").containsMatchIn(word) && piece.length >= target * 0.8) || piece.length >= target) {
            pieces += piece; left -= piece.length + 1; target = share(); current.clear()
        }
    }
    if (current.isNotEmpty()) pieces += current.joinToString(" ")
    return pieces
}
private fun wrap(text: String): String {
    if (text.length <= 42) return text
    val words = text.split(' '); var best = text; var score = Int.MAX_VALUE
    for (i in 1 until words.size) { val first = words.take(i).joinToString(" "); val second = words.drop(i).joinToString(" "); val size = max(first.length, second.length); if (size < score) { score = size; best = "$first\n$second" } }
    return best
}
private fun stamp(seconds: Double, format: String): String { val ms = max(0, (seconds * 1000).roundToInt()); return "%02d:%02d:%02d%s%03d".format(Locale.ROOT, ms / 3_600_000, ms / 60_000 % 60, ms / 1000 % 60, if (format == "srt") "," else ".", ms % 1000) }
