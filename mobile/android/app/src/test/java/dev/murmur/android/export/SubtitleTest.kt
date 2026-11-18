package dev.murmur.android.export

import android.content.ContextWrapper
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class SubtitleTest {
    @Test fun translatedSubtitlesRetainSpeakerBoundariesAndOriginal() {
        val meeting = JSONObject("""{"speakerNames":{"0":"Marta","1":"Joan"},"utterances":[{"id":"u1","speaker":0,"start":0,"end":20,"text":"Bon dia a tothom."},{"id":"u2","speaker":1,"start":20,"end":25,"text":"Bon dia, Marta."}],"translation":{"texts":{"u1":"Good morning everyone."}}}""")
        val before = meeting.toString()
        val exporter = ExportManager(ContextWrapper(null))
        val srt = exporter.subtitles(meeting, "srt", speakers = true, translated = true)
        assertTrue(srt.contains("Marta: Good morning everyone.")); assertTrue(srt.contains("Joan: Bon dia, Marta."))
        assertTrue(srt.contains("00:00:00,000 --> 00:00:06,000"))
        assertEquals(before, meeting.toString())
        val vtt = exporter.subtitles(meeting, "vtt")
        assertTrue(vtt.startsWith("WEBVTT\n\n")); assertTrue(vtt.contains("<v Marta>"))
    }
    @Test fun longTextIsSplitByPunctuationAndBalancedForTwoLines() {
        val text = "Aquesta és una frase de prova força llarga, amb informació important sobre el pressupost i les dates. Cal enviar el document demà."
        val pieces = splitText(text)
        assertEquals(text, pieces.joinToString(" ")); assertTrue(pieces.size > 1); assertTrue(pieces.all { it.length <= 84 })
    }
}
