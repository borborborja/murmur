package dev.murmur.android

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class NativeEngineSmokeTest {
    @Test fun realRustDatabaseAndCatalog() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val engine = NativeEngine(context)
        val catalog = engine.call("model_catalog")
        File(context.filesDir, "test-model-catalog.json").writeText(catalog.toString())
        val ids = catalog.array("models").objects().map { it.getString("id") }
        assertTrue(ids.containsAll(listOf("transcription", "english", "punctuation", "whisper", "whisper-ca", "llm-mobile")))
        val id = "test-${UUID.randomUUID()}"
        val doc = json("type" to "doc", "content" to JSONArray().put(json("type" to "paragraph", "attrs" to json("t" to 2.0),
            "content" to JSONArray().put(json("type" to "text", "text" to "Decisió àgil", "marks" to JSONArray().put(json("type" to "bold")))))))
        val meeting = json("id" to id, "title" to "Àudio català", "createdAt" to 0, "duration" to 3, "status" to "done",
            "notesDoc" to doc, "utterances" to JSONArray().put(json("id" to "$id-1", "start" to 0, "end" to 3, "speaker" to 1, "text" to "Bon dia Girona", "edited" to true, "speakerLocked" to true)))
        try {
            engine.put(meeting)
            val saved = engine.get(id)!!
            assertEquals(canonical(doc), canonical(saved.getJSONObject("notesDoc")))
            assertTrue(saved.getJSONArray("utterances").getJSONObject(0).getBoolean("edited"))
            assertTrue(engine.search("girona").any { it.getString("meetingId") == id })
            assertTrue(engine.search("decisio").any { it.getString("meetingId") == id })
        } finally { engine.delete(id) }
    }

    private fun canonical(value: Any?): String = when (value) {
        is JSONObject -> value.keys().asSequence().toList().sorted().joinToString(prefix = "{", postfix = "}") { JSONObject.quote(it) + ":" + canonical(value.get(it)) }
        is JSONArray -> (0 until value.length()).joinToString(prefix = "[", postfix = "]") { canonical(value.get(it)) }
        is Number -> value.toDouble().toString()
        else -> value.toString()
    }

    @Test fun conformerProducesRealTranscriptAndCanUnloadAndResume() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val engine = NativeEngine(instrumentation.targetContext)
        assumeTrue("Install the real live models before this test", engine.call("model_catalog").array("models").objects().any { it.optString("id") == "transcription" && it.optBoolean("installed") })
        val bytes = instrumentation.context.assets.open("bon-dia.wav").use { it.readBytes() }
        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        buffer.position(44)
        val samples = FloatArray(buffer.remaining()/2) { buffer.short / 32768f }
        val id = engine.start("ca-es", true, "Native engine smoke")
        try {
            samples.toList().chunked(3200).forEach { engine.push(it.toFloatArray()) }
            val stopped = engine.stop().getJSONObject("meeting")
            assertTrue("The real Conformer should hear the fixture", stopped.array("utterances").objects().any { it.optString("text").isNotBlank() })
            val duration = stopped.getDouble("duration")
            engine.unload()
            assertEquals(id, engine.resume(id))
            samples.toList().chunked(3200).forEach { engine.push(it.toFloatArray()) }
            val continued = engine.stop().getJSONObject("meeting")
            assertTrue(continued.getDouble("duration") > duration)
            assertTrue(continued.array("parts").length() > 0)
        } finally { if (engine.call("status").optBoolean("recording")) runCatching { engine.call("discard") }; engine.unload(); engine.delete(id) }
    }

    @Test fun optionalPunctuationAndFinalDiarizationExecuteRealModels() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val engine = NativeEngine(context)
        val models = engine.call("model_catalog").array("models").objects()
        assumeTrue("Install real live and punctuation models", listOf("transcription", "punctuation").all { id -> models.any { it.optString("id") == id && it.optBoolean("installed") } })
        val wav = File(context.cacheDir, "diarization-smoke.wav")
        instrumentation.context.assets.open("bon-dia.wav").use { source -> wav.outputStream().use { source.copyTo(it) } }
        try {
            val speakers = engine.call("diarize", json("path" to wav.path, "spans" to JSONArray().put(json("start" to 0.0, "end" to 6.0)), "maxSpeakers" to 5))
            assertEquals(1, speakers.array("assignments").length())
            assertTrue(speakers.array("assignments").getJSONObject(0).getInt("speaker") >= 0)
            val result = engine.call("punctuate", json("texts" to JSONArray().put("avui parlarem del pressupost").put("després decidirem què farem"), "speakers" to JSONArray().put(0).put(0))).array("texts")
            assertEquals(2, result.length())
            assertTrue(result.getString(0).contains("pressupost"))
            assertTrue(result.getString(1).contains("decidirem"))
        } finally { engine.unload(); wav.delete() }
    }
}
