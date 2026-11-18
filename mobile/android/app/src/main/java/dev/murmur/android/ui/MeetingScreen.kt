package dev.murmur.android.ui

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.murmur.android.UiState
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable fun MeetingScreen(state: UiState, action: Dispatch) {
    val meeting = state.meeting ?: return
    val meetingId = meeting.optString("id")
    val recordingHere = state.recording && meeting.optString("status") == "recording"
    var tab by rememberSaveable(meetingId) { mutableStateOf(if (meeting.optJSONObject("cover") != null) "summary" else "transcript") }
    var more by remember { mutableStateOf(false) }
    var dialog by remember { mutableStateOf<String?>(null) }
    var editLine by remember { mutableStateOf<JSONObject?>(null) }
    var speakerLine by remember { mutableStateOf<JSONObject?>(null) }
    var translated by rememberSaveable(meetingId) { mutableStateOf(false) }
    val palette = LocalPalette.current
    val cover = meeting.optJSONObject("cover")
    Column(Modifier.fillMaxSize()) {
        if (recordingHere) RecordingControls(state, action) { dialog = "stop" }
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ScrollableTabRow(selectedTabIndex = listOf("summary", "transcript", "notes").indexOf(tab), Modifier.weight(1f), edgePadding = 0.dp, containerColor = palette.background, divider = {}) {
                listOf("summary", "transcript", "notes").forEach { id -> Tab(selected = tab == id, onClick = { tab = id }, text = { Text(tr(id)) }) }
            }
            IconButton(onClick = { dialog = "export" }) { Icon(Icons.Filled.IosShare, tr("export")) }
            Box {
                IconButton(onClick = { more = true }) { Icon(Icons.Filled.MoreVert, tr("more")) }
                DropdownMenu(more, { more = false }) {
                    listOf("rename", "resume", "finish_processing", "translate", "retranscribe", "share", "delete").forEach { id ->
                        DropdownMenuItem(text = { Text(tr(id)) }, onClick = {
                            more = false
                            when (id) {
                                "resume" -> action("record.resume", payload("id" to meetingId))
                                else -> dialog = id
                            }
                        }, enabled = id != "resume" || !state.recording)
                    }
                }
            }
        }
        if (tab == "notes") {
            Hint(tr("notes_pending"), Modifier.padding(horizontal = 20.dp, vertical = 10.dp))
            NotesEditor(meetingId, meeting.optJSONObject("notesDoc") ?: JSONObject("{\"type\":\"doc\",\"content\":[{\"type\":\"paragraph\"}]}"),
                if (recordingHere) state.seconds else meeting.optDouble("duration", 0.0), Modifier.weight(1f), zoom = state.settings.optDouble("textZoom", 1.0)) { doc -> action("notes.save", payload("doc" to doc)) }
        } else {
            LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
                item { MeetingHeading(meeting, action) { dialog = "tags" } }
                if ((meeting.optJSONArray("pending")?.length() ?: 0) > 0 && !state.recording && state.busy == null) item {
                    OutlinedButton(onClick = { dialog = "finish_processing" }, modifier = Modifier.fillMaxWidth()) { Icon(Icons.Filled.AutoAwesome, null, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)); Text(tr("finish_processing")) }
                }
                if (tab == "summary") {
                    if (cover == null) item {
                        Column(Modifier.fillMaxWidth().padding(vertical = 20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                            Text(tr("no_summary"), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
                            Hint(tr("no_summary_body"))
                            Button(onClick = { action("summary.generate", JSONObject()) }, enabled = state.busy == null) { Icon(Icons.Filled.AutoAwesome, null, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)); Text(tr("generate_summary")) }
                        }
                    } else {
                        if (meeting.optBoolean("coverStale")) item { Hint(tr("summary_stale")) }
                        item { SummaryHeader(cover, action) }
                        val summary = cover.optJSONArray("summary")?.strings().orEmpty()
                        if (summary.isNotEmpty()) item { SummarySection(tr("summary")) { summary.forEach { Text(it, lineHeight = 25.sp, style = MaterialTheme.typography.bodyLarge) } } }
                        val decisions = cover.array("decisions")
                        if (decisions.isNotEmpty()) item { SummarySection(tr("decisions")) { decisions.forEach { decision -> TimedText(decision.optString("text"), decision.optDouble("t", Double.NaN), action) } } }
                        val tasks = cover.array("tasks")
                        item { SummarySection(tr("tasks")) {
                            tasks.forEachIndexed { index, task -> TaskRow(task, index, action) }
                            TextButton(onClick = { dialog = "task_add" }) { Icon(Icons.Filled.Add, null, Modifier.size(18.dp)); Text(tr("add_task")) }
                        } }
                        val parts = cover.array("parts")
                        if (parts.isNotEmpty()) item { SummarySection(tr("parts")) { parts.forEach { part ->
                            TimedText(part.optString("title"), part.optDouble("t", Double.NaN), action)
                            part.optJSONArray("points")?.strings()?.forEach { Text(it, style = MaterialTheme.typography.bodyMedium, color = palette.secondary, modifier = Modifier.padding(start = 12.dp)) }
                        } } }
                    }
                } else {
                    val translation = meeting.optJSONObject("translation")
                    if (translation != null) item {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            FilterChip(!translated, { translated = false }, label = { Text(tr("original")) })
                            FilterChip(translated, { translated = true }, label = { Text(languageChoices(false).firstOrNull { it.first == translation.optString("lang") }?.second ?: tr("translation")) })
                        }
                    }
                    val utterances = meeting.array("utterances")
                    val parts = meeting.array("parts").sortedBy { it.optDouble("at") }
                    if (utterances.isEmpty()) item { Hint(if (recordingHere) tr("listening") else tr("mobile:meeting.empty"), Modifier.padding(vertical = 32.dp)) }
                    itemsIndexed(utterances, key = { _, line -> line.optString("id") }) { index, line ->
                        parts.forEachIndexed { partIndex, part ->
                            val at = part.optDouble("at")
                            if (line.optDouble("start") >= at && (index == 0 || utterances[index - 1].optDouble("start") < at)) {
                                val previous = parts.getOrNull(partIndex - 1)
                                val start = if (previous == null) meeting.optDouble("createdAt") + at * 1000
                                    else previous.optDouble("startedAt") + (at - previous.optDouble("at")) * 1000
                                PauseSeparator(start, part.optDouble("startedAt"))
                            }
                        }
                        val text = if (translated) translation?.optJSONObject("texts")?.optString(line.optString("id"))?.takeIf { it.isNotBlank() } ?: line.optString("text") else line.optString("text")
                        Column(verticalArrangement = Arrangement.spacedBy(9.dp)) {
                            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                SpeakerChip(meeting, line.optInt("speaker"), { speakerLine = line })
                                TextButton(onClick = { action("playback.seek", payload("time" to line.optDouble("start", 0.0))) }, contentPadding = PaddingValues(0.dp), modifier = Modifier.height(28.dp)) { Text(clock(line.optDouble("start", 0.0)), style = MaterialTheme.typography.labelMedium) }
                                Spacer(Modifier.weight(1f))
                                if (!translated) IconButton(onClick = { editLine = line }, modifier = Modifier.size(28.dp)) { Icon(Icons.Filled.Edit, tr("edit"), Modifier.size(15.dp), tint = palette.secondary) }
                            }
                            Text(text, style = MaterialTheme.typography.bodyLarge, lineHeight = 27.sp, fontSize = (16 * state.settings.optDouble("textZoom", 1.0)).sp)
                            val source = translation?.optJSONObject("sources")?.optString(line.optString("id"))
                            if (translated && source != null && source != line.optString("text")) Hint(tr("translation_stale"))
                            if (line.optBoolean("edited")) Hint(tr("edit"), Modifier.padding(top = 1.dp))
                        }
                    }
                    if (recordingHere && state.partial.isNotBlank()) item { Text(state.partial, color = palette.secondary, fontSize = 16.sp, lineHeight = 27.sp) }
                }
                val screens = meeting.array("screens")
                if (screens.isNotEmpty()) item { ScreenshotStrip(screens, action) }
            }
        }
        if (!meeting.isNull("audioPath") && meeting.optString("audioPath").isNotBlank()) PlayerBar(state, action)
    }
    editLine?.let { line -> EditDialog(tr("edit"), line.optString("text"), true, { editLine = null }) { action("transcript.edit", payload("id" to line.optString("id"), "text" to it)) } }
    speakerLine?.let { line -> SpeakerDialog(meeting, line, { speakerLine = null }, action) }
    when (dialog) {
        "rename" -> EditDialog(tr("title"), meeting.optString("title"), onDismiss = { dialog = null }) { action("meeting.rename", payload("title" to it)) }
        "tags" -> EditDialog(tr("tags"), meeting.optJSONArray("tags")?.strings()?.joinToString(", ").orEmpty(), onDismiss = { dialog = null }) { action("tags.set", payload("tags" to JSONArray(it.split(',').map(String::trim).filter(String::isNotBlank)))) }
        "delete" -> AlertDialog(onDismissRequest = { dialog = null }, title = { Text(tr("delete")) }, text = { Text(tr("delete_confirm")) }, confirmButton = { TextButton(onClick = { action("meeting.delete", payload("id" to meetingId)); dialog = null }) { Text(tr("delete"), color = MaterialTheme.colorScheme.error) } }, dismissButton = { TextButton(onClick = { dialog = null }) { Text(tr("cancel")) } })
        "stop" -> AlertDialog(onDismissRequest = { dialog = null }, title = { Text(tr("mobile:rec.stopConfirm")) }, confirmButton = { TextButton(onClick = { action("record.stop", JSONObject()); dialog = null }) { Text(tr("stop"), color = MaterialTheme.colorScheme.error) } }, dismissButton = { TextButton(onClick = { dialog = null }) { Text(tr("cancel")) } })
        "finish_processing" -> ProcessingDialog(state, { dialog = null }, action)
        "translate", "retranscribe" -> LanguageDialog(tr(if (dialog == "translate") "translate" else "retranscribe"), { dialog = null }) { language -> action(if (dialog == "translate") "translate" else "retranscribe", payload("language" to language)); dialog = null }
        "export" -> ExportSheet({ dialog = null }, action)
        "share" -> ShareSheet(meeting, { dialog = null }, action)
        "task_add" -> TaskDialog(null, { dialog = null }) { action("task.add", it); dialog = null }
    }
}

@Composable private fun PauseSeparator(start: Double, end: Double) {
    val language = LocalStrings.current.language
    val formatter = remember(language) { SimpleDateFormat("HH:mm", Locale.forLanguageTag(language)) }
    Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
        HorizontalDivider(Modifier.weight(1f))
        Icon(Icons.Filled.Coffee, null, Modifier.size(15.dp), tint = LocalPalette.current.secondary)
        Text(tr("pause") + " " + formatter.format(Date(start.toLong())) + " - " + formatter.format(Date(end.toLong())), style = MaterialTheme.typography.labelSmall, color = LocalPalette.current.secondary)
        HorizontalDivider(Modifier.weight(1f))
    }
}

@Composable private fun MeetingHeading(meeting: JSONObject, action: Dispatch, addTag: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(meeting.optString("title"), fontSize = 29.sp, lineHeight = 34.sp, letterSpacing = (-0.6).sp, fontWeight = FontWeight.Bold)
        Hint(SimpleDateFormat("EEEE d MMMM · HH:mm", Locale.forLanguageTag(LocalStrings.current.language)).format(Date(meeting.optDouble("createdAt", 0.0).toLong())) + "  ·  " + clock(meeting.optDouble("duration", 0.0)))
        LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            items(meeting.optJSONArray("tags")?.strings().orEmpty()) { tag -> AssistChip(onClick = addTag, label = { Text(tag) }) }
            item { IconButton(onClick = addTag, modifier = Modifier.size(34.dp)) { Icon(Icons.Filled.Label, tr("add_tag"), Modifier.size(18.dp)) } }
        }
    }
}

@Composable private fun RecordingControls(state: UiState, action: Dispatch, stop: () -> Unit) {
    val p = LocalPalette.current
    Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp).clip(RoundedCornerShape(18.dp)).background(p.color("recordingPill")).padding(14.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Box(Modifier.size(9.dp).clip(CircleShape).background(p.recording))
            Text(tr(if (state.paused) "paused" else "recording"), color = p.color("onRecordingPill"), fontWeight = FontWeight.SemiBold)
            Text(clock(state.seconds), color = p.color("onRecordingPill"), style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            Row(horizontalArrangement = Arrangement.spacedBy(2.dp), verticalAlignment = Alignment.CenterVertically) {
                repeat(12) { bar -> Box(Modifier.width(3.dp).height((8 + (bar % 4) * 3).dp).clip(RoundedCornerShape(1.dp)).background(if (state.level * 60 > bar) p.color("recordingLevel") else p.color("onRecordingPill").copy(alpha = .18f))) }
            }
            IconButton(onClick = stop) { Icon(Icons.Filled.StopCircle, tr("stop"), tint = p.color("onRecordingPill")) }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
            listOf(Triple("record.mark", "mark", Icons.Filled.BookmarkAdd), Triple("photo.take", "photo", Icons.Filled.PhotoCamera), Triple("screen.capture", "capture", Icons.Filled.Screenshot), Triple("record.pause", if (state.paused) "resume_recording" else "pause", if (state.paused) Icons.Filled.PlayArrow else Icons.Filled.Pause)).forEach { (id, label, icon) ->
                TextButton(onClick = { action(id, JSONObject()) }, colors = ButtonDefaults.textButtonColors(contentColor = p.color("onRecordingPill"))) { Icon(icon, null, Modifier.size(16.dp)); Spacer(Modifier.width(4.dp)); Text(tr(label), style = MaterialTheme.typography.labelSmall) }
            }
        }
    }
}

@Composable private fun SpeakerChip(meeting: JSONObject, speaker: Int, onClick: () -> Unit) {
    val palette = LocalPalette.current
    val name = meeting.optJSONObject("speakerNames")?.optString(speaker.toString())?.takeIf { it.isNotBlank() } ?: "${tr("speaker")} ${speaker + 1}"
    val you = name in setOf("Tu", "Tú", "You")
    val number = ((speaker % 4) + 1).toString()
    Surface(onClick = onClick, color = palette.color(if (you) "you" else "speaker${number}Tint"), contentColor = palette.color(if (you) "onYou" else "speaker${number}Text"), shape = RoundedCornerShape(6.dp)) {
        Text(name, Modifier.padding(horizontal = 8.dp, vertical = 4.dp), style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold)
    }
}

@Composable private fun SpeakerDialog(meeting: JSONObject, line: JSONObject, dismiss: () -> Unit, action: Dispatch) {
    val speaker = line.optInt("speaker")
    val names = meeting.optJSONObject("speakerNames") ?: JSONObject()
    var name by remember { mutableStateOf(names.optString(speaker.toString())) }
    val speakers = meeting.array("utterances").map { it.optInt("speaker") }.distinct().sorted()
    AlertDialog(onDismissRequest = dismiss, title = { Text(tr("speaker")) }, text = {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            OutlinedTextField(name, { name = it }, label = { Text(tr("speaker_name")) })
            val guests = meeting.optJSONArray("guests")?.strings().orEmpty()
            if (guests.isNotEmpty()) ChoiceRow(tr("calendar_guests"), name, guests.map { it to it }) { name = it }
            ChoiceRow(tr("assign_speaker"), speaker.toString(), speakers.map { it.toString() to (names.optString(it.toString()).takeIf(String::isNotBlank) ?: "${tr("speaker")} ${it + 1}") }) { target -> action("speaker.assign", payload("utteranceId" to line.optString("id"), "speaker" to target.toInt())); dismiss() }
            TextButton(onClick = {
                val next = (speakers.filter { (it >= 100) == (speaker >= 100) }.maxOrNull() ?: if (speaker >= 100) 99 else -1) + 1
                action("speaker.assign", payload("utteranceId" to line.optString("id"), "speaker" to next)); dismiss()
            }) { Icon(Icons.Filled.PersonAdd, null, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)); Text(tr("new_speaker")) }
            ChoiceRow(tr("merge_speaker"), "", speakers.filter { it != speaker }.map { it.toString() to (names.optString(it.toString()).takeIf(String::isNotBlank) ?: "${tr("speaker")} ${it + 1}") }) { target -> action("speaker.merge", payload("from" to speaker, "to" to target.toInt())); dismiss() }
        }
    }, confirmButton = { TextButton(onClick = { action("speaker.rename", payload("speaker" to speaker, "name" to name)); dismiss() }) { Text(tr("save")) } }, dismissButton = { TextButton(onClick = dismiss) { Text(tr("cancel")) } })
}

@Composable private fun SummaryHeader(cover: JSONObject, action: Dispatch) {
    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        val excerpt = cover.optString("excerpt")
        if (excerpt.isNotBlank()) Text(excerpt, style = MaterialTheme.typography.titleMedium, lineHeight = 26.sp)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Hint(cover.optString("writtenBy").takeIf { it.isNotBlank() } ?: tr("local"), Modifier.weight(1f))
            TextButton(onClick = { action("summary.generate", JSONObject()) }) { Text(tr("regenerate")) }
        }
        val tone = cover.optJSONObject("tone")
        if (tone != null) Surface(color = LocalPalette.current.subtle, shape = RoundedCornerShape(12.dp)) {
            Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                Text(tr("tone"), style = MaterialTheme.typography.labelSmall, color = LocalPalette.current.secondary)
                Text(tr("cover:tones." + tone.optString("label")), fontWeight = FontWeight.SemiBold)
                Text(tone.optString("reason"), style = MaterialTheme.typography.bodyMedium, lineHeight = 22.sp)
            }
        }
    }
}

@Composable private fun SummarySection(title: String, content: @Composable ColumnScope.() -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) { SectionTitle(title); content() }
}

@Composable private fun TimedText(text: String, time: Double, action: Dispatch) {
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.Top) {
        if (!time.isNaN()) Text(clock(time), color = LocalPalette.current.timestamp, style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(top = 4.dp).clickable { action("playback.seek", payload("time" to time)) })
        Text(text, style = MaterialTheme.typography.bodyLarge, lineHeight = 25.sp)
    }
}

@Composable private fun TaskRow(task: JSONObject, index: Int, action: Dispatch) {
    var edit by remember { mutableStateOf(false) }
    Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        Checkbox(task.optBoolean("done"), { action("task.toggle", payload("index" to index, "done" to it)) }, Modifier.size(36.dp))
        Column(Modifier.weight(1f).clickable { edit = true }.padding(top = 5.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(task.optString("text"), style = MaterialTheme.typography.bodyMedium, lineHeight = 23.sp)
            val meta = listOf("owner", "when").mapNotNull { task.optString(it).takeIf { v -> v.isNotBlank() && v != "null" } }
            if (meta.isNotEmpty()) Hint(meta.joinToString(" · "))
        }
        IconButton(onClick = { action("task.delete", payload("index" to index)) }, modifier = Modifier.size(30.dp)) { Icon(Icons.Filled.Close, tr("delete"), Modifier.size(15.dp)) }
    }
    if (edit) TaskDialog(task, { edit = false }) { fields -> fields.put("index", index); action("task.save", fields); edit = false }
}

@Composable private fun TaskDialog(task: JSONObject?, dismiss: () -> Unit, save: (JSONObject) -> Unit) {
    var text by remember { mutableStateOf(task?.optString("text").orEmpty()) }
    var owner by remember { mutableStateOf(task?.optString("owner")?.takeIf { it != "null" }.orEmpty()) }
    var date by remember { mutableStateOf(task?.optString("when")?.takeIf { it != "null" }.orEmpty()) }
    AlertDialog(onDismissRequest = dismiss, title = { Text(tr("task")) }, text = { Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(text, { text = it }, label = { Text(tr("task")) }, minLines = 2)
        OutlinedTextField(owner, { owner = it }, label = { Text(tr("owner")) })
        OutlinedTextField(date, { date = it }, label = { Text(tr("due")) })
    } }, confirmButton = { TextButton(onClick = { save(payload("text" to text, "owner" to owner.takeIf(String::isNotBlank), "when" to date.takeIf(String::isNotBlank))) }, enabled = text.isNotBlank()) { Text(tr("save")) } }, dismissButton = { TextButton(onClick = dismiss) { Text(tr("cancel")) } })
}

@Composable private fun ScreenshotStrip(screens: List<JSONObject>, action: Dispatch) {
    val context = LocalContext.current
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        SectionTitle(tr("screenshots"))
        LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) { items(screens) { screen ->
            val path = screen.optString("path")
            val bitmap = remember(path) { val file = if (path.startsWith('/')) File(path) else File(context.filesDir, "data/$path"); runCatching { BitmapFactory.decodeFile(file.path, BitmapFactory.Options().apply { inSampleSize = 2 })?.asImageBitmap() }.getOrNull() }
            Column { if (bitmap != null) Image(bitmap, tr("screenshots"), Modifier.width(180.dp).height(110.dp).clip(RoundedCornerShape(10.dp)).clickable { action("screen.open", payload("path" to path)) }); TextButton(onClick = { action("playback.seek", payload("time" to screen.optDouble("t"))) }) { Text(clock(screen.optDouble("t"))) } }
        } }
    }
}

@Composable private fun PlayerBar(state: UiState, action: Dispatch) {
    val duration = state.meeting?.optDouble("duration", 0.0)?.toFloat()?.coerceAtLeast(1f) ?: 1f
    var position by remember { mutableFloatStateOf(state.playbackPosition.toFloat()) }
    var dragging by remember { mutableStateOf(false) }
    LaunchedEffect(state.playbackPosition) { if (!dragging) position = state.playbackPosition.toFloat() }
    Row(Modifier.fillMaxWidth().background(LocalPalette.current.surface).padding(horizontal = 8.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = { action("playback.play", JSONObject()) }) { Icon(if (state.playbackPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow, tr(if (state.playbackPlaying) "pause" else "play")) }
        Text(clock(position.toDouble()), style = MaterialTheme.typography.labelSmall)
        Slider(position.coerceIn(0f, duration), { dragging = true; position = it }, Modifier.weight(1f), onValueChangeFinished = { dragging = false; action("playback.seek", payload("time" to position.toDouble())) }, valueRange = 0f..duration)
        var expanded by remember { mutableStateOf(false) }
        Box { TextButton(onClick = { expanded = true }) { Text("${state.playbackSpeed}×") }; DropdownMenu(expanded, { expanded = false }) { listOf(1f, 1.25f, 1.5f, 2f).forEach { speed -> DropdownMenuItem(text = { Text("${speed}×") }, onClick = { action("playback.speed", payload("value" to speed)); expanded = false }) } } }
    }
}

@Composable private fun ProcessingDialog(state: UiState, dismiss: () -> Unit, action: Dispatch) {
    var speakers by remember { mutableStateOf(state.settings.optBoolean("verifySpeakers", true)) }
    var text by remember { mutableStateOf(state.settings.optBoolean("finalPass", true)) }
    var punctuation by remember { mutableStateOf(state.settings.optBoolean("punctuate", false)) }
    var cover by remember { mutableStateOf(state.settings.optBoolean("summarize", false)) }
    AlertDialog(onDismissRequest = dismiss, title = { Text(tr("process_title")) }, text = { Column {
        ToggleRow(tr("verify_speakers"), speakers) { speakers = it }; ToggleRow(tr("whisper"), text) { text = it }
        ToggleRow(tr("punctuate"), punctuation) { punctuation = it }; ToggleRow(tr("generate_summary"), cover) { cover = it }
    } }, confirmButton = { TextButton(onClick = { action("process.run", payload("speakers" to speakers, "text" to text, "punctuation" to punctuation, "cover" to cover)); dismiss() }) { Text(tr("run")) } }, dismissButton = { TextButton(onClick = dismiss) { Text(tr("cancel")) } })
}

@Composable private fun LanguageDialog(title: String, dismiss: () -> Unit, choose: (String) -> Unit) {
    AlertDialog(
        onDismissRequest = dismiss,
        title = { Text(title) },
        text = {
            Column {
                languageChoices(false).forEach { (id, label) ->
                    TextButton(onClick = { choose(id) }, modifier = Modifier.fillMaxWidth()) { Text(label) }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = dismiss) { Text(tr("cancel")) } }
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable private fun ExportSheet(dismiss: () -> Unit, action: Dispatch) {
    var content by remember { mutableStateOf("all") }
    ModalBottomSheet(onDismissRequest = dismiss, containerColor = LocalPalette.current.background) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp).navigationBarsPadding(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(tr("export"), style = MaterialTheme.typography.headlineSmall)
            ChoiceRow(tr("export"), content, listOf("all" to tr("all_content"), "summary" to tr("summary_notes"), "transcript" to tr("transcript"))) { content = it }
            listOf("md" to "Markdown", "docx" to "Word (.docx)", "pdf" to "PDF", "srt" to "SRT", "vtt" to "WebVTT", "audio" to tr("audio"), "screens" to tr("screenshots"), "obsidian" to "Obsidian", "notion" to "Notion").forEach { (format, title) -> TextButton(onClick = { action("export", payload("format" to format, "content" to content)); dismiss() }, Modifier.fillMaxWidth()) { Text(title) } }
            Spacer(Modifier.height(16.dp))
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable private fun ShareSheet(meeting: JSONObject, dismiss: () -> Unit, action: Dispatch) {
    var parts by remember { mutableStateOf(setOf("cover", "notes", "transcript", "screens")) }
    var expiry by remember { mutableStateOf("7d") }
    var password by remember { mutableStateOf("") }
    ModalBottomSheet(onDismissRequest = dismiss, containerColor = LocalPalette.current.background) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp).navigationBarsPadding(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(tr("share"), style = MaterialTheme.typography.headlineSmall)
            listOf("cover" to tr("summary"), "notes" to tr("notes"), "transcript" to tr("transcript"), "screens" to tr("screenshots"), "audio" to tr("audio")).forEach { (id, label) ->
                Row(verticalAlignment = Alignment.CenterVertically) { Checkbox(id in parts, { parts = if (it) parts + id else parts - id }); Text(label) }
            }
            ChoiceRow(tr("expiry"), expiry, listOf("1d" to tr("one_day"), "7d" to tr("seven_days"), "30d" to tr("thirty_days"), "never" to tr("never"))) { expiry = it }
            OutlinedTextField(password, { password = it }, label = { Text(tr("password")) }, visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth())
            Button(onClick = { action("share.publish", payload("parts" to JSONArray(parts.toList()), "expiry" to expiry, "password" to password)); dismiss() }, enabled = parts.isNotEmpty(), modifier = Modifier.fillMaxWidth()) { Text(tr("share_create")) }
            meeting.array("shares").forEach { share -> Row(verticalAlignment = Alignment.CenterVertically) {
                Text(share.optString("url"), Modifier.weight(1f), maxLines = 1)
                TextButton(onClick = { action("clipboard", payload("text" to share.optString("url"))) }) { Text(tr("copy")) }
                TextButton(onClick = { action("share.revoke", payload("id" to share.optString("id"))) }) { Text(tr("revoke")) }
            } }
            Spacer(Modifier.height(20.dp))
        }
    }
}
