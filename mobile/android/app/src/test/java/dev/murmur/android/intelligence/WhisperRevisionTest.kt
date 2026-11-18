package dev.murmur.android.intelligence

import org.junit.Assert.*
import org.junit.Test

class WhisperRevisionTest {
    @Test fun blankOrKnownHallucinatedTextNeverErasesTheLiveTranscript() {
        assertFalse(acceptedWhisperRevision(" \n"))
        assertFalse(acceptedWhisperRevision("GRÀCIES PER VEURE AQUEST VÍDEO"))
        assertFalse(acceptedWhisperRevision("SUBTÍTULOS REALIZADOS POR LA COMUNIDAD"))
        assertFalse(acceptedWhisperRevision("Thank you for watching."))
        assertTrue(acceptedWhisperRevision("La Marta enviarà el pressupost demà."))
    }
}
