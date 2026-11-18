package dev.murmur.android.ui

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.murmur.android.UiState
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Product flows exercise real Compose controls without microphone/network side effects. */
@RunWith(AndroidJUnit4::class)
class UiWorkflowTest {
    @get:Rule val compose = createComposeRule()
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private fun text(key: String) = AppStrings(context, "en").text(key)
    private fun settings() = JSONObject().put("uiLanguage", "en").put("onboardingComplete", true)

    @Test fun firstLaunchDownloadsRequiredModelBeforeContinuing() {
        val base = JSONObject().put("id", "transcription").put("required", true).put("installed", false).put("bytes", 190000000)
        val state = mutableStateOf(UiState(settings = settings().put("onboardingComplete", false), models = listOf(base)))
        val actions = mutableListOf<Pair<String, JSONObject>>()
        compose.setContent { MurmurApp(state.value) { name, value -> actions += name to value } }
        compose.onNodeWithText(text("onboarding_continue")).performClick()
        compose.onNodeWithText(text("onboarding_continue")).assertIsNotEnabled()
        compose.onNodeWithText(text("download")).performClick()
        compose.runOnIdle {
            assertTrue(actions.any { it.first == "model.download" && it.second.optString("id") == "transcription" })
            state.value = state.value.copy(models = listOf(JSONObject(base.toString()).put("installed", true)))
        }
        compose.onNodeWithText(text("onboarding_continue")).assertIsEnabled().performClick()
        compose.onNodeWithText(text("onboarding_ai")).assertIsDisplayed()
        compose.onNodeWithText(text("onboarding_ready")).performClick()
        compose.runOnIdle { assertTrue(actions.any { it.first == "settings.update" && it.second.optJSONObject("patch")?.optBoolean("onboardingComplete") == true }) }
    }

    @Test fun directConnectorRequiresVisiblePhoneApproval() {
        var approved: JSONObject? = null
        val data = settings().put("mcpStatus", JSONObject("""{"running":true,"pending":[{"id":"request-a","name":"My computer"}],"clients":[]}"""))
        compose.setContent { MurmurTheme(data) { SettingsScreen(UiState(settings = data), "connector", {}, { name, value -> if (name == "mcp.approve") approved = value }) } }
        compose.onNodeWithText("My computer").assertIsDisplayed()
        compose.onNodeWithText(text("connector_approve")).performScrollTo().performClick()
        compose.runOnIdle { assertEquals("request-a", approved?.optString("requestId")) }
    }

    @Test fun correctedTranscriptIsSentWithTheStableUtteranceId() {
        val meeting = JSONObject("""{"id":"meeting-a","title":"Budget review","createdAt":0,"duration":10,"status":"done","speakerNames":{"0":"Marta"},"utterances":[{"id":"line-a","start":0,"end":5,"speaker":0,"text":"Budget is ten."}],"notesDoc":{"type":"doc","content":[]}}""")
        var correction: JSONObject? = null
        compose.setContent { MurmurTheme(settings()) { MeetingScreen(UiState(settings = settings(), meeting = meeting)) { action, payload -> if (action == "transcript.edit") correction = payload } } }
        compose.onNodeWithContentDescription(text("edit")).performClick()
        compose.onNode(hasSetTextAction()).performTextReplacement("Budget is twelve.")
        compose.onNodeWithText(text("save")).performClick()
        compose.runOnIdle { assertEquals("line-a", correction?.optString("id")); assertEquals("Budget is twelve.", correction?.optString("text")) }
    }

    @Test fun voiceEnrollmentShowsTheReadingGuideAndCanBeCancelled() {
        val sentence = text("mobile:voice.sentence")
        var cancelled = false
        val data = settings().put("voiceSentence", sentence)
        compose.setContent { MurmurApp(UiState(settings = data, busy = "voice", progress = .5f)) { action, _ -> if (action == "voice.cancel") cancelled = true } }
        compose.onNodeWithText(sentence).assertIsDisplayed()
        compose.onNodeWithText(text("mobile:voice.bio")).assertIsDisplayed()
        compose.onNodeWithText(text("cancel")).performClick()
        compose.runOnIdle { assertTrue(cancelled) }
    }
}
