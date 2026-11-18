package dev.murmur.android

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class MeetingEditsTest {
    @Test fun wakeWordAndIntroductionsMatchDesktopForThreeLanguages() {
        assertEquals("Marta", MeetingEdits.introducedName("Murmur, sóc la marta i porto el pressupost"))
        assertEquals("Anna", MeetingEdits.introducedName("Murmur: jo sóc l’Anna, hola!"))
        assertEquals("Jordi Puig", MeetingEdits.introducedName("mur mur! em dic en jordi puig; gràcies"))
        assertEquals("María López", MeetingEdits.introducedName("¡Múrmur! Mi nombre es maría lópez y llevo el proyecto"))
        assertEquals("Alex", MeetingEdits.introducedName("Murmur, I'm alex and I manage the team."))
        assertNull(MeetingEdits.introducedName("Sóc la Marta"))
        assertNull(MeetingEdits.introducedName("La Marta diu: Murmur, sóc la Marta"))
        assertNull(MeetingEdits.introducedName("Murmur explica que sóc la Marta"))
        assertNull(MeetingEdits.introducedName("Murmur, sóc"))
        assertEquals("Un Dos Tres", MeetingEdits.introducedName("Murmur sóc un dos tres quatre"))
    }

    @Test fun candidatesUseUniqueFuzzyFirstNameAndDoNotGuessAmbiguousNames() {
        assertEquals("Xavi Vinaixa", MeetingEdits.introducedName("Murmur, sóc xabi", guests = listOf("Xavi Vinaixa")))
        assertEquals("Àngels Pons", MeetingEdits.introducedName("Murmur sóc angels", known = listOf("Àngels Pons")))
        assertEquals("Marta", MeetingEdits.introducedName("Murmur sóc marta", guests = listOf("Marta Puig", "Marta Ferrer")))
        assertEquals("Marta Puig", MeetingEdits.introducedName("Murmur sóc marta puig", guests = listOf("Marta Puig", "Marta Ferrer")))
        assertEquals("Jo", MeetingEdits.matchName("jo", listOf("Jon")))
        assertEquals("O'Neil", MeetingEdits.matchName("o'Neil", emptyList()))
    }

    @Test fun renameUpdatesHumanFacingCoverFieldsWithoutTouchingDataOrSimilarNames() {
        val cover = JSONObject("""{
          "basis":"Marta","writtenBy":"Marta","lang":"ca","generatedAt":12,
          "excerpt":"Marta i Martall, amb ÀngelsMarta i Marta2.",
          "tone":{"label":"constructive","reason":"Marta escolta."},
          "summary":["Marta obre.","(Marta) tanca."],
          "decisions":[{"text":"Marta ho aprova","t":12}],
          "tasks":[{"text":"Marta envia","owner":"Marta","when":"demà","done":true,"manual":true}],
          "parts":[{"title":"Marta explica","points":["Marta revisa"]}]
        }""")
        val updated = MeetingEdits.renameInCover(cover, "Marta", "M. Àngels $1")
        assertEquals("M. Àngels $1 i Martall, amb ÀngelsMarta i Marta2.", updated.getString("excerpt"))
        assertEquals("M. Àngels $1", updated.getJSONArray("tasks").getJSONObject(0).getString("owner"))
        assertEquals("M. Àngels $1 revisa", updated.getJSONArray("parts").getJSONObject(0).getJSONArray("points").getString(0))
        assertEquals("Marta", updated.getString("basis"))
        assertEquals("Marta", updated.getString("writtenBy"))
        assertTrue(updated.getJSONArray("tasks").getJSONObject(0).getBoolean("done"))
        assertEquals(cover.getString("excerpt"), "Marta i Martall, amb ÀngelsMarta i Marta2.")
    }

    @Test fun defaultSpeakerNamesAndRegexPunctuationAreLiteral() {
        val cover = JSONObject("""{"excerpt":"Parlant 1 i Parlant 10. A+B? AAB?","tasks":[{"text":"Parlant 1 acaba","owner":null}]}""")
        val fixed = MeetingEdits.renameInCover(cover, listOf("Parlant 1" to "Laia", "A+B?" to "X"))
        assertEquals("Laia i Parlant 10. X AAB?", fixed.getString("excerpt"))
        assertTrue(fixed.getJSONArray("tasks").getJSONObject(0).isNull("owner"))
    }

    @Test fun glossaryOffersOnlyNewNamesAndRetainsPunctuationBoundaries() {
        assertEquals(listOf("Guillem Puig", "WordPress", "AINA"), MeetingEdits.newNames(
            "Ho farà el guillem puig amb wordpress i aina.", "Ho farà el Guillem Puig amb WordPress i AINA."))
        assertEquals(emptyList<String>(), MeetingEdits.newNames("Bon dia.", "Bon dia. Hola amics."))
        assertEquals(listOf("Xavi", "Marta"), MeetingEdits.newNames("Ho fan ells.", "Ho fan Xavi, Marta."))
        assertEquals(emptyList<String>(), MeetingEdits.newNames("Ho farà la Marta", "Ho farà la Marta"))
    }
}
