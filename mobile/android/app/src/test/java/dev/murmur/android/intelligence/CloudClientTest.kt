package dev.murmur.android.intelligence

import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class CloudClientTest {
    @Test fun ollamaHostedRequestsUseBearerAndNeverUnsupportedStructuredOutput() = runBlocking {
        var captured: JSONObject? = null
        val client = CloudClient { method, url, headers, body ->
            assertEquals("POST", method); assertEquals("https://ollama.com/api/chat", url)
            assertEquals("Bearer test-secret", headers["Authorization"])
            captured = body
            obj("message" to obj("content" to "{\"tasks\":[]}"))
        }
        val schema = obj("type" to "object", "properties" to obj("tasks" to obj("type" to "array")))
        assertEquals("{\"tasks\":[]}", client.ask("ollama", "gemma4:31b", "test-secret", "system", "private meeting", "tasks", schema, 800))
        assertFalse(captured!!.has("format")); assertFalse(captured!!.has("response_format"))
        assertTrue(captured!!.getJSONArray("messages").getJSONObject(1).getString("content").contains("schema"))
    }
    @Test fun openaiUsesResponsesAndStrictNestedSchema() = runBlocking {
        val client = CloudClient { _, url, _, body ->
            assertEquals("https://api.openai.com/v1/responses", url)
            val schema = body!!.getJSONObject("text").getJSONObject("format").getJSONObject("schema")
            assertFalse(schema.getBoolean("additionalProperties"))
            assertEquals("title", schema.getJSONArray("required").getString(0))
            obj("status" to "completed", "output" to arr(listOf(obj("type" to "message", "content" to arr(listOf(obj("type" to "output_text", "text" to "{\"title\":\"Hello\"}")))))))
        }
        val schema = obj("type" to "object", "properties" to obj("title" to obj("type" to "string")))
        assertEquals("{\"title\":\"Hello\"}", client.ask("openai", "gpt-model", "key", "system", "meeting", "title", schema, 300))
        assertFalse(schema.has("additionalProperties"))
    }
    @Test fun cloudCatalogsFilterIncompatibleModels() = runBlocking {
        val client = CloudClient { _, _, _, _ -> obj("models" to arr(listOf(
            obj("name" to "gemma4:31b", "capabilities" to arr(listOf("completion", "vision"))),
            obj("name" to "clef:27b", "capabilities" to arr(listOf("decision")))
        ))) }
        assertEquals(listOf("gemma4:31b"), client.listModels("ollama", "key").map { it.getString("id") })
    }
    @Test fun schemaValidatorRejectsMissingOrWrongTypedFields() {
        val schema = obj("type" to "object", "properties" to obj("tasks" to obj("type" to "array", "items" to obj("type" to "object", "properties" to obj("text" to obj("type" to "string")), "required" to arr(listOf("text"))))), "required" to arr(listOf("tasks")))
        assertTrue(validateJson(JSONObject("{\"tasks\":[{\"text\":\"Send invoice\"}]}"), schema))
        assertFalse(validateJson(JSONObject("{\"tasks\":[{\"text\":17}]}"), schema))
        assertFalse(validateJson(JSONObject("{}"), schema))
    }

    @Test fun anthropicKeepsTextOnlyAndUsesItsNativeSchema() = runBlocking {
        val client = CloudClient { _, url, headers, body ->
            assertEquals("https://api.anthropic.com/v1/messages", url)
            assertEquals("key", headers["x-api-key"])
            assertFalse(body!!.getJSONObject("output_config").getJSONObject("format").getJSONObject("schema").getBoolean("additionalProperties"))
            assertEquals("private transcript\n\n---\n", body.getJSONArray("messages").getJSONObject(0).getJSONArray("content").getJSONObject(0).getString("text"))
            obj("stop_reason" to "end_turn", "content" to arr(listOf(obj("type" to "thinking", "thinking" to "hidden"), obj("type" to "text", "text" to "{\"title\":\"Reunió\"}"))))
        }
        assertEquals("{\"title\":\"Reunió\"}", client.ask("anthropic", "claude-model", "key", "system", "private transcript", "title", titleSchema(), 300))
    }

    @Test fun geminiOmitsUnsupportedSchemaKeywordAndThinkingOutput() = runBlocking {
        val client = CloudClient { _, url, headers, body ->
            assertEquals("https://generativelanguage.googleapis.com/v1beta/models/gemini-model:generateContent", url)
            assertEquals("key", headers["x-goog-api-key"])
            assertFalse(body!!.getJSONObject("generationConfig").getJSONObject("responseJsonSchema").has("additionalProperties"))
            obj("candidates" to arr(listOf(obj("finishReason" to "STOP", "content" to obj("parts" to arr(listOf(obj("thought" to true, "text" to "hidden"), obj("text" to "{\"title\":\"Reunió\"}"))))))))
        }
        assertEquals("{\"title\":\"Reunió\"}", client.ask("gemini", "gemini-model", "key", "system", "meeting", "title", titleSchema(), 300))
    }

    @Test fun openrouterRequiresSchemaSupportAndKeepsRefusalOutOfMeeting() = runBlocking {
        val client = CloudClient { _, url, headers, body ->
            assertEquals("https://openrouter.ai/api/v1/chat/completions", url)
            assertEquals("Bearer key", headers["Authorization"])
            assertTrue(body!!.getJSONObject("provider").getBoolean("require_parameters"))
            assertTrue(body.getJSONObject("response_format").getJSONObject("json_schema").getBoolean("strict"))
            obj("choices" to arr(listOf(obj("finish_reason" to "stop", "message" to obj("refusal" to "blocked", "content" to "should not be stored")))))
        }
        try {
            client.ask("openrouter", "vendor/model", "key", "system", "meeting", "title", titleSchema(), 300)
            fail("A refusal must never become meeting content")
        } catch (error: IntelligenceException) { assertEquals("cloud_refused", error.code) }
    }

    private fun titleSchema() = obj("type" to "object", "properties" to obj("title" to obj("type" to "string")), "required" to arr(listOf("title")))
}
