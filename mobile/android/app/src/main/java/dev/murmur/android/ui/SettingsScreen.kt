package dev.murmur.android.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import dev.murmur.android.BuildConfig
import dev.murmur.android.UiState
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

@Composable
fun SettingsScreen(state: UiState, section: String, selectSection: (String) -> Unit, action: Dispatch) {
    var dialog by remember { mutableStateOf<String?>(null) }
    val settings = state.settings
    val strings = LocalStrings.current
    val update: (JSONObject) -> Unit = { action("settings.update", payload("patch" to it)) }
    val sections = listOf("general", "appearance", "transcription", "models", "intelligence", "exports", "computer", "connector", "share_settings", "privacy")
    Column(Modifier.fillMaxSize()) {
        Text(tr("settings"), Modifier.padding(horizontal = 20.dp, vertical = 12.dp), style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.Bold)
        LazyRow(contentPadding = PaddingValues(horizontal = 20.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(sections) { id -> FilterChip(selected = section == id, onClick = { selectSection(id) }, label = { Text(if (id == "general") tr("settings:tabs.general") else tr(id)) }) }
        }
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            when (section) {
                "general" -> {
                    item { SectionTitle(tr("audio")); ToggleRow(tr("microphone"), settings.optBoolean("mic", true)) { update(payload("mic" to it)) } }
                    item { ToggleRow(tr("save_audio"), settings.optBoolean("saveAudio", false)) { update(payload("saveAudio" to it)) } }
                    item {
                        val microphones = settings.optJSONArray("microphones")?.objects().orEmpty().map { it.optString("id") to it.optString("name") }
                        ChoiceRow(tr("microphone"), settings.textOrNull("micDevice").orEmpty(), listOf("" to tr("automatic")) + microphones) { update(payload("micDevice" to it.takeIf(String::isNotBlank))) }
                    }
                    item { ToggleRow(tr("system_audio"), settings.optBoolean("system", false), tr("audio_restricted")) { update(payload("system" to it)) } }
                    item { ToggleRow(tr("floating_bar"), settings.optBoolean("recordingIndicator", false)) { update(payload("recordingIndicator" to it)); if (it) action("overlay.permission", JSONObject()) } }
                    item { SectionTitle(tr("process_title")); ToggleRow(tr("ask_after"), settings.optBoolean("askAfter", true)) { update(payload("askAfter" to it)) } }
                    item { ToggleRow(tr("auto_summary"), settings.optBoolean("summarize", false)) { update(payload("summarize" to it)) } }
                    item { ToggleRow(tr("calendar_alerts"), settings.optBoolean("calendarAlerts", true)) { update(payload("calendarAlerts" to it)); if (it) action("calendar.connect", JSONObject()) } }
                    item { ActionRow(tr("calendar_exact"), Icons.Filled.Alarm, tr("calendar_exact_hint")) { action("calendar.exact", JSONObject()) } }
                    item { ToggleRow(tr("calendar_no_guests"), settings.optBoolean("calendarNoGuests")) { update(payload("calendarNoGuests" to it)) } }
                    val calendars = settings.optJSONArray("calendars")?.objects().orEmpty()
                    if (calendars.isNotEmpty()) {
                        item { SectionTitle(tr("calendar_select")) }
                        items(calendars, key = { it.optString("id") }) { calendar ->
                            val selected = settings.optJSONArray("calendarIds")?.strings()?.toSet()
                            ToggleRow(calendar.optString("title"), selected == null || calendar.optString("id") in selected, calendar.optString("account")) { enabled ->
                                val next = (selected ?: calendars.map { it.optString("id") }.toSet()).toMutableSet()
                                if (enabled) next.add(calendar.optString("id")) else next.remove(calendar.optString("id"))
                                update(payload("calendarIds" to JSONArray(next.toList())))
                                action("calendar.refresh", JSONObject())
                            }
                        }
                    }
                    item { ActionRow(tr("check_updates"), Icons.Filled.SystemUpdate) { action("updates.check", JSONObject()) } }
                    item { ActionRow(tr("update_url"), subtitle = settings.optString("updateManifest").takeIf { it.isNotBlank() && it != "null" }) { dialog = "update_url" } }
                }
                "appearance" -> {
                    item { ChoiceRow(tr("language_ui"), settings.optString("uiLanguage", "system"), listOf("system" to tr("system")) + languageChoices(false)) { update(payload("uiLanguage" to it)) } }
                    item { ChoiceRow(tr("theme"), settings.optString("theme", "acid"), listOf("acid" to tr("acid"), "mono" to tr("mono"), "formigo" to tr("formigo"))) { update(payload("theme" to it)) } }
                    item { ChoiceRow(tr("mode"), settings.optString("mode", "system"), listOf("system" to tr("system"), "light" to tr("light"), "dark" to tr("dark"))) { update(payload("mode" to it)) } }
                    item {
                        Text(tr("text_zoom")); Slider(settings.optDouble("textZoom", 1.0).toFloat(), { update(payload("textZoom" to it.toDouble())) }, valueRange = .85f..1.5f, steps = 5)
                    }
                }
                "transcription" -> {
                    item { ChoiceRow(tr("language"), settings.optString("language", "ca-es"), languageChoices()) { update(payload("language" to it)) } }
                    item { ToggleRow(tr("room_speakers"), settings.optBoolean("roomSpeakers", true)) { update(payload("roomSpeakers" to it)) } }
                    item {
                        ChoiceRow(tr("max_speakers"), settings.optInt("maxSpeakers", 5).toString(), (2..12).map { it.toString() to it.toString() }) { update(payload("maxSpeakers" to it.toInt())) }
                        Text(tr("threshold") + " · " + "%.2f".format(Locale.ROOT, settings.optDouble("threshold", .7)))
                        Slider(settings.optDouble("threshold", .7).toFloat(), { update(payload("threshold" to it.toDouble())) }, valueRange = .4f.. .95f)
                    }
                    item { ActionRow(tr("glossary"), Icons.Filled.Spellcheck, tr("glossary_hint")) { dialog = "glossary" } }
                    item { SectionTitle(tr("voice")); Hint(tr("voice_hint")) }
                    item { ActionRow(tr("voice_record"), Icons.Filled.RecordVoiceOver) { dialog = "voice" } }
                    item { TextButton(onClick = { action("voice.delete", JSONObject()) }) { Text(tr("voice_delete")) } }
                    item { SectionTitle(tr("recordings_steps")); StepSettings(settings, false, update) }
                    item { SectionTitle(tr("file_steps")); StepSettings(settings, true, update) }
                }
                "models" -> {
                    item { Hint(tr("models_hint")) }
                    items(state.models, key = { it.optString("id") }) { model -> ModelCard(model, state, action) }
                }
                "intelligence" -> {
                    item { Hint(tr("local_detail")) }
                    item { IntelligenceSettings(state, action, update) }
                    item { ChoiceRow(tr("summary_language"), settings.optString("coverLanguage", "interface"), listOf("interface" to tr("language_ui"), "meeting" to tr("language")) + languageChoices(false)) { update(payload("coverLanguage" to it)) } }
                    item { SectionTitle(tr("prompts")) }
                    items(listOf("system", "title", "tone", "summary", "decisions", "tasks", "parts")) { id ->
                        ActionRow(tr("cover:promptLabels.$id")) { dialog = "prompt:$id" }
                    }
                }
                "exports" -> {
                    item { SectionTitle("Markdown"); ActionRow(tr("folder"), Icons.Filled.FolderOpen, settings.textOrNull("markdownDir")) { action("folder.choose", payload("kind" to "markdown")) } }
                    item { ToggleRow(tr("markdown_auto"), settings.optBoolean("saveMarkdown")) { update(payload("saveMarkdown" to it)) } }
                    item { ChoiceRow(tr("export"), settings.optString("markdownContent", "all"), listOf("all" to tr("all_content"), "summary" to tr("summary_notes"), "transcript" to tr("transcript"))) { update(payload("markdownContent" to it)) } }
                    item { SectionTitle("Obsidian"); ActionRow(tr("obsidian_folder"), Icons.Filled.FolderOpen, settings.textOrNull("obsidianVault")) { action("folder.choose", payload("kind" to "obsidian")) } }
                    item { ToggleRow(tr("obsidian_auto"), settings.optBoolean("obsidianAuto")) { update(payload("obsidianAuto" to it)) } }
                    item { ToggleRow(tr("obsidian_people"), settings.optBoolean("obsidianPeople")) { update(payload("obsidianPeople" to it)) } }
                    item { SectionTitle("Notion"); ActionRow(tr("notion_connect"), Icons.Filled.AccountCircle, settings.optJSONObject("notionStatus")?.textOrNull("workspace")) { action("notion.connect", JSONObject()) } }
                    item { ActionRow(tr("notion_page"), Icons.Filled.Search) { dialog = "notion_page"; action("notion.search", payload("query" to "")) } }
                    item { ToggleRow(tr("notion_auto"), settings.optBoolean("notionAuto")) { update(payload("notionAuto" to it)) } }
                    item { TextButton(onClick = { action("notion.disconnect", JSONObject()) }) { Text(tr("disconnect")) } }
                }
                "computer" -> {
                    item { Hint(tr("pair_hint")) }
                    item { ActionRow(tr("pair"), Icons.Filled.QrCodeScanner) { dialog = "pair" } }
                    item { ActionRow(tr("scan_qr"), Icons.Filled.PhotoCamera) { action("pair.scan", JSONObject()) } }
                    item { ActionRow(tr("sync"), Icons.Filled.Sync, settings.optJSONObject("syncStatus")?.optJSONObject("computer")?.optString("name")) { action("pair.sync", JSONObject()) } }
                    item { TextButton(onClick = { action("pair.disconnect", JSONObject()) }) { Text(tr("unpair")) } }
                }
                "connector" -> {
                    item { Hint(tr("connector_hint")) }
                    item { ActionRow(tr("connector_start"), Icons.Filled.Cable) { action("mcp.start", JSONObject()) } }
                    item { ActionRow(tr("connector_pair"), Icons.Filled.QrCode) { action("mcp.pair", JSONObject()) } }
                    item { TextButton(onClick = { action("mcp.stop", JSONObject()) }) { Text(tr("connector_stop")) } }
                    val status = settings.optJSONObject("mcpStatus") ?: JSONObject()
                    item { Hint(tr(if (status.optBoolean("running")) "connector_running" else "connector_inactive")) }
                    if (status.optString("address").isNotBlank()) item { Text(status.optString("address"), style = MaterialTheme.typography.bodySmall) }
                    val code = status.optString("pairingUri").takeIf { it.isNotBlank() && it != "null" }
                    if (code != null) item {
                        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            SectionTitle(tr("connector_pairing_code"))
                            PairingQr(code, Modifier.size(200.dp).align(Alignment.CenterHorizontally))
                            Hint(tr("connector_pairing_hint"))
                            OutlinedButton(onClick = { action("clipboard", payload("text" to code)) }) { Icon(Icons.Filled.ContentCopy, null, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)); Text(tr("copy")) }
                        }
                    }
                    items(status.optJSONArray("pending")?.objects().orEmpty(), key = { it.optString("id") }) { pending ->
                        Surface(color = LocalPalette.current.subtle, shape = RoundedCornerShape(12.dp)) {
                            Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                Hint(tr("connector_pending")); Text(pending.optString("name"), fontWeight = FontWeight.SemiBold)
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Button(onClick = { action("mcp.approve", payload("requestId" to pending.optString("id"))) }) { Text(tr("connector_approve")) }
                                    TextButton(onClick = { action("mcp.reject", payload("requestId" to pending.optString("id"))) }) { Text(tr("connector_reject")) }
                                }
                            }
                        }
                    }
                    val clients = status.optJSONArray("clients")?.objects().orEmpty()
                    if (clients.isNotEmpty()) item { SectionTitle(tr("connector_clients")) }
                    items(clients, key = { it.optString("id") }) { client ->
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Text(client.optString("name"), Modifier.weight(1f))
                            TextButton(onClick = { action("mcp.revoke", payload("clientId" to client.optString("id"))) }) { Text(tr("connector_revoke")) }
                        }
                    }
                    if (clients.size > 1) item { TextButton(onClick = { action("mcp.revoke", payload("clientId" to "")) }) { Text(tr("connector_revoke_all")) } }
                }
                "share_settings" -> {
                    item { Hint(tr("local_detail")) }
                    item { ActionRow(tr("cloudflare_setup"), Icons.Filled.CloudUpload) { dialog = "cloudflare" } }
                    item { ActionRow(tr("connect"), Icons.Filled.Link, settings.optJSONObject("shareStatus")?.optString("url")?.takeIf(String::isNotBlank)) { dialog = "worker" } }
                    item { TextButton(onClick = { action("share.disconnect", JSONObject()) }) { Text(tr("disconnect")) } }
                }
                "privacy" -> {
                    item { Hint(tr("local_detail")) }
                    if (BuildConfig.APTABASE_KEY.isNotBlank()) item { ToggleRow(tr("analytics"), settings.optBoolean("analytics", false)) { update(payload("analytics" to it)) } }
                    item { ActionRow(tr("technical_log"), Icons.Filled.Article) { action("log.export", JSONObject()) } }
                    item { SectionTitle(tr("about")); Text(tr("about_body"), style = MaterialTheme.typography.bodyMedium); Hint(BuildConfig.VERSION_NAME, Modifier.padding(top = 12.dp)) }
                    item { ActionRow(tr("licenses"), Icons.Filled.Description) { action("licenses.show", JSONObject()) } }
                }
            }
            item { Spacer(Modifier.height(32.dp)) }
        }
    }
    when {
        dialog == "glossary" -> EditDialog(tr("glossary"), settings.optJSONArray("glossary")?.strings()?.joinToString("\n").orEmpty(), true, { dialog = null }) { action("glossary.set", payload("terms" to JSONArray(it.lines().map(String::trim).filter(String::isNotBlank)))) }
        dialog == "pair" -> EditDialog(tr("pair_code"), "", true, { dialog = null }) { action("pair.connect", payload("uri" to it.trim())) }
        dialog == "update_url" -> EditDialog(tr("update_url"), settings.optString("updateManifest").takeUnless { it == "null" }.orEmpty(), onDismiss = { dialog = null }) { update(payload("updateManifest" to it.trim().takeIf(String::isNotBlank))) }
        dialog?.startsWith("prompt:") == true -> {
            val id = dialog!!.substringAfter(':')
            val initial = settings.optJSONObject("prompts")?.optString(id)?.takeIf(String::isNotBlank) ?: LocalStrings.current.text("cover:prompts.$id")
            EditDialog(tr("cover:promptLabels.$id"), initial, true, { dialog = null }) { val prompts = JSONObject(settings.optJSONObject("prompts")?.toString() ?: "{}"); prompts.put(id, it); update(payload("prompts" to prompts)) }
        }
        dialog == "voice" -> AlertDialog(onDismissRequest = { dialog = null }, title = { Text(tr("voice")) }, text = { Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Hint(tr("mobile:voice.ready"))
            Text(tr("mobile:voice.sentence"), style = MaterialTheme.typography.titleMedium)
            Hint(tr("mobile:voice.bio")); Hint(tr("voice_hint"))
        } }, confirmButton = { TextButton(onClick = { action("voice.record", payload("sentence" to strings.text("mobile:voice.sentence"))); dialog = null }) { Text(tr("voice_record")) } }, dismissButton = { TextButton(onClick = { dialog = null }) { Text(tr("cancel")) } })
        dialog == "worker" -> WorkerDialog({ dialog = null }, action)
        dialog == "cloudflare" -> SecretDialog(tr("cloudflare_token"), { dialog = null }) { action("share.setup", payload("token" to it)) }
        dialog == "notion_page" -> NotionPageDialog(settings, { dialog = null }, action, update)
    }
}

@Composable private fun StepSettings(settings: JSONObject, file: Boolean, update: (JSONObject) -> Unit) {
    val values = if (file) settings.optJSONObject("fileSteps") ?: JSONObject() else settings
    val fields = listOf("verifySpeakers" to "verify_speakers", "finalPass" to "whisper", "punctuate" to "punctuate", "summarize" to "generate_summary")
    fields.forEach { (field, label) -> ToggleRow(tr(label), values.optBoolean(field, field in setOf("verifySpeakers", "finalPass"))) { checked ->
        if (file) { val next = JSONObject(values.toString()); next.put(field, checked); update(payload("fileSteps" to next)) }
        else update(payload(field to checked))
    } }
}

@Composable internal fun ModelCard(model: JSONObject, state: UiState, action: Dispatch) {
    val installed = model.optBoolean("installed")
    val bytes = model.optDouble("bytes", 0.0)
    val downloaded = model.optDouble("downloaded", 0.0)
    val progress = if (bytes > 0) (downloaded / bytes).toFloat().coerceIn(0f, 1f) else 0f
    val id = model.optString("id")
    val downloading = state.busy == "download" && state.settings.optString("downloadingModel") == id
    val liveProgress = if (downloading) state.progress ?: progress else progress
    Surface(color = LocalPalette.current.subtle, shape = RoundedCornerShape(14.dp)) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
                Column(Modifier.weight(1f)) { Text(modelName(id), style = MaterialTheme.typography.titleSmall); Hint(if (bytes > 1_000_000_000) "%.2f GB".format(Locale.ROOT, bytes / 1_000_000_000) else "%.0f MB".format(Locale.ROOT, bytes / 1_000_000)) }
                if (installed) Icon(Icons.Filled.CheckCircle, tr("installed"), tint = LocalPalette.current.timestamp, modifier = Modifier.size(20.dp))
            }
            if (model.optString("description").isNotBlank()) Hint(model.optString("description"))
            if ((downloaded > 0 || downloading) && !installed) LinearProgressIndicator(progress = { liveProgress }, modifier = Modifier.fillMaxWidth())
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                if (installed) TextButton(onClick = { action("model.delete", payload("id" to id)) }, enabled = !state.recording && state.busy == null) { Text(tr("delete")) }
                else if (downloading) TextButton(onClick = { action("model.cancel", payload("id" to id)) }) { Text(tr("download_cancel")) }
                else OutlinedButton(onClick = { action("model.download", payload("id" to id)) }, enabled = state.busy != "download") { Icon(Icons.Filled.Download, null, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)); Text(tr("download")) }
            }
        }
    }
}

@Composable internal fun IntelligenceSettings(state: UiState, action: Dispatch, update: (JSONObject) -> Unit) {
    val settings = state.settings
    val provider = settings.optString("coverEngine", "local")
    val providers = listOf("local" to tr("local"), "anthropic" to "Claude", "openai" to "OpenAI", "openrouter" to "OpenRouter", "ollama" to "Ollama Cloud", "gemini" to "Gemini")
    var keyDialog by remember { mutableStateOf(false) }
    var modelDialog by remember { mutableStateOf(false) }
    val keyHint = settings.optJSONObject("cloudStatus")?.optString(provider)?.takeIf { it.isNotBlank() && it != "null" }
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        ChoiceRow(tr("provider"), provider, providers) { selected ->
            update(payload("coverEngine" to selected))
            if (selected != "local" && settings.optJSONObject("cloudStatus")?.optString(selected)?.let { it.isNotBlank() && it != "null" } == true) action("cloud.models", payload("provider" to selected))
        }
        if (provider == "local") {
            val options = state.models.filter { it.optString("id").startsWith("llm") }.map { it.optString("id") to modelName(it.optString("id")) }
            if (options.isNotEmpty()) ChoiceRow(tr("model"), settings.optString("localModel", "llm-mobile"), options) { update(payload("localModel" to it)) }
        } else {
            Hint(tr("cloud_hint"))
            ActionRow(tr("api_key"), Icons.Filled.Key, keyHint?.let { "${tr("connected")} · ····$it" }) { keyDialog = true }
            val chosen = settings.optJSONObject("cloudModels")?.optString(provider).orEmpty()
            ActionRow(tr("model"), Icons.Filled.AutoAwesome, chosen.takeIf(String::isNotBlank)) { modelDialog = true; action("cloud.models", payload("provider" to provider)) }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = { action("cloud.models", payload("provider" to provider)) }) { Text(tr("refresh_models")) }
                if (keyHint != null) TextButton(onClick = { action("cloud.disconnect", payload("provider" to provider)) }) { Text(tr("disconnect")) }
            }
        }
    }
    if (keyDialog) SecretDialog(tr("api_key"), { keyDialog = false }) { action("cloud.connect", payload("provider" to provider, "key" to it)) }
    if (modelDialog) ModelPicker(settings, provider, { modelDialog = false }) { id ->
        val models = JSONObject(settings.optJSONObject("cloudModels")?.toString() ?: "{}"); models.put(provider, id); update(payload("cloudModels" to models))
    }
}

@Composable private fun SecretDialog(title: String, dismiss: () -> Unit, save: (String) -> Unit) {
    var secret by remember { mutableStateOf("") }
    AlertDialog(onDismissRequest = { secret = ""; dismiss() }, title = { Text(title) }, text = { OutlinedTextField(secret, { secret = it }, visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth(), singleLine = true, label = { Text(title) }) }, confirmButton = { TextButton(onClick = { save(secret.trim()); secret = ""; dismiss() }, enabled = secret.isNotBlank()) { Text(tr("connect")) } }, dismissButton = { TextButton(onClick = { secret = ""; dismiss() }) { Text(tr("cancel")) } })
}

@Composable private fun ModelPicker(settings: JSONObject, provider: String, dismiss: () -> Unit, select: (String) -> Unit) {
    var query by remember { mutableStateOf("") }
    val catalogProvider = settings.textOrNull("providerModelsProvider")
    val models = if (catalogProvider == null || catalogProvider == provider) settings.optJSONArray("providerModels")?.objects().orEmpty() else emptyList()
    val filtered = models.filter { it.optString("id").contains(query, true) || it.optString("name").contains(query, true) || it.optString("group").contains(query, true) }
    AlertDialog(onDismissRequest = dismiss, title = { Text(tr("select_model")) }, text = { Column {
        OutlinedTextField(query, { query = it }, label = { Text(tr("search")) }, singleLine = true)
        LazyColumn(Modifier.heightIn(max = 380.dp)) { items(filtered, key = { it.optString("id") }) { model -> TextButton(onClick = { select(model.optString("id")); dismiss() }, modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.fillMaxWidth()) { Text(model.optString("name", model.optString("id"))); Hint(model.optString("id")) }
        } } }
    } }, confirmButton = { if (query.isNotBlank()) TextButton(onClick = { select(query.trim()); dismiss() }) { Text(tr("save")) } }, dismissButton = { TextButton(onClick = dismiss) { Text(tr("cancel")) } })
}

@Composable private fun WorkerDialog(dismiss: () -> Unit, action: Dispatch) {
    var url by remember { mutableStateOf("") }
    var key by remember { mutableStateOf("") }
    AlertDialog(onDismissRequest = dismiss, title = { Text(tr("share_settings")) }, text = { Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(url, { url = it }, label = { Text(tr("worker_url")) }, singleLine = true)
        OutlinedTextField(key, { key = it }, label = { Text(tr("worker_key")) }, visualTransformation = PasswordVisualTransformation(), singleLine = true)
    } }, confirmButton = { TextButton(onClick = { action("share.connect", payload("url" to url.trim(), "key" to key)); key = ""; dismiss() }, enabled = url.isNotBlank() && key.isNotBlank()) { Text(tr("connect")) } }, dismissButton = { TextButton(onClick = dismiss) { Text(tr("cancel")) } })
}

@Composable private fun NotionPageDialog(settings: JSONObject, dismiss: () -> Unit, action: Dispatch, update: (JSONObject) -> Unit) {
    var query by remember { mutableStateOf("") }
    val pages = settings.optJSONArray("notionPages")?.objects().orEmpty()
    AlertDialog(onDismissRequest = dismiss, title = { Text(tr("notion_page")) }, text = { Column {
        OutlinedTextField(query, { query = it; action("notion.search", payload("query" to it)) }, label = { Text(tr("search")) })
        LazyColumn(Modifier.heightIn(max = 300.dp)) { items(pages) { page -> TextButton(onClick = { update(payload("notionPage" to page)); dismiss() }, modifier = Modifier.fillMaxWidth()) { Text(page.optString("title")) } } }
    } }, confirmButton = {}, dismissButton = { TextButton(onClick = dismiss) { Text(tr("cancel")) } })
}
