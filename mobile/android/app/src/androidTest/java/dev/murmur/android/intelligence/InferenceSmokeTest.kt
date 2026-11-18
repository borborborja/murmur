package dev.murmur.android.intelligence

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Real native execution when a model is installed; skipped explicitly otherwise. */
@RunWith(AndroidJUnit4::class)
class InferenceSmokeTest {
    @Test fun whisperWholeFileSpansAndLanguageDetection() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val target = instrumentation.targetContext
        val requested = InstrumentationRegistry.getArguments().getString("whisperModel")
        val model = requested?.let(::File) ?: File(target.filesDir, "models").walkTopDown().firstOrNull { it.isFile && it.extension == "bin" && it.name.startsWith("ggml-") }
        assumeTrue("Install a multilingual Whisper model or pass -e whisperModel <private absolute path>", model?.isFile == true)
        val wav = File(target.cacheDir, "inference-test/bon-dia.wav").apply { parentFile!!.mkdirs() }
        instrumentation.context.assets.open("bon-dia.wav").use { input -> wav.outputStream().use { output -> input.copyTo(output) } }
        try {
            val client = InferenceClient(target)
            val result = client.transcribe(model!!.path, wav.path, "ca")
            assertTrue("Native Whisper must return actual text", result.objects().any { it.optString("text").isNotBlank() })
            val spans = arr(listOf(obj("id" to "original-utterance", "start" to 0.0, "end" to 6.0)))
            val revised = client.transcribeSpans(model.path, wav.path, "ca", spans)
            assertEquals(1, revised.length()); assertEquals("original-utterance", revised.getJSONObject(0).getString("id"))
            assertEquals(0.0, revised.getJSONObject(0).getDouble("start"), .001)
            assertEquals(6.0, revised.getJSONObject(0).getDouble("end"), .001)
            assertTrue(revised.getJSONObject(0).getString("text").isNotBlank())
            val detected = client.detectLanguage(model.path, wav.path, spans)
            assertEquals(1, detected.getInt("windows"))
            assertTrue(detected.getJSONArray("probabilities").getJSONObject(0).getDouble("ca") > 0.0)
        } finally { wav.delete() }
    }

    @Test fun localLlmProducesSchemaConstrainedJson() = runBlocking {
        val target = InstrumentationRegistry.getInstrumentation().targetContext
        val requested = InstrumentationRegistry.getArguments().getString("llmModel")
        val model = requested?.let(::File) ?: File(target.filesDir, "models").walkTopDown().firstOrNull { it.isFile && it.extension == "gguf" }
        assumeTrue("Install a GGUF model or pass -e llmModel <private absolute path>", model?.isFile == true)
        val schema = """{"type":"object","properties":{"title":{"type":"string"}},"required":["title"],"additionalProperties":false}"""
        val text = InferenceClient(target).generate(model!!.path, "You write faithful meeting titles in Catalan.", "Marta: Enviarem el pressupost demà.", "Write a short title for this meeting. Return JSON.", 100, schema)
        assertTrue(JSONObject(text).getString("title").isNotBlank())
    }
}
