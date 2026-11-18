package dev.murmur.android.intelligence

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.util.Locale

class DomainDocumentTest {
    @Test fun glossaryPromptKeepsOnlyPresentTermsAndRealGuestNames() {
        val meeting = obj("guests" to arr(listOf("Marta Puig", "anonymous@example.org", "Marta Puig")))
        assertEquals(listOf("Marta Puig", "Softcatalà", "WordPress"), coverTerms(meeting,
            "Cal configurar softcatala i WordPress abans de demà.", listOf("Softcatalà", "WordPress", "Amazon", "wordpress")))
    }

    @Test fun richNotesUseListItemTimestampWithoutDuplicatingItsParagraph() {
        val meeting = JSONObject("""{"notesDoc":{"type":"doc","content":[
            {"type":"heading","attrs":{"t":2},"content":[{"type":"text","text":"Decisions"}]},
            {"type":"taskList","content":[{"type":"taskItem","attrs":{"t":4},"content":[{"type":"paragraph","attrs":{"t":3},"content":[{"type":"text","text":"Enviar pressupost"}]}]}]},
            {"type":"paragraph","attrs":{"t":6},"content":[{"type":"text","text":""}]}
        ]}}""")
        assertEquals(listOf(2.0 to "Decisions", 4.0 to "Enviar pressupost"), noteBlocks(meeting))
        val legacy = JSONObject("""{"notes":[{"t":12,"text":"Nota antiga"}]}""")
        assertEquals(listOf(12.0 to "Nota antiga"), noteBlocks(legacy))
    }

    @Test fun generatedMinutesMustBeWithinTheMeeting() {
        assertEquals(65.0, parseMinute("[1:05]", 120.0)!!, 0.001)
        assertEquals(3601.0, parseMinute("1:00:01", 4000.0)!!, 0.001)
        assertNull(parseMinute("demà", 100.0))
        assertNull(parseMinute("-1:02", 100.0))
        assertNull(parseMinute("5:00", 100.0))
    }

    @Test fun systemLanguageMatchesInterfaceAndUnsupportedLocalesFallBackToCatalan() {
        val previous = Locale.getDefault()
        try {
            Locale.setDefault(Locale.forLanguageTag("es-ES"))
            assertEquals("es", resolveUiLanguage("system"))
            assertEquals("en", resolveUiLanguage("en"))
            Locale.setDefault(Locale.forLanguageTag("de-DE"))
            assertEquals("ca", resolveUiLanguage("system"))
        } finally { Locale.setDefault(previous) }
    }
}
