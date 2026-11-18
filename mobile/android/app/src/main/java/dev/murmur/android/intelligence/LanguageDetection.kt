package dev.murmur.android.intelligence

import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.floor
import kotlin.math.min

/** Same speech-window sampling and ca/es/en decision thresholds as desktop import.rs. */
object LanguageDetection {
    fun windows(spans: JSONArray): JSONArray {
        val groups = mutableListOf<MutableList<JSONObject>>()
        var seconds = 0.0
        spans.objects().forEach { span ->
            if (groups.isEmpty() || seconds >= 10.0) { groups += mutableListOf<JSONObject>(); seconds = 0.0 }
            val start = span.getDouble("start"); val end = min(span.getDouble("end"), start + 10.0)
            groups.last() += obj("start" to start, "end" to end); seconds += end - start
        }
        if (groups.size > 1 && groups.last().sumOf { it.getDouble("end") - it.getDouble("start") } < 5.0) groups.removeAt(groups.lastIndex)
        val chosen = if (groups.size <= 8) groups else (0 until 8).map { groups[floor(it * groups.size.toDouble() / 8).toInt()] }
        return arr(chosen.map(::arr))
    }
    fun decide(heard: JSONArray): JSONObject {
        val count = doubleArrayOf(0.0, 0.0, 0.0)
        heard.objects().forEach { window ->
            val probabilities = doubleArrayOf(window.optDouble("ca", 0.0), window.optDouble("es", 0.0), window.optDouble("en", 0.0))
            val sum = probabilities.sum()
            if (sum < 0.5) return@forEach
            val normalized = probabilities.map { it / sum }
            val sure = normalized.indexOfFirst { it >= 0.6 }
            if (sure >= 0) count[sure] += 1.0 else if (normalized[0] >= 0.3 && normalized[1] >= 0.3) { count[0] += 0.5; count[1] += 0.5 }
        }
        val total = count.sum()
        val language = if (total == 0.0 || total < heard.length() / 2.0) null
            else if (count[2] / total > 0.5) "en"
            else if (count[0] / total >= 0.15 && count[1] / total >= 0.15) "ca-es"
            else if (count[0] >= count[1]) "ca" else "es"
        return obj("language" to language, "windows" to heard.length(), "probabilities" to heard)
    }
}
