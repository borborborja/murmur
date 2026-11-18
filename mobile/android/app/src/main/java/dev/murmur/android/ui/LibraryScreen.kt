package dev.murmur.android.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.murmur.android.UiState
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable fun LibraryScreen(state: UiState, action: Dispatch, start: () -> Unit) {
    var query by rememberSaveable { mutableStateOf("") }
    var tag by rememberSaveable { mutableStateOf<String?>(null) }
    var editingTag by remember { mutableStateOf<String?>(null) }
    val tags = state.meetings.flatMap { it.optJSONArray("tags")?.strings().orEmpty() }.groupingBy { it }.eachCount().entries.sortedByDescending { it.value }
    val selected = state.meetings.filter { tag == null || it.optJSONArray("tags")?.strings()?.contains(tag) == true }
    val selectedIds = selected.map { it.optString("id") }.toSet()
    val searchResults = state.searchResults.filter { tag == null || it.optString("meetingId", it.optString("meeting_id")) in selectedIds }
    val language = LocalStrings.current.language
    val dateFormat = remember(language) { SimpleDateFormat("d MMM · HH:mm", Locale.forLanguageTag(language)) }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 8.dp, bottom = 110.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            Text(tr("meetings"), fontWeight = FontWeight.Bold, fontSize = 32.sp, letterSpacing = (-0.8).sp)
            Hint(tr("local_private"), Modifier.padding(top = 6.dp, bottom = 20.dp))
            OutlinedTextField(query, { query = it; action("library.search", payload("query" to it)) }, Modifier.fillMaxWidth(), singleLine = true,
                placeholder = { Text(tr("search")) }, leadingIcon = { Icon(Icons.Filled.Search, null) },
                trailingIcon = { if (query.isNotEmpty()) IconButton(onClick = { query = ""; action("library.search", payload("query" to "")) }) { Icon(Icons.Filled.Close, tr("close")) } }, shape = RoundedCornerShape(14.dp))
        }
        if (tags.isNotEmpty()) item {
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                item { FilterChip(selected = tag == null, onClick = { tag = null }, label = { Text(tr("all_meetings")) }) }
                items(tags) { entry -> FilterChip(selected = tag == entry.key, onClick = { if (tag == entry.key) editingTag = entry.key else tag = entry.key }, label = { Text("${entry.key} · ${entry.value}") }) }
            }
        }
        if (query.isNotBlank()) {
            if (searchResults.isEmpty()) item { Hint(tr("no_results"), Modifier.padding(vertical = 32.dp)) }
            items(searchResults) { hit ->
                Surface(onClick = { action("meeting.open", payload("id" to hit.optString("meetingId", hit.optString("meeting_id")), "time" to hit.optDouble("t", 0.0))) }, shape = RoundedCornerShape(12.dp), color = LocalPalette.current.subtle) {
                    Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(hit.optString("title"), fontWeight = FontWeight.SemiBold)
                        Text(hit.optString("snippet").replace("\u0002", "").replace("\u0003", ""), style = MaterialTheme.typography.bodyMedium, maxLines = 3, overflow = TextOverflow.Ellipsis)
                        Text(clock(hit.optDouble("t", 0.0)), color = LocalPalette.current.timestamp, style = MaterialTheme.typography.labelSmall)
                    }
                }
            }
        } else if (selected.isEmpty()) {
            item {
                Column(Modifier.fillMaxWidth().padding(top = 48.dp, bottom = 32.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    MurmurLogo(Modifier.size(88.dp))
                    Text(tr("empty_title"), Modifier.padding(top = 28.dp), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
                    Text(tr("empty_body"), Modifier.padding(top = 12.dp), color = LocalPalette.current.secondary, style = MaterialTheme.typography.bodyLarge, textAlign = androidx.compose.ui.text.style.TextAlign.Center, lineHeight = 26.sp)
                    OutlinedButton(onClick = { action("import.file", JSONObject()) }, Modifier.padding(top = 24.dp)) { Icon(Icons.Filled.FileOpen, null, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)); Text(tr("import_file")) }
                    Hint(tr("consent_recording"), Modifier.padding(top = 24.dp))
                }
            }
        } else {
            items(selected, key = { it.optString("id") }) { meeting ->
                Surface(onClick = { action("meeting.open", payload("id" to meeting.optString("id"))) }, color = LocalPalette.current.subtle, shape = RoundedCornerShape(16.dp)) {
                    Column(Modifier.fillMaxWidth().padding(18.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
                        Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            Text(meeting.optString("title"), Modifier.weight(1f), fontWeight = FontWeight.SemiBold, fontSize = 17.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            if (meeting.optString("status") == "recording") Icon(Icons.Filled.FiberManualRecord, tr("recording"), tint = LocalPalette.current.recording, modifier = Modifier.size(14.dp))
                            else if (meeting.optJSONArray("pending")?.length() ?: 0 > 0) Icon(Icons.Filled.Pending, tr("finish_processing"), tint = LocalPalette.current.secondary, modifier = Modifier.size(18.dp))
                        }
                        val last = meeting.optString("lastLine", meeting.optString("last_line"))
                        if (last.isNotBlank()) Text(last, maxLines = 2, overflow = TextOverflow.Ellipsis, color = LocalPalette.current.secondary, style = MaterialTheme.typography.bodyMedium)
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Hint(dateFormat.format(Date(meeting.optDouble("createdAt", 0.0).toLong())))
                            Hint(clock(meeting.optDouble("duration", 0.0)))
                        }
                        val meetingTags = meeting.optJSONArray("tags")?.strings().orEmpty()
                        if (meetingTags.isNotEmpty()) Text(meetingTags.joinToString("  ·  "), color = LocalPalette.current.secondary, style = MaterialTheme.typography.labelSmall)
                    }
                }
            }
        }
    }
    editingTag?.let { original ->
        var name by remember(original) { mutableStateOf(original) }
        AlertDialog(onDismissRequest = { editingTag = null }, title = { Text(tr("tags")) }, text = { OutlinedTextField(name, { name = it }, label = { Text(tr("tag_name")) }) },
            confirmButton = { TextButton(onClick = { action("tag.rename", payload("from" to original, "to" to name)); editingTag = null; tag = name }) { Text(tr("save")) } },
            dismissButton = { Row { TextButton(onClick = { action("tag.delete", payload("name" to original)); editingTag = null; tag = null }) { Text(tr("delete")) }; TextButton(onClick = { editingTag = null }) { Text(tr("cancel")) } } })
    }
}

@Composable fun CalendarScreen(state: UiState, action: Dispatch) {
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item { Text(tr("calendar"), style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.Bold); Hint(tr("calendar_hint"), Modifier.padding(vertical = 12.dp)) }
        item { Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { action("calendar.connect", JSONObject()) }) { Text(tr("calendar_connect")) }
            IconButton(onClick = { action("calendar.refresh", JSONObject()) }) { Icon(Icons.Filled.Refresh, tr("calendar_refresh")) }
        } }
        if (state.calendarEvents.isEmpty()) item { Hint(tr("calendar_empty"), Modifier.padding(vertical = 32.dp)) }
        items(state.calendarEvents) { event ->
            Surface(color = LocalPalette.current.subtle, shape = RoundedCornerShape(14.dp)) {
                Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(event.optString("title"), style = MaterialTheme.typography.titleMedium)
                    Hint(SimpleDateFormat("EEE d MMM · HH:mm", Locale.forLanguageTag(LocalStrings.current.language)).format(Date(event.optDouble("start", 0.0).toLong())))
                    OutlinedButton(onClick = { action("calendar.record", payload("event" to event)) }, enabled = !state.recording) { Icon(Icons.Filled.Mic, null, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)); Text(tr("start_recording")) }
                }
            }
        }
    }
}
