package dev.murmur.android.intelligence

import org.json.JSONArray
import org.json.JSONObject

/** API adapters mirror the desktop, including native OpenAI Responses and Gemini schemas. */
class CloudClient(private val http: suspend (String, String, Map<String, String>, JSONObject?) -> JSONObject = { method, url, headers, body -> JsonHttp.request(method, url, headers, body) }) {
    companion object { val providers = listOf("anthropic", "openai", "openrouter", "ollama", "gemini") }

    private fun headers(provider: String, key: String): Map<String, String> = when (provider) {
        "anthropic" -> mapOf("x-api-key" to key, "anthropic-version" to "2023-06-01")
        "gemini" -> mapOf("x-goog-api-key" to key)
        "openrouter" -> mapOf("Authorization" to "Bearer $key", "X-Title" to "Murmur Android")
        "openai", "ollama" -> mapOf("Authorization" to "Bearer $key")
        else -> throw IllegalArgumentException("Unknown provider")
    }

    suspend fun listModels(provider: String, key: String): List<JSONObject> {
        val h = headers(provider, key)
        val body = when (provider) {
            "anthropic" -> http("GET", "https://api.anthropic.com/v1/models?limit=100", h, null)
            "openai" -> http("GET", "https://api.openai.com/v1/models", h, null)
            "openrouter" -> {
                http("GET", "https://openrouter.ai/api/v1/key", h, null)
                http("GET", "https://openrouter.ai/api/v1/models?supported_parameters=structured_outputs", h, null)
            }
            "ollama" -> http("GET", "https://ollama.com/api/tags", h, null)
            "gemini" -> http("GET", "https://generativelanguage.googleapis.com/v1beta/models?pageSize=1000", h, null)
            else -> error("Unknown provider")
        }
        val items = body.optJSONArray(if (provider in listOf("ollama", "gemini")) "models" else "data") ?: JSONArray()
        return items.objects().mapNotNull { item ->
            val id = when (provider) { "gemini" -> item.optString("name").removePrefix("models/"); "ollama" -> item.optString("name"); else -> item.optString("id") }
            if (id.isBlank() || id.endsWith(":batch")) return@mapNotNull null
            val unsuitable = listOf("audio", "realtime", "transcribe", "tts", "image", "search", "instruct", "embedding")
            if (provider == "openai" && (!id.startsWith("gpt-") && !id.startsWith("o3") && !id.startsWith("o4") || unsuitable.any(id::contains))) return@mapNotNull null
            if (provider == "anthropic" && id.startsWith("claude-3")) return@mapNotNull null
            if (provider == "gemini" && (!id.startsWith("gemini-") || unsuitable.any(id::contains) || !(item.optJSONArray("supportedGenerationMethods")?.strings()?.contains("generateContent") ?: false))) return@mapNotNull null
            if (provider == "ollama" && item.has("capabilities") && !item.getJSONArray("capabilities").strings().contains("completion")) return@mapNotNull null
            val modalities = item.optJSONObject("architecture")?.optJSONArray("output_modalities")?.strings()
            if (provider == "openrouter" && modalities != null && modalities != listOf("text")) return@mapNotNull null
            val name = item.optString("display_name").ifBlank { item.optString("displayName").ifBlank { item.optString("name").ifBlank { id } } }
            obj("id" to id, "name" to name, "group" to if (provider == "openrouter") id.substringBefore('/') else provider)
        }.sortedBy { it.optString("name").lowercase() }
    }

    /** Ollama's public catalog alone cannot validate a key. This explicit test sends no meeting. */
    suspend fun validateOllamaKey(key: String, model: String) {
        http("POST", "https://ollama.com/api/chat", headers("ollama", key), obj(
            "model" to model, "stream" to false,
            "messages" to arr(listOf(obj("role" to "user", "content" to "Reply OK."))),
            "options" to obj("num_predict" to 8)))
    }

    suspend fun ask(provider: String, model: String, key: String, system: String, document: String, prompt: String, schema: JSONObject?, maxTokens: Int): String {
        val strict = schema?.let { strictSchema(JSONObject(it.toString())) }
        val h = headers(provider, key)
        val source = "$document\n\n---\n$prompt"
        val maximum = maxOf(maxTokens, 4096).coerceAtMost(16000)
        val body: JSONObject
        val endpoint: String
        when (provider) {
            "anthropic" -> {
                endpoint = "https://api.anthropic.com/v1/messages"
                body = obj("model" to model, "max_tokens" to maximum, "system" to system,
                    "messages" to arr(listOf(obj("role" to "user", "content" to arr(listOf(
                        obj("type" to "text", "text" to "$document\n\n---\n", "cache_control" to obj("type" to "ephemeral")),
                        obj("type" to "text", "text" to prompt)))))))
                strict?.let { body.put("output_config", obj("format" to obj("type" to "json_schema", "schema" to it))) }
            }
            "openai" -> {
                endpoint = "https://api.openai.com/v1/responses"
                body = obj("model" to model, "instructions" to system, "max_output_tokens" to maximum,
                    "input" to arr(listOf(obj("role" to "user", "content" to source))))
                strict?.let { body.put("text", obj("format" to obj("type" to "json_schema", "name" to "answer", "strict" to true, "schema" to it))) }
            }
            "openrouter" -> {
                endpoint = "https://openrouter.ai/api/v1/chat/completions"
                body = obj("model" to model, "max_tokens" to maximum, "messages" to arr(listOf(obj("role" to "system", "content" to system), obj("role" to "user", "content" to source))))
                strict?.let {
                    body.put("response_format", obj("type" to "json_schema", "json_schema" to obj("name" to "answer", "strict" to true, "schema" to it)))
                    body.put("provider", obj("require_parameters" to true))
                }
            }
            "gemini" -> {
                require(model.matches(Regex("[A-Za-z0-9._-]+")))
                endpoint = "https://generativelanguage.googleapis.com/v1beta/models/$model:generateContent"
                val generation = obj("maxOutputTokens" to maximum)
                strict?.let { generation.put("responseMimeType", "application/json").put("responseJsonSchema", withoutAdditionalProperties(it)) }
                body = obj("systemInstruction" to obj("parts" to arr(listOf(obj("text" to system)))),
                    "contents" to arr(listOf(obj("role" to "user", "parts" to arr(listOf(obj("text" to source)))))), "generationConfig" to generation)
            }
            "ollama" -> {
                endpoint = "https://ollama.com/api/chat"
                // Hosted Ollama does not support `format`/structured outputs. Validate locally.
                val instruction = if (schema == null) prompt else "$prompt\nReturn only JSON matching this schema: $schema"
                body = obj("model" to model, "stream" to false,
                    "messages" to arr(listOf(obj("role" to "system", "content" to system), obj("role" to "user", "content" to "$document\n\n---\n$instruction"))),
                    "options" to obj("num_predict" to maximum, "temperature" to 0.1))
            }
            else -> error("Unknown provider")
        }
        val response = http("POST", endpoint, h, body)
        if (response.has("error")) throw IntelligenceException("cloud_unavailable")
        val result = when (provider) {
            "anthropic" -> {
                if (response.optString("stop_reason") == "refusal") throw IntelligenceException("cloud_refused")
                if (response.optString("stop_reason") == "max_tokens") throw IntelligenceException("cloud_too_long")
                response.optJSONArray("content")?.objects()?.filter { it.optString("type") == "text" }?.joinToString("") { it.optString("text") }.orEmpty()
            }
            "openai" -> {
                if (response.optString("status") == "incomplete") throw IntelligenceException("cloud_too_long")
                response.optJSONArray("output")?.objects()?.flatMap { it.optJSONArray("content")?.objects().orEmpty() }
                    ?.filter { it.optString("type") == "output_text" }?.joinToString("") { it.optString("text") }.orEmpty()
            }
            "openrouter" -> {
                val choice = response.optJSONArray("choices")?.optJSONObject(0) ?: throw IntelligenceException("cloud_empty")
                if (choice.optString("finish_reason") == "length") throw IntelligenceException("cloud_too_long")
                if (choice.optJSONObject("message")?.optString("refusal").orEmpty().isNotBlank()) throw IntelligenceException("cloud_refused")
                choice.optJSONObject("message")?.optString("content").orEmpty()
            }
            "gemini" -> {
                val candidate = response.optJSONArray("candidates")?.optJSONObject(0) ?: throw IntelligenceException("cloud_refused")
                if (candidate.optString("finishReason") == "MAX_TOKENS") throw IntelligenceException("cloud_too_long")
                candidate.optJSONObject("content")?.optJSONArray("parts")?.objects()?.filter { !it.optBoolean("thought") }?.joinToString("") { it.optString("text") }.orEmpty()
            }
            else -> response.optJSONObject("message")?.optString("content").orEmpty()
        }
        return result.trim().takeIf { it.isNotBlank() } ?: throw IntelligenceException("cloud_empty")
    }
}

/** A strict schema copy follows the desktop's provider compatibility rules. */
fun strictSchema(schema: JSONObject): JSONObject {
    schema.remove("minItems"); schema.remove("maxItems")
    if (schema.optString("type") == "object") {
        schema.put("additionalProperties", false)
        schema.optJSONObject("properties")?.let { schema.put("required", arr(it.keys().asSequence().toList())) }
    }
    schema.keys().asSequence().toList().forEach { key ->
        when (val value = schema.opt(key)) {
            is JSONObject -> strictSchema(value)
            is JSONArray -> value.objects().forEach(::strictSchema)
        }
    }
    return schema
}
private fun withoutAdditionalProperties(value: JSONObject): JSONObject {
    value.remove("additionalProperties")
    value.keys().asSequence().toList().forEach { key -> when (val child = value.opt(key)) { is JSONObject -> withoutAdditionalProperties(child); is JSONArray -> child.objects().forEach(::withoutAdditionalProperties) } }
    return value
}

/** Validation is mandatory even when a provider promises structured output. */
fun validateJson(value: Any?, schema: JSONObject): Boolean {
    val type = schema.optString("type")
    if (schema.has("enum") && !(0 until schema.getJSONArray("enum").length()).any { schema.getJSONArray("enum").opt(it) == value }) return false
    return when (type) {
        "object" -> value is JSONObject && schema.optJSONArray("required").orEmptyStrings().all { value.has(it) && !value.isNull(it) } &&
            (schema.optJSONObject("properties")?.let { properties -> properties.keys().asSequence().all { key -> !value.has(key) || validateJson(value.opt(key), properties.getJSONObject(key)) } } ?: true)
        "array" -> value is JSONArray && (schema.optJSONObject("items")?.let { item -> (0 until value.length()).all { validateJson(value.opt(it), item) } } ?: true)
        "string" -> value is String
        "integer" -> value is Number && value.toDouble() % 1.0 == 0.0
        "number" -> value is Number
        "boolean" -> value is Boolean
        else -> true
    }
}
private fun JSONArray?.orEmptyStrings(): List<String> = this?.strings().orEmpty()
