package dev.murmur.android.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.murmur.android.UiState

/** First launch offers required offline models before the first microphone permission. */
@Composable internal fun Onboarding(state: UiState, action: Dispatch) {
    var step by rememberSaveable { mutableIntStateOf(0) }
    val base = state.models.firstOrNull { it.optString("id") == "transcription" }
    val update: (org.json.JSONObject) -> Unit = { action("settings.update", payload("patch" to it)) }
    fun finish() = update(payload("onboardingComplete" to true))
    Surface(Modifier.fillMaxSize(), color = LocalPalette.current.background) {
        Column(Modifier.fillMaxSize().safeDrawingPadding().widthIn(max = 600.dp).padding(24.dp)) {
            Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(20.dp)) {
                MurmurLogo(Modifier.size(if (step == 0) 100.dp else 64.dp).align(Alignment.CenterHorizontally))
                Text(tr(when(step) { 0 -> "onboarding_title"; 1 -> "onboarding_models"; else -> "onboarding_ai" }),
                    style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.Bold)
                Text(tr(when(step) { 0 -> "onboarding_body"; 1 -> "onboarding_models_body"; else -> "onboarding_ai_body" }),
                    style = MaterialTheme.typography.bodyLarge, color = LocalPalette.current.secondary)
                when (step) {
                    0 -> { Hint(tr("audio_restricted")); Hint(tr("consent_recording")) }
                    1 -> {
                        if (base == null) {
                            LinearProgressIndicator(Modifier.fillMaxWidth())
                            Hint(tr("onboarding_loading"))
                        } else ModelCard(base, state, action)
                    }
                    else -> {
                        IntelligenceSettings(state, action, update)
                        if (state.settings.optString("coverEngine", "local") == "local") {
                            state.models.firstOrNull { it.optString("id") == state.settings.optString("localModel", "llm-mobile") }
                                ?.let { ModelCard(it, state, action) }
                        }
                    }
                }
                state.message?.let { Hint(it) }
                Spacer(Modifier.height(12.dp))
            }
            HorizontalDivider()
            Row(Modifier.fillMaxWidth().padding(top = 20.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                TextButton(onClick = { finish() }) { Text(tr("onboarding_later")) }
                Button(onClick = { if (step < 2) step++ else finish() }, enabled = step != 1 || base?.optBoolean("installed") == true,
                    colors = ButtonDefaults.buttonColors(containerColor = LocalPalette.current.accent, contentColor = LocalPalette.current.onAccent)) {
                    if (step == 2) { Icon(Icons.Filled.CheckCircle, null, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)) }
                    Text(tr(if (step == 2) "onboarding_ready" else "onboarding_continue"))
                }
            }
        }
    }
}
