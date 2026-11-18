package dev.murmur.android.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.murmur.android.UiState
import dev.murmur.android.R
import org.json.JSONObject

@OptIn(ExperimentalMaterial3Api::class)
@Composable fun MurmurApp(state: UiState, onAction: Dispatch) {
    MurmurTheme(state.settings) {
        val onboarding = !state.settings.optBoolean("onboardingComplete") && state.meetings.isEmpty() && !state.recording
        if (onboarding) {
            BackHandler {}
            Onboarding(state, onAction)
            return@MurmurTheme
        }
        var route by rememberSaveable { mutableStateOf("meetings") }
        var startDialog by rememberSaveable { mutableStateOf(false) }
        var settingsSection by rememberSaveable { mutableStateOf("general") }
        val snackbar = remember { SnackbarHostState() }
        LaunchedEffect(state.message) {
            state.message?.let { snackbar.showSnackbar(it); onAction("message.clear", JSONObject()) }
        }
        BackHandler(enabled = state.meeting != null || route != "meetings") {
            if (route != "meetings") route = "meetings" else onAction("meeting.close", JSONObject())
        }
        val inMeeting = route == "meetings" && state.meeting != null
        Scaffold(
            containerColor = LocalPalette.current.background,
            snackbarHost = { SnackbarHost(snackbar) },
            topBar = {
                TopAppBar(title = {
                    if (inMeeting) Text(state.meeting!!.optString("title"), maxLines = 1, style = MaterialTheme.typography.titleMedium)
                    else Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        MurmurLogo(Modifier.size(34.dp))
                        Text("murmur", fontFamily = FontFamily(Font(R.font.space_grotesk, FontWeight.Bold)), fontWeight = FontWeight.Bold, fontSize = 25.sp, letterSpacing = (-1).sp)
                    }
                }, navigationIcon = {
                    if (inMeeting) IconButton(onClick = { onAction("meeting.close", JSONObject()) }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, tr("back")) }
                }, actions = {
                    if (!inMeeting) IconButton(onClick = { onAction("import.file", JSONObject()) }) { Icon(Icons.Filled.FileOpen, tr("import_file")) }
                    if (state.recording && !inMeeting) IconButton(onClick = { onAction("record.open", JSONObject()); route = "meetings" }) {
                        Icon(Icons.Filled.FiberManualRecord, tr("recording"), tint = LocalPalette.current.recording)
                    }
                }, colors = TopAppBarDefaults.topAppBarColors(containerColor = LocalPalette.current.background))
            },
            bottomBar = {
                if (!inMeeting) NavigationBar(containerColor = LocalPalette.current.surface, tonalElevation = 0.dp) {
                    NavigationBarItem(selected = route == "meetings", onClick = { route = "meetings" }, icon = { Icon(Icons.Filled.Forum, null) }, label = { Text(tr("meetings")) })
                    NavigationBarItem(selected = route == "calendar", onClick = { route = "calendar"; onAction("calendar.refresh", JSONObject()) }, icon = { Icon(Icons.Filled.CalendarMonth, null) }, label = { Text(tr("calendar")) })
                    NavigationBarItem(selected = route == "settings", onClick = { route = "settings" }, icon = { Icon(Icons.Filled.Tune, null) }, label = { Text(tr("settings")) })
                }
            },
            floatingActionButton = {
                if (!inMeeting && route == "meetings") ExtendedFloatingActionButton(
                    onClick = { if (state.recording) onAction("record.open", JSONObject()) else startDialog = true },
                    icon = { Icon(if (state.recording) Icons.Filled.FiberManualRecord else Icons.Filled.Mic, null) },
                    text = { Text(if (state.recording) tr("recording") + " " + clock(state.seconds) else tr("new_meeting")) },
                    containerColor = LocalPalette.current.accent, contentColor = LocalPalette.current.onAccent
                )
            }
        ) { padding ->
            Column(Modifier.fillMaxSize().padding(padding)) {
                state.settings.optJSONArray("glossarySuggestion")?.strings()?.takeIf { it.isNotEmpty() }?.let { terms ->
                    Surface(color = LocalPalette.current.subtle, modifier = Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(horizontal = 20.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Hint(tr("glossary_suggestion"))
                            Text(terms.joinToString(", "), style = MaterialTheme.typography.bodyMedium)
                            Row {
                                TextButton(onClick = { onAction("glossary.accept", payload("terms" to org.json.JSONArray(terms))) }) { Text(tr("glossary_add")) }
                                TextButton(onClick = { onAction("glossary.dismiss", JSONObject()) }) { Text(tr("dismiss")) }
                            }
                        }
                    }
                }
                if (state.busy != null) {
                    Column(Modifier.fillMaxWidth().background(LocalPalette.current.subtle).padding(horizontal = 20.dp, vertical = 10.dp)) {
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Text(tr("busy_" + state.busy), Modifier.weight(1f), style = MaterialTheme.typography.labelMedium)
                            IconButton(onClick = {
                                if (state.busy == "download") onAction("model.cancel", payload("id" to state.settings.optString("downloadingModel")))
                                else if (state.busy == "voice") onAction("voice.cancel", JSONObject())
                                else onAction("process.cancel", JSONObject())
                            }, modifier = Modifier.size(32.dp)) { Icon(Icons.Filled.Close, tr("cancel"), Modifier.size(18.dp)) }
                        }
                        if (state.progress != null) LinearProgressIndicator(progress = { state.progress!!.coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth())
                        else LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    }
                }
                when {
                    inMeeting -> MeetingScreen(state, onAction)
                    route == "settings" -> SettingsScreen(state, settingsSection, { settingsSection = it }, onAction)
                    route == "calendar" -> CalendarScreen(state, onAction)
                    else -> LibraryScreen(state, onAction) { startDialog = true }
                }
            }
        }
        if (startDialog) StartDialog(state.settings, { startDialog = false }) { language, room ->
            onAction("record.start", payload("language" to language, "roomSpeakers" to room))
            startDialog = false; route = "meetings"
        }
        state.settings.optJSONArray("pendingImports")?.objects()?.firstOrNull()?.let { pending ->
            AlertDialog(onDismissRequest = {}, title = { Text(tr("import_choose_language")) }, text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    pending.optString("title").takeIf(String::isNotBlank)?.let { Text(it, style = MaterialTheme.typography.titleSmall) }
                    Hint(tr("import_language_hint"))
                    languageChoices().forEach { (language, label) ->
                        OutlinedButton(onClick = { onAction("import.language", payload("jobId" to pending.optString("job"), "language" to language)) }, modifier = Modifier.fillMaxWidth()) { Text(label) }
                    }
                }
            }, confirmButton = {}, dismissButton = { TextButton(onClick = { onAction("import.cancel", payload("jobId" to pending.optString("job"))) }) { Text(tr("cancel")) } })
        }
        if (route != "settings" || settingsSection != "connector") {
            state.settings.optJSONObject("mcpStatus")?.optJSONArray("pending")?.objects()?.firstOrNull()?.let { pending ->
                AlertDialog(onDismissRequest = {}, title = { Text(tr("connector_pending")) }, text = { Text(pending.optString("name")) },
                    confirmButton = { Button(onClick = { onAction("mcp.approve", payload("requestId" to pending.optString("id"))) }) { Text(tr("connector_approve")) } },
                    dismissButton = { TextButton(onClick = { onAction("mcp.reject", payload("requestId" to pending.optString("id"))) }) { Text(tr("connector_reject")) } })
            }
        }
        if (state.busy == "voice") VoiceProgress(state, onAction)
    }
}

@Composable private fun VoiceProgress(state: UiState, action: Dispatch) {
    val sentence = state.settings.textOrNull("voiceSentence") ?: tr("mobile:voice.sentence")
    val words = remember(sentence) { Regex("\\S+").findAll(sentence).toList() }
    val ratio = (state.progress ?: 0f).coerceIn(0f, 1f)
    val matched = (ratio * words.size + .001f).toInt().coerceIn(0, words.size)
    val color = LocalPalette.current.timestamp
    val highlighted = buildAnnotatedString {
        append(sentence)
        if (matched > 0) addStyle(SpanStyle(color = color, fontWeight = FontWeight.SemiBold), 0, words[matched - 1].range.last + 1)
    }
    AlertDialog(onDismissRequest = { action("voice.cancel", JSONObject()) }, icon = { Icon(Icons.Filled.RecordVoiceOver, null) },
        title = { Text(tr("voice")) }, text = {
            Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Hint(tr("mobile:voice.listening"))
                Text(highlighted, style = MaterialTheme.typography.titleMedium, color = LocalPalette.current.secondary)
                LinearProgressIndicator(progress = { ratio }, modifier = Modifier.fillMaxWidth())
                Text("$matched / ${words.size}", style = MaterialTheme.typography.labelMedium, color = LocalPalette.current.secondary)
                Hint(tr("mobile:voice.bio"))
            }
        }, confirmButton = {}, dismissButton = { TextButton(onClick = { action("voice.cancel", JSONObject()) }) { Text(tr("cancel")) } })
}

@Composable private fun StartDialog(settings: JSONObject, onDismiss: () -> Unit, onStart: (String, Boolean) -> Unit) {
    var language by remember { mutableStateOf(settings.optString("language", "ca-es")) }
    var room by remember { mutableStateOf(settings.optBoolean("roomSpeakers", true)) }
    AlertDialog(onDismissRequest = onDismiss, icon = { MurmurLogo(Modifier.size(56.dp)) }, title = { Text(tr("new_meeting")) }, text = {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            ChoiceRow(tr("language"), language, languageChoices()) { language = it }
            ToggleRow(tr("room_speakers"), room) { room = it }
            HorizontalDivider()
            Hint(tr("consent_recording"))
        }
    }, confirmButton = {
        Button(onClick = { onStart(language, room) }, colors = ButtonDefaults.buttonColors(containerColor = LocalPalette.current.accent, contentColor = LocalPalette.current.onAccent)) {
            Icon(Icons.Filled.Mic, null, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)); Text(tr("start_recording"))
        }
    }, dismissButton = { TextButton(onClick = onDismiss) { Text(tr("cancel")) } })
}

@Composable fun languageChoices(mixed: Boolean = true): List<Pair<String, String>> = buildList {
    add("ca" to tr("ca")); add("es" to tr("es")); if (mixed) add("ca-es" to tr("ca_es")); add("en" to tr("en"))
}
