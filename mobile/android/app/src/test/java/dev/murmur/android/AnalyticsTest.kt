package dev.murmur.android

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class AnalyticsTest {
    private val system = { JSONObject().put("isDebug", true).put("osVersion", "17").put("locale", "ca-ES")
        .put("appVersion", "0.16.0-android.1").put("appBuildNumber", "1")
        .put("deviceModel", "Private phone name").put("deviceId", "not-allowed").put("userId", "not-allowed") }

    @Test fun absentMalformedOrUnsupportedKeysAndOptOutNeverSend() {
        var sent = 0
        val transport: (String, String, JSONObject) -> Unit = { _, _, _ -> sent++ }
        for (key in listOf("", "A-EU-", "A-SH-1234567890", "https://evil.example", "A-EU-123456\nHost: evil")) {
            AnonymousAnalytics(key, { true }, system, transport).track("app_opened")
        }
        AnonymousAnalytics("A-EU-1234567890", { false }, system, transport).track("app_opened")
        assertEquals(0, sent)
    }

    @Test fun endpointAndPayloadFollowProtocolWithoutContentOrStableIdentifiers() {
        var url = ""; var appKey = ""; var payload: JSONObject? = null
        val client = AnonymousAnalytics("A-EU-1234567890", { true }, system, { endpoint, key, event -> url = endpoint; appKey = key; payload = event }, { 1_800_000_000_000 })
        client.track("recording_finished", JSONObject().put("language", "ca-es").put("minutes", AnonymousAnalytics.bucketMinutes(3700.0))
            .put("count", AnonymousAnalytics.bucketCount(9)).put("engine", "local").put("source", "mic")
            .put("title", "Private meeting").put("text", "Private transcript").put("format", "private document name")
            .put("audio", JSONObject().put("path", "/private/file")))
        assertEquals("https://eu.aptabase.com/api/v0/event", url)
        assertEquals("A-EU-1234567890", appKey)
        val data = payload!!
        assertEquals("recording_finished", data.getString("eventName"))
        assertTrue(data.getString("timestamp").endsWith("Z"))
        assertEquals("Android", data.getJSONObject("systemProps").getString("osName"))
        assertFalse(data.toString().contains("Private")); assertFalse(data.toString().contains("not-allowed"))
        assertEquals(setOf("language", "minutes", "count", "engine", "source"), data.getJSONObject("props").keys().asSequence().toSet())
        assertFalse(data.getJSONObject("systemProps").has("deviceModel"))
        assertNotNull(java.util.UUID.fromString(data.getString("sessionId")))
    }

    @Test fun unknownEventsAreDroppedAndRawNumbersCannotLeakExactDurations() {
        val sent = mutableListOf<JSONObject>()
        val client = AnonymousAnalytics("A-US-1234567890", { true }, system, { _, _, event -> sent += event })
        client.track("Private meeting title")
        client.track("shared", JSONObject().put("count", 12).put("minutes", 123.45).put("source", "Marta's microphone"))
        assertEquals(1, sent.size)
        assertEquals(0, sent[0].getJSONObject("props").length())
        assertEquals("https://us.aptabase.com/api/v0/event", AnonymousAnalytics.endpoint("A-US-1234567890"))
    }

    @Test fun sessionLivesOnlyInMemoryAndExpiresAfterIdleOrOptOut() {
        var now = 1000L; var enabled = true
        val ids = mutableListOf<String>()
        val client = AnonymousAnalytics("A-EU-1234567890", { enabled }, system, { _, _, event -> ids += event.getString("sessionId") }, { now })
        client.track("app_opened"); now += 1000; client.track("exported")
        assertEquals(ids[0], ids[1])
        now += 3_600_000; client.track("app_opened")
        assertNotEquals(ids[1], ids[2])
        enabled = false; client.track("translated"); enabled = true; client.track("app_opened")
        assertNotEquals(ids[2], ids[3])
        val secondProcess = AnonymousAnalytics("A-EU-1234567890", { true }, system, { _, _, event -> ids += event.getString("sessionId") }, { now })
        secondProcess.track("app_opened")
        assertNotEquals(ids[3], ids[4])
    }

    @Test fun durationAndCountBoundariesMatchCoarseDesktopBuckets() {
        assertEquals("<5", AnonymousAnalytics.bucketMinutes(299.0)); assertEquals("5-15", AnonymousAnalytics.bucketMinutes(300.0))
        assertEquals("15-30", AnonymousAnalytics.bucketMinutes(900.0)); assertEquals("30-60", AnonymousAnalytics.bucketMinutes(1800.0))
        assertEquals("60-120", AnonymousAnalytics.bucketMinutes(3600.0)); assertEquals(">120", AnonymousAnalytics.bucketMinutes(7200.0))
        assertEquals("<5", AnonymousAnalytics.bucketMinutes(Double.NaN))
        assertEquals("0", AnonymousAnalytics.bucketCount(-1)); assertEquals("1-5", AnonymousAnalytics.bucketCount(5))
        assertEquals("6-20", AnonymousAnalytics.bucketCount(6)); assertEquals("21-50", AnonymousAnalytics.bucketCount(50)); assertEquals(">50", AnonymousAnalytics.bucketCount(51))
    }
}
