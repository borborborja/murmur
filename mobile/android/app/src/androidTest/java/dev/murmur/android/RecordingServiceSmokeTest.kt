package dev.murmur.android

import android.Manifest
import android.content.Intent
import android.os.Build
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class RecordingServiceSmokeTest {
    @Test fun microphoneServiceSurvivesLeavingActivityAndFinalizesAudio() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        grantRecordingPermissions()
        val scenario = ActivityScenario.launch(MainActivity::class.java)
        val runtime = AppRuntime.get(context)
        val previous = runtime.config.read()
        var id: String? = null
        try {
            val models = runBlocking { runtime.models.catalog() }
            assumeTrue("Install live models", models.any { it.optString("id") == "transcription" && it.optBoolean("installed") })
            runtime.config.update(recordingSettings(saveAudio = true))
            scenario.onActivity { activity ->
                ContextCompat.startForegroundService(activity, Intent(activity, RecordingService::class.java)
                    .putExtra("options", json("title" to "Service smoke", "language" to "ca-es", "mic" to true, "system" to false).toString()))
            }
            waitFor { runtime.state.value.recording && runtime.state.value.seconds > 1.0 }
            id = runtime.activeId!!
            val before = runtime.state.value.seconds
            scenario.moveToState(Lifecycle.State.CREATED)
            waitFor { runtime.state.value.seconds > before + 1.0 }
            assertTrue(File(runtime.dataDir, "capture/$id-mic.pcm").length() > 32_000)
            context.startService(Intent(context, RecordingService::class.java).setAction(RecordingService.STOP))
            waitFor { !runtime.state.value.recording && runtime.activeId == null }
            waitFor { housekeepingFinished(runtime, id!!) }
            val meeting = runBlocking { runtime.get(id!!) }!!
            assertEquals("done", meeting.getString("status"))
            assertTrue(meeting.getDouble("duration") > 2.0)
            assertTrue(File(meeting.getString("audioPath")).length() > 0)
        } finally {
            if (runtime.activeId != null) {
                context.startService(Intent(context, RecordingService::class.java).setAction(RecordingService.STOP))
                runCatching { waitFor { runtime.activeId == null } }
            }
            restoreSettings(runtime, previous)
            id?.let { runBlocking { runtime.native("delete", json("id" to it)) } }
            scenario.close()
        }
    }
    @Test fun audioRetentionDisabledDeletesOpusEvenWithoutAnyProcessingStages() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        grantRecordingPermissions()
        val scenario = ActivityScenario.launch(MainActivity::class.java)
        val runtime = AppRuntime.get(context)
        val previous = runtime.config.read()
        var id: String? = null
        try {
            val models = runBlocking { runtime.models.catalog() }
            assumeTrue("Install live models", models.any { it.optString("id") == "transcription" && it.optBoolean("installed") })
            runtime.config.update(recordingSettings(saveAudio = false))
            scenario.onActivity { activity ->
                ContextCompat.startForegroundService(activity, Intent(activity, RecordingService::class.java)
                    .putExtra("options", json("title" to "Private audio cleanup smoke", "language" to "ca-es", "mic" to true, "system" to false).toString()))
            }
            waitFor { runtime.state.value.recording && runtime.state.value.seconds > 1.0 }
            id = runtime.activeId!!
            val captured = runBlocking { runtime.get(id!!) }!!
            val audio = File(captured.getString("audioPath"))
            val pcm = File(runtime.dataDir, "capture/$id-mic.pcm")
            assertTrue("The real recorder must allocate an Opus destination before cleanup", audio.isFile)
            waitFor { pcm.length() > 32_000 }
            context.startService(Intent(context, RecordingService::class.java).setAction(RecordingService.STOP))
            waitFor { !runtime.state.value.recording && runtime.activeId == null }
            // This is automatic finalization: the test never calls process.run or removes audio.
            waitFor {
                val saved = runBlocking { runtime.get(id!!) }
                saved != null && saved.isNull("audioPath") && !audio.exists() && !pcm.exists() && housekeepingFinished(runtime, id!!)
            }
            val saved = runBlocking { runtime.get(id!!) }!!
            assertEquals("done", saved.getString("status"))
            assertEquals("Meeting metadata must survive privacy cleanup", id, saved.getString("id"))
            assertTrue(saved.getDouble("duration") > 1.0)
            assertTrue("No retained path may refer to deleted audio", saved.isNull("audioPath"))
            assertFalse("Opus must actually be removed, not only hidden in the UI", audio.exists())
            assertFalse("Temporary microphone PCM must also be removed", pcm.exists())
            assertFalse(File(runtime.dataDir, "active.json").exists())
            assertEquals(0, saved.array("pending").length())
        } finally {
            if (runtime.activeId != null) {
                context.startService(Intent(context, RecordingService::class.java).setAction(RecordingService.STOP))
                runCatching { waitFor { runtime.activeId == null } }
            }
            restoreSettings(runtime, previous)
            id?.let { runBlocking { runtime.native("delete", json("id" to it)) } }
            scenario.close()
        }
    }

    /** Prevent tests from exporting fixtures to any previously configured external destination. */
    private fun recordingSettings(saveAudio: Boolean): JSONObject = json(
        "saveAudio" to saveAudio, "verifySpeakers" to false, "finalPass" to false,
        "punctuate" to false, "summarize" to false, "askAfter" to false,
        "saveMarkdown" to false, "obsidianAuto" to false, "notionAuto" to false,
        "analytics" to false, "autoSync" to false, "pairedComputer" to JSONObject.NULL)

    private fun restoreSettings(runtime: AppRuntime, previous: JSONObject) {
        runtime.config.update(previous.copyJson()
            .put("autoSync", previous.optBoolean("autoSync", true))
            .put("pairedComputer", previous.opt("pairedComputer") ?: JSONObject.NULL))
    }

    private fun housekeepingFinished(runtime: AppRuntime, id: String): Boolean {
        val tasks = runCatching { JSONArray(File(runtime.dataDir, "jobs.json").readText()).objects() }.getOrDefault(emptyList())
        return tasks.any { it.optString("meetingId") == id && it.optString("status") == "done" }
    }

    private fun grantRecordingPermissions() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val packageName = instrumentation.targetContext.packageName
        instrumentation.uiAutomation.grantRuntimePermission(packageName, Manifest.permission.RECORD_AUDIO)
        if (Build.VERSION.SDK_INT >= 33) instrumentation.uiAutomation.grantRuntimePermission(packageName, Manifest.permission.POST_NOTIFICATIONS)
    }

    private fun waitFor(condition: () -> Boolean) {
        val end = System.currentTimeMillis() + 30_000
        while (System.currentTimeMillis() < end) { if (condition()) return; Thread.sleep(100) }
        throw AssertionError("Recording service did not reach expected state")
    }
}
