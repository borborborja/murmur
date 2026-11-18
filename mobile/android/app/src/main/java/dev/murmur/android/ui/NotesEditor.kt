package dev.murmur.android.ui

import android.annotation.SuppressLint
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import org.json.JSONObject
import java.io.ByteArrayInputStream

/** Only the bundled editor can access this bridge. Network and file navigation are blocked. */
@SuppressLint("SetJavaScriptEnabled")
@Composable fun NotesEditor(meetingId: String, doc: JSONObject, time: Double, modifier: Modifier = Modifier, zoom: Double = 1.0, onChanged: (JSONObject) -> Unit) {
    val context = LocalContext.current
    val palette = LocalPalette.current
    val strings = LocalStrings.current
    val latestChanged by rememberUpdatedState(onChanged)
    val latestDoc by rememberUpdatedState(doc)
    val latestTime by rememberUpdatedState(time)
    var webView by remember(meetingId) { mutableStateOf<WebView?>(null) }
    var ready by remember(meetingId) { mutableStateOf(false) }
    var headingMenu by remember { mutableStateOf(false) }
    var linkDialog by remember { mutableStateOf(false) }
    val configure: () -> Unit = {
        val colors = JSONObject()
        listOf("background", "textPrimary", "textSecondary", "timestamp", "separator", "fillSubtle").forEach { key ->
            val color = palette.color(key).toArgb().toLong() and 0xffffffffL
            colors.put(key, "#" + (color and 0xffffff).toString(16).padStart(6, '0'))
        }
        val config = payload("doc" to latestDoc, "time" to latestTime, "placeholder" to strings.text("notes_placeholder"), "colors" to colors, "lang" to strings.language, "zoom" to zoom)
        webView?.evaluateJavascript("window.murmur&&window.murmur.configure($config)", null)
    }
    Column(modifier) {
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 8.dp)) {
            val commands = listOf(
                Triple("bold", "bold", Icons.Filled.FormatBold), Triple("italic", "italic", Icons.Filled.FormatItalic),
                Triple("underline", "underline", Icons.Filled.FormatUnderlined), Triple("strike", "strike", Icons.Filled.FormatStrikethrough),
                Triple("bullet", "bullet_list", Icons.Filled.FormatListBulleted),
                Triple("ordered", "ordered_list", Icons.Filled.FormatListNumbered), Triple("tasks", "task_list", Icons.Filled.Checklist),
                Triple("indent", "indent", Icons.Filled.FormatIndentIncrease), Triple("outdent", "outdent", Icons.Filled.FormatIndentDecrease),
                Triple("undo", "undo", Icons.Filled.Undo), Triple("redo", "redo", Icons.Filled.Redo)
            )
            Box {
                IconButton(onClick = { headingMenu = true }, enabled = ready) { Icon(Icons.Filled.Title, tr("heading"), Modifier.size(21.dp)) }
                DropdownMenu(headingMenu, { headingMenu = false }) {
                    listOf("heading1" to "heading_1", "heading2" to "heading_2", "heading3" to "heading_3", "paragraph" to "paragraph", "quote" to "quote", "code" to "code").forEach { (command, label) ->
                        DropdownMenuItem(text = { Text(tr(label)) }, onClick = { headingMenu = false; webView?.evaluateJavascript("window.murmur&&window.murmur.command('$command')", null) })
                    }
                }
            }
            IconButton(onClick = { linkDialog = true }, enabled = ready) { Icon(Icons.Filled.Link, tr("link"), Modifier.size(21.dp)) }
            commands.forEach { (command, label, icon) -> IconButton(onClick = { webView?.evaluateJavascript("window.murmur&&window.murmur.command('${command}')", null) }, enabled = ready) { Icon(icon, tr(label), Modifier.size(21.dp)) } }
        }
        HorizontalDivider()
        key(meetingId) {
            AndroidView(factory = { ctx ->
                WebView(ctx).apply {
                    setBackgroundColor(palette.background.toArgb())
                    settings.javaScriptEnabled = true
                    settings.domStorageEnabled = false
                    settings.allowFileAccess = false
                    settings.allowContentAccess = false
                    settings.blockNetworkLoads = true
                    settings.saveFormData = false
                    settings.setSupportMultipleWindows(false)
                    isVerticalScrollBarEnabled = false
                    webViewClient = object : WebViewClient() {
                        override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean = true
                        override fun shouldInterceptRequest(view: WebView?, request: WebResourceRequest?): WebResourceResponse {
                            val uri = request?.url
                            if (uri?.scheme == "https" && uri.host == "murmur.local" && uri.path?.startsWith("/notes/") == true) {
                                val name = uri.path!!.removePrefix("/")
                                if (!name.contains("..")) {
                                    val mime = when { name.endsWith(".js") -> "application/javascript"; name.endsWith(".css") -> "text/css"; else -> "text/html" }
                                    return runCatching { WebResourceResponse(mime, "UTF-8", ctx.assets.open(name)) }.getOrElse { WebResourceResponse("text/plain", "UTF-8", 404, "Not found", emptyMap(), ByteArrayInputStream(byteArrayOf())) }
                                }
                            }
                            return WebResourceResponse("text/plain", "UTF-8", 403, "Forbidden", emptyMap(), ByteArrayInputStream(byteArrayOf()))
                        }
                    }
                    addJavascriptInterface(object {
                        @JavascriptInterface fun changed(json: String) { post { runCatching { latestChanged(JSONObject(json)) } } }
                        @JavascriptInterface fun ready() { post { ready = true; configure() } }
                    }, "AndroidNotes")
                    webView = this
                    loadUrl("https://murmur.local/notes/index.html")
                }
            }, update = { if (ready) configure() }, modifier = Modifier.fillMaxSize().imePadding())
        }
    }
    if (linkDialog) EditDialog(tr("link_url"), "", onDismiss = { linkDialog = false }) { value ->
        val quoted = JSONObject.quote(value.trim())
        webView?.evaluateJavascript("window.murmur&&window.murmur.link($quoted)", null)
    }
    DisposableEffect(meetingId) { onDispose { webView?.apply { removeJavascriptInterface("AndroidNotes"); destroy() }; webView = null } }
}
