package dev.murmur.android

import android.content.Context
import org.json.JSONObject
import java.io.File

/** Atomic private settings; credentials are deliberately stored elsewhere, in SecretStore. */
class AppSettings(context: Context) {
    private val file = File(context.filesDir, "android-settings.json")
    private val defaults = JSONObject("""{
      "uiLanguage":"ca","language":"ca-es","theme":"acid","mode":"system",
      "mic":true,"system":false,"micDevice":null,"saveAudio":false,
      "maxSpeakers":5,"threshold":0.7,"roomSpeakers":true,"verifySpeakers":true,
      "finalPass":true,"punctuate":false,"summarize":false,"askAfter":true,
      "fileSteps":{"verifySpeakers":true,"finalPass":true,"punctuate":false,"summarize":false},
      "coverEngine":"local","localModel":"llm-mobile","whisperProfile":"original",
      "cloudModels":{},"coverLanguage":"interface","prompts":{},"textZoom":1,
      "primaryPanel":"transcript","recordingIndicator":false,"compactNotes":true,
      "calendarEnabled":false,"calendarAlerts":true,"calendarIds":null,"calendarNoGuests":false,
      "saveMarkdown":false,"markdownContent":"all","markdownDir":null,
      "obsidianVault":null,"obsidianFolder":"Murmur","obsidianAuto":false,"obsidianPeople":false,
      "notionAuto":false,"notionPage":null,"subtitleSpeakers":true,"subtitleTranslated":false,
      "shareExpiry":"7d","checkUpdates":false,"updateManifest":null,"showWhatsNew":true,
      "analytics":false,"mcpEnabled":false,"keepPhoneAudio":true
    }""")
    fun read(): JSONObject = synchronized(LOCK) { defaults.copyJson().apply {
        runCatching { JSONObject(file.readText()) }.getOrNull()?.let { saved ->
            saved.keys().forEach { put(it, saved.get(it)) }
        }
    } }
    fun update(patch: JSONObject): JSONObject = synchronized(LOCK) { read().apply {
        patch.keys().forEach { key ->
            require(!key.contains("apiKey", true) && key != "key" && key != "token")
            put(key, patch.get(key))
        }
        file.parentFile?.mkdirs()
        val temp = File(file.parentFile, "${file.name}.tmp")
        temp.writeText(toString())
        check(temp.renameTo(file))
    } }
    companion object { private val LOCK = Any() }
}
