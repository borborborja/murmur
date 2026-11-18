package dev.murmur.android

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import uniffi.murmur_mobile.Murmur

/** Single owner of the real Rust engine. The caller schedules expensive operations off the UI. */
class NativeEngine(context: Context) {
    val dataDir = context.filesDir.resolve("data").apply { mkdirs() }
    val modelsDir = context.filesDir.resolve("models").apply { mkdirs() }
    private val engine: Murmur

    init {
        System.loadLibrary("c++_shared")
        System.loadLibrary("onnxruntime")
        System.loadLibrary("sherpa-onnx-c-api")
        engine = Murmur(modelsDir.absolutePath, dataDir.absolutePath)
    }

    @Synchronized
    fun call(action: String, payload: JSONObject = JSONObject()): JSONObject {
        if (action == "push") {
            val values = payload.getJSONArray("samples")
            return push(FloatArray(values.length()) { values.getDouble(it).toFloat() }, payload.optString("channel", "mic"))
        }
        return JSONObject(engine.androidCall(action, payload.toString()))
    }

    fun list(): List<JSONObject> = call("meetings").getJSONArray("meetings").objects()
    fun get(id: String): JSONObject? = call("get", JSONObject().put("id", id)).optJSONObject("meeting")
    fun put(meeting: JSONObject) { call("put", JSONObject().put("meeting", meeting)) }
    fun delete(id: String) { call("delete", JSONObject().put("id", id)) }
    fun search(query: String): List<JSONObject> = call("search", JSONObject().put("query", query)).getJSONArray("hits").objects()
    fun start(language: String, roomSpeakers: Boolean, title: String = "", mic: Boolean = true, system: Boolean = false): String =
        call("start", JSONObject().put("language", language).put("roomSpeakers", roomSpeakers).put("title", title).put("mic", mic).put("system", system)).getString("id")

    @Synchronized
    fun push(samples: FloatArray, channel: String = "mic"): JSONObject {
        val update = if (channel == "system") engine.pushSystem(samples.toList()) else engine.push(samples.toList())
        val finals = JSONArray()
        update.finals.forEach { line ->
            finals.put(JSONObject().put("id", line.id).put("start", line.start).put("end", line.end).put("speaker", line.speaker).put("text", line.text))
        }
        return JSONObject().put("finals", finals).put("partial", update.partial ?: JSONObject.NULL).put("seconds", update.seconds).put("level", update.level)
    }

    fun stop(extra: JSONObject = JSONObject()): JSONObject = call("stop", extra)
    fun stop(notesDoc: String, marks: List<Double>): JSONObject = stop(JSONObject().put("notesDoc", JSONObject(notesDoc)).put("marks", JSONArray(marks)))
    fun resume(id: String, roomSpeakers: Boolean = true): String = call("resume", JSONObject().put("id", id).put("roomSpeakers", roomSpeakers)).getString("id")
    fun unload() { call("unload") }
    fun speechSpans(wavPath: String): JSONArray = call("speech_spans", JSONObject().put("path", wavPath)).getJSONArray("spans")

    private fun JSONArray.objects(): List<JSONObject> = (0 until length()).map { getJSONObject(it) }
}
