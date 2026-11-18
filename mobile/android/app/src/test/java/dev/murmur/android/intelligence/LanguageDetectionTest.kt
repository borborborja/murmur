package dev.murmur.android.intelligence

import org.json.JSONArray
import org.junit.Assert.*
import org.junit.Test

class LanguageDetectionTest {
    private fun heard(vararg probabilities: Triple<Double, Double, Double>) = arr(probabilities.map { obj("ca" to it.first, "es" to it.second, "en" to it.third) })
    @Test fun mixedMeetingAndEnglishMajorityFollowDesktopThresholds() {
        assertEquals("ca-es", LanguageDetection.decide(heard(Triple(.8, .1, .1), Triple(.1, .8, .1))).getString("language"))
        assertEquals("ca-es", LanguageDetection.decide(heard(Triple(.45, .45, .1))).getString("language"))
        assertEquals("en", LanguageDetection.decide(heard(Triple(.1, .1, .8), Triple(.1, .1, .8), Triple(.8, .1, .1))).getString("language"))
    }
    @Test fun noiseAndMostlyUnknownWindowsNeverPretendToDetectLanguage() {
        assertTrue(LanguageDetection.decide(heard(Triple(.1, .1, .1))).isNull("language"))
        assertTrue(LanguageDetection.decide(heard(Triple(.8, .1, .1), Triple(.1, .1, .1), Triple(.1, .1, .1))).isNull("language"))
        assertTrue(LanguageDetection.decide(JSONArray()).isNull("language"))
    }
    @Test fun windowsSampleWholeFileAndDiscardShortTail() {
        val spans = arr((0 until 21).map { obj("start" to it * 12.0, "end" to it * 12.0 + if (it == 20) 2.0 else 10.0) })
        val windows = LanguageDetection.windows(spans)
        assertEquals(8, windows.length())
        assertEquals(0.0, windows.getJSONArray(0).getJSONObject(0).getDouble("start"), .01)
        assertTrue(windows.getJSONArray(7).getJSONObject(0).getDouble("start") > 180)
        assertTrue(windows.getJSONArray(7).getJSONObject(0).getDouble("start") < 240)
    }
}
