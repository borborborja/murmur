package dev.murmur.android

import org.json.JSONObject
import java.text.Normalizer
import java.util.Locale

/** The desktop's speaker-name edits and spoken introductions, independent of UI and storage. */
object MeetingEdits {
    /** Rewrite only human-facing cover fields, with Unicode word boundaries and literal names. */
    fun renameInCover(cover: JSONObject, from: String, to: String): JSONObject =
        renameInCover(cover, listOf(from to to))

    fun renameInCover(cover: JSONObject, renames: List<Pair<String, String>>): JSONObject {
        val result = JSONObject(cover.toString())
        val patterns = renames.filter { it.first.isNotEmpty() && it.first != it.second }.map { (old, replacement) ->
            Regex("(?<![\\p{L}\\p{N}])${Regex.escape(old)}(?![\\p{L}\\p{N}])") to replacement
        }
        fun fix(text: String) = patterns.fold(text) { current, (pattern, replacement) ->
            pattern.replace(current) { replacement }
        }
        fun field(objectValue: JSONObject, name: String) {
            if (objectValue.has(name) && !objectValue.isNull(name)) objectValue.put(name, fix(objectValue.getString(name)))
        }
        field(result, "excerpt")
        result.optJSONObject("tone")?.let { field(it, "reason") }
        result.optJSONArray("summary")?.let { paragraphs ->
            for (index in 0 until paragraphs.length()) paragraphs.put(index, fix(paragraphs.getString(index)))
        }
        result.optJSONArray("decisions")?.let { decisions ->
            for (index in 0 until decisions.length()) field(decisions.getJSONObject(index), "text")
        }
        result.optJSONArray("tasks")?.let { tasks ->
            for (index in 0 until tasks.length()) tasks.getJSONObject(index).let { field(it, "text"); field(it, "owner") }
        }
        result.optJSONArray("parts")?.let { parts ->
            for (index in 0 until parts.length()) parts.getJSONObject(index).let { part ->
                field(part, "title")
                part.optJSONArray("points")?.let { points ->
                    for (point in 0 until points.length()) points.put(point, fix(points.getString(point)))
                }
            }
        }
        return result
    }

    /** A name is recognised only after the wake word, as in the original intro.ts. */
    fun introducedName(
        text: String,
        guests: List<String> = emptyList(),
        known: List<String> = emptyList(),
        glossary: List<String> = emptyList(),
    ): String? = heardIntroduction(text)?.let { matchName(it, guests + known + glossary) }

    fun heardIntroduction(text: String): String? {
        val introduction = wakeWord.find(text) ?: return null
        var rest = introduction.groupValues[1].trim()
        val lower = rest.lowercase(Locale.ROOT)
        val lead = leads.firstOrNull { lower == it || lower.startsWith("$it ") || lower.startsWith("$it,") } ?: return null
        rest = rest.drop(lead.length).replace(Regex("^[\\s,]+"), "")
        val article = articles.firstOrNull { prefix ->
            rest.lowercase(Locale.ROOT).startsWith(if (prefix.endsWith("'") || prefix.endsWith("’")) prefix else "$prefix ")
        }
        if (article != null) rest = rest.drop(article.length).trimStart()
        val words = mutableListOf<String>()
        for (raw in rest.split(Regex("\\s+"))) {
            val word = raw.replace(Regex("^[^\\p{L}]+|[^\\p{L}'’-]+$"), "")
            if (word.isEmpty() || word.lowercase(Locale.ROOT) in stops) break
            words += word
            if (words.size == 3 || Regex("[.,;:!?]$").containsMatchIn(raw)) break
        }
        return words.takeIf { it.isNotEmpty() }?.joinToString(" ")
    }

    fun fold(value: String): String = Normalizer.normalize(value, Normalizer.Form.NFD)
        .replace(Regex("[\\u0300-\\u036f]"), "").lowercase(Locale.ROOT)
        .replace(Regex("[^a-z0-9' ]+"), " ").replace(Regex("\\s+"), " ").trim()

    fun matchName(heard: String, candidates: List<String>): String {
        val normalized = fold(heard)
        val first = normalized.substringBefore(' ')
        // LinkedHashMap retains the last spelling, matching JS new Map(...).values().
        val unique = linkedMapOf<String, String>()
        candidates.filter { it.trim().isNotEmpty() }.forEach { unique[fold(it)] = it.trim() }
        unique.values.firstOrNull { fold(it) == normalized }?.let { return it }
        val close = unique.values.filter { candidate ->
            val candidateFirst = fold(candidate).substringBefore(' ')
            candidateFirst == first || (minOf(candidateFirst.length, first.length) >= 4 && distance(candidateFirst, first) <= 1)
        }
        if (close.size == 1) return close[0]
        return heard.split(' ').joinToString(" ") { word -> word.take(1).uppercase(Locale.ROOT) + word.drop(1) }
    }

    /** New proper names introduced by a manual correction; the UI can offer a glossary entry. */
    fun newNames(before: String, after: String): List<String> {
        fun bare(raw: String) = raw.replace(Regex("^[^\\p{L}\\p{N}]+|[^\\p{L}\\p{N}]+$"), "")
            .replace(Regex("^\\p{L}{1,2}['’](?=\\p{L})"), "")
        val old = before.split(Regex("\\s+")).map(::bare).toSet()
        val words = after.split(Regex("\\s+")).filter(String::isNotBlank)
        val names = mutableListOf<String>()
        val run = mutableListOf<String>()
        fun close() { if (run.isNotEmpty()) names += run.joinToString(" "); run.clear() }
        words.forEachIndexed { index, raw ->
            val word = bare(raw)
            val startsSentence = index == 0 || Regex("[.!?…:]\\P{L}*$").containsMatchIn(words[index - 1])
            val isName = word.length >= 2 && word !in old &&
                (Regex("\\p{Lu}").containsMatchIn(word.drop(1)) || (!startsSentence && Regex("^\\p{Lu}").containsMatchIn(word)))
            if (!isName) close()
            else {
                if (run.isNotEmpty() && Regex("[^\\p{L}\\p{N}]$").containsMatchIn(words[index - 1])) close()
                run += word
            }
        }
        close()
        return names.distinct()
    }

    private fun distance(a: String, b: String): Int {
        val d = IntArray(b.length + 1) { it }
        for (i in 1..a.length) {
            var previous = d[0]
            d[0] = i
            for (j in 1..b.length) {
                val old = d[j]
                d[j] = minOf(d[j] + 1, d[j - 1] + 1, previous + if (a[i - 1] == b[j - 1]) 0 else 1)
                previous = old
            }
        }
        return d[b.length]
    }

    private val wakeWord = Regex("^\\W*m[uú]r\\s?m[uú]r\\b[\\s,.:;!¡¿?-]*(.*)$", RegexOption.IGNORE_CASE)
    private val leads = listOf("my name is", "em dic", "jo soc", "jo sóc", "me llamo", "mi nombre es", "yo soy", "i am", "i'm", "im", "soc", "sóc", "soy", "this is", "aquí", "aqui").sortedByDescending(String::length)
    private val articles = listOf("la", "el", "en", "na", "l'", "n'", "l’", "n’")
    private val stops = setOf("i", "y", "and", "que", "but", "però", "pero", "de", "del", "from", "amb", "con", "with", "per", "para", "for")
}
