package dev.murmur.android

import org.json.JSONArray
import org.json.JSONObject

data class UiState(
    val meetings: List<JSONObject> = emptyList(),
    val meeting: JSONObject? = null,
    val recording: Boolean = false,
    val seconds: Double = 0.0,
    val level: Float = 0f,
    val partial: String = "",
    val busy: String? = null,
    val progress: Float? = null,
    val message: String? = null,
    val models: List<JSONObject> = emptyList(),
    val settings: JSONObject = JSONObject(),
    val searchResults: List<JSONObject> = emptyList(),
    val calendarEvents: List<JSONObject> = emptyList(),
    val playbackPosition: Double = 0.0,
    val playbackPlaying: Boolean = false,
    val playbackSpeed: Float = 1f,
    val paused: Boolean = false,
)

data class PlatformRequest(val action: String, val payload: JSONObject = JSONObject())

fun JSONArray.objects(): List<JSONObject> = (0 until length()).mapNotNull { optJSONObject(it) }
fun JSONArray.strings(): List<String> = (0 until length()).map { getString(it) }
fun JSONObject.copyJson(): JSONObject = JSONObject(toString())
fun JSONObject.array(key: String): JSONArray = optJSONArray(key) ?: JSONArray()
fun JSONObject.obj(key: String): JSONObject = optJSONObject(key) ?: JSONObject()
fun json(vararg pairs: Pair<String, Any?>): JSONObject = JSONObject().apply {
    pairs.forEach { (key, value) -> put(key, value ?: JSONObject.NULL) }
}
