package dev.murmur.android

import android.annotation.SuppressLint
import android.app.Service
import android.content.Intent
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import android.view.*
import android.view.inputmethod.InputMethodManager
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.*
import androidx.compose.ui.graphics.toArgb
import androidx.core.content.ContextCompat
import dev.murmur.android.ui.AppStrings
import dev.murmur.android.ui.MurmurPalette
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collect
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.util.Locale
import kotlin.math.abs
import kotlin.math.sqrt

/** Optional recording controls. Capture and persistence remain owned by RecordingService. */
class OverlayService : Service() {
    private val runtime by lazy { AppRuntime.get(this) }
    private val windows by lazy { getSystemService(WindowManager::class.java) }
    private val preferences by lazy { getSharedPreferences("murmur.overlay.position", MODE_PRIVATE) }
    private val main = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var palette: MurmurPalette
    private lateinit var strings: AppStrings
    private var appearance = ""
    private var root: View? = null
    private var params: WindowManager.LayoutParams? = null
    private var signal: SignalView? = null
    private var camera: ControlView? = null
    private var stop: ControlView? = null
    private var timer: TextView? = null
    private var web: WebView? = null
    private var webReady = false
    private var editorLoaded = false
    private var editorChanged = false
    private var destroyed = false
    private var compact = false
    private var panelFocused = false
    private var meetingId: String? = null
    private var document = json("type" to "doc", "content" to org.json.JSONArray())
    private var lastConfiguredDoc = ""
    private var lastTime = -1.0
    private var stopArmed = false
    private var dragging = false
    private var imeHeight = 0
    private var pendingSave: Runnable? = null
    private var latestSave: Pair<String, JSONObject>? = null
    private var pendingDocument: JSONObject? = null
    private var pendingDocumentMeeting: String? = null
    private val disarm = Runnable { stopArmed = false; updateControls() }
    private var lastState = UiState()

    override fun onBind(intent: Intent?): IBinder? = null
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int) = START_NOT_STICKY
    override fun onCreate() {
        super.onCreate()
        if (!Settings.canDrawOverlays(this) || runtime.activeId == null) { stopSelf(); return }
        applyAppearance(runtime.state.value.settings)
        showPill()
        scope.launch {
            runtime.state.collect { state ->
                lastState = state
                if (!state.recording || runtime.activeId == null || !Settings.canDrawOverlays(this@OverlayService)) {
                    stopSelf(); return@collect
                }
                val changed = applyAppearance(state.settings)
                if (changed) {
                    if (compact) showCompact() else showPill()
                }
                signal?.level = state.level
                signal?.paused = state.paused
                signal?.invalidate()
                timer?.text = clock(state.seconds)
                if (compact) {
                    val active = runtime.activeId
                    if (active != meetingId) { showCompact(); return@collect }
                    val selected = state.meeting
                    if (selected != null && selected.optString("id") == meetingId) selected.optJSONObject("notesDoc")?.let { incoming ->
                        val pending = pendingDocument.takeIf { pendingDocumentMeeting == meetingId }
                        if (pending == null || canonical(incoming) == canonical(pending)) {
                            document = incoming
                            if (pending != null) { pendingDocument = null; pendingDocumentMeeting = null }
                        }
                    }
                    configureEditor(state)
                }
            }
        }
        scope.launch {
            while (isActive) { signal?.invalidate(); delay(120) }
        }
    }

    /** Tokens remain the same source of truth as Compose and the desktop application. */
    private fun applyAppearance(settings: JSONObject): Boolean {
        val theme = settings.optString("theme", "acid").takeIf { it in setOf("acid", "mono", "formigo") } ?: "acid"
        val dark = when (settings.optString("mode", "system")) {
            "dark" -> true; "light" -> false
            else -> resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES
        }
        val language = settings.optString("uiLanguage", "system").takeIf { it in setOf("ca", "es", "en") }
            ?: Locale.getDefault().language.takeIf { it in setOf("ca", "es", "en") } ?: "en"
        val identity = "$theme/$dark/$language"
        if (identity == appearance) return false
        val tokens = JSONObject(assets.open("murmur-tokens.json").bufferedReader().use { it.readText() })
        palette = MurmurPalette(tokens.getJSONObject("themes").getJSONObject(theme).getJSONObject(if (dark) "dark" else "light"), dark)
        strings = AppStrings(this, language)
        appearance = identity
        return true
    }
    private fun color(key: String) = palette.color(key).toArgb()
    private fun text(key: String) = strings.text(key)
    private fun dp(value: Float) = value * resources.displayMetrics.density
    private fun px(value: Int) = dp(value.toFloat()).toInt()
    private fun shape(fill: Int, radius: Int, border: Int? = null) = GradientDrawable().apply {
        setColor(fill); cornerRadius = dp(radius.toFloat()); border?.let { setStroke(px(1), it) }
    }

    private fun showPill() {
        closeEditor()
        compact = false; panelFocused = false; meetingId = null; imeHeight = 0
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(px(4), px(5), px(4), px(5))
            background = shape(color("recordingPill"), 26, color("recordingPillBorder"))
            elevation = dp(6f)
        }
        signal = SignalView().apply {
            level = lastState.level; paused = lastState.paused
            contentDescription = "${text("recording")} · ${text("notes")}"
            setOnClickListener {
                if (runtime.config.read().optBoolean("compactNotes", true)) showCompact() else openApp()
            }
            setOnTouchListener(dragListener(this))
        }.also { layout.addView(it, LinearLayout.LayoutParams(px(44), px(53))) }
        camera = ControlView(Control.CAMERA).apply {
            contentDescription = text("capture")
            setOnClickListener { if (stopArmed) stopRecording() else capture() }
        }.also { layout.addView(it, LinearLayout.LayoutParams(px(44), px(48))) }
        stop = ControlView(Control.STOP).apply {
            contentDescription = text("stop")
            setOnClickListener {
                stopArmed = !stopArmed
                main.removeCallbacks(disarm)
                if (stopArmed) main.postDelayed(disarm, 4000)
                updateControls()
            }
        }.also { layout.addView(it, LinearLayout.LayoutParams(px(44), px(40))) }
        timer = null
        val placement = placement(px(52), px(151))
        replaceWindow(layout, placement)
        updateControls()
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun showCompact() {
        val id = runtime.activeId ?: return
        val alreadyCompact = compact
        val initial = pendingDocument?.takeIf { pendingDocumentMeeting == id }
            ?: if (meetingId == id) document else runtime.state.value.meeting?.takeIf { it.optString("id") == id }?.optJSONObject("notesDoc")
        closeEditor()
        document = initial ?: json("type" to "doc", "content" to org.json.JSONArray())
        compact = true; panelFocused = if (alreadyCompact) panelFocused else true
        meetingId = id; webReady = false; editorLoaded = false; editorChanged = false
        lastConfiguredDoc = ""; lastTime = -1.0; stopArmed = false
        camera = null; stop = null; main.removeCallbacks(disarm)
        val column = object : LinearLayout(this) {
            override fun dispatchTouchEvent(event: MotionEvent): Boolean {
                if (event.actionMasked == MotionEvent.ACTION_OUTSIDE) { releasePanelFocus(); return false }
                if (event.actionMasked == MotionEvent.ACTION_DOWN) focusPanel()
                return super.dispatchTouchEvent(event)
            }
        }.apply {
            orientation = LinearLayout.VERTICAL
            background = shape(color("background"), 18, color("separator"))
            clipToOutline = true; elevation = dp(10f)
            isFocusableInTouchMode = true
            setOnKeyListener { _, key, event ->
                if (key == KeyEvent.KEYCODE_BACK && event.action == KeyEvent.ACTION_UP) { showPill(); true } else false
            }
        }
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setPadding(px(10), px(2), px(6), px(2)); setBackgroundColor(color("recordingPill"))
        }
        signal = SignalView().apply {
            level = lastState.level; paused = lastState.paused
            contentDescription = text("recording_open"); setOnClickListener { openApp() }
        }.also { header.addView(it, LinearLayout.LayoutParams(px(34), px(44))) }
        val title = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_VERTICAL
            addView(TextView(this@OverlayService).apply {
                text = "Murmur · ${text("notes")}"
                textSize = 13f; setTextColor(color("onRecordingPill")); setTypeface(typeface, Typeface.BOLD)
            })
            timer = TextView(this@OverlayService).apply {
                text = clock(lastState.seconds); textSize = 11f; typeface = Typeface.MONOSPACE
                setTextColor(color("onRecordingPill")); alpha = .7f
            }.also(::addView)
            setOnTouchListener(dragListener(this))
        }
        header.addView(title, LinearLayout.LayoutParams(0, px(44), 1f))
        header.addView(labelButton("↗", "recording_open", true) { openApp() }, LinearLayout.LayoutParams(px(40), px(44)))
        header.addView(labelButton("×", "close", true) { showPill() }, LinearLayout.LayoutParams(px(40), px(44)))
        column.addView(header, LinearLayout.LayoutParams(-1, px(48)))
        val tools = HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false; setBackgroundColor(color("fillSubtle")) }
        val toolbar = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(px(4), 0, px(4), 0) }
        val commands = listOf(
            Triple("H1", "heading_1", "heading1"), Triple("H2", "heading_2", "heading2"), Triple("H3", "heading_3", "heading3"),
            Triple("¶", "paragraph", "paragraph"), Triple("B", "bold", "bold"), Triple("I", "italic", "italic"),
            Triple("U", "underline", "underline"), Triple("S̶", "strike", "strike"), Triple("•", "bullet_list", "bullet"),
            Triple("1.", "ordered_list", "ordered"), Triple("☑", "task_list", "tasks"), Triple("❯", "indent", "indent"),
            Triple("❮", "outdent", "outdent"), Triple("❝", "quote", "quote"), Triple("‹›", "code", "code"),
            Triple("↶", "undo", "undo"), Triple("↷", "redo", "redo"))
        commands.forEach { (label, description, command) -> toolbar.addView(labelButton(label, description) {
            if (webReady) web?.evaluateJavascript("window.murmur.command('${command}')", null)
        }, LinearLayout.LayoutParams(px(40), px(42))) }
        val linkRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; visibility = View.GONE; setPadding(px(8), 0, px(4), 0) }
        val linkInput = EditText(this).apply {
            hint = text("link_url"); textSize = 13f; isSingleLine = true
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_URI
            setTextColor(color("textPrimary")); setHintTextColor(color("textSecondary")); highlightColor = color("sidebarSelection")
            backgroundTintList = ColorStateList.valueOf(color("timestamp"))
        }
        linkRow.addView(linkInput, LinearLayout.LayoutParams(0, px(44), 1f))
        linkRow.addView(labelButton("✓", "save") {
            val url = linkInput.text.toString().trim()
            if (url.isBlank() || url.startsWith("https://", true) || url.startsWith("http://", true)) {
                web?.evaluateJavascript("window.murmur.link(${JSONObject.quote(url)})", null)
                linkRow.visibility = View.GONE; linkInput.text.clear()
            }
        }, LinearLayout.LayoutParams(px(40), px(44)))
        toolbar.addView(labelButton("↗", "link") {
            linkRow.visibility = if (linkRow.visibility == View.VISIBLE) View.GONE else View.VISIBLE
            if (linkRow.visibility == View.VISIBLE) {
                linkInput.requestFocus(); getSystemService(InputMethodManager::class.java).showSoftInput(linkInput, InputMethodManager.SHOW_IMPLICIT)
            }
        }, LinearLayout.LayoutParams(px(40), px(42)))
        tools.addView(toolbar)
        column.addView(tools, LinearLayout.LayoutParams(-1, px(42)))
        column.addView(linkRow, LinearLayout.LayoutParams(-1, -2))
        column.addView(View(this).apply { setBackgroundColor(color("separator")) }, LinearLayout.LayoutParams(-1, px(1)))
        val editor = WebView(this).apply {
            setBackgroundColor(color("background"))
            settings.javaScriptEnabled = true; settings.domStorageEnabled = false
            settings.allowFileAccess = false; settings.allowContentAccess = false
            settings.blockNetworkLoads = true; settings.saveFormData = false
            settings.setSupportMultipleWindows(false); isVerticalScrollBarEnabled = false
            isEnabled = false
            webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?) = true
                override fun shouldInterceptRequest(view: WebView?, request: WebResourceRequest?): WebResourceResponse {
                    val uri = request?.url
                    if (uri?.scheme == "https" && uri.host == "murmur.local" && uri.path?.startsWith("/notes/") == true && uri.query == null) {
                        val name = uri.path!!.removePrefix("/")
                        if (!name.contains("..")) {
                            val mime = when { name.endsWith(".js") -> "application/javascript"; name.endsWith(".css") -> "text/css"; else -> "text/html" }
                            return runCatching { WebResourceResponse(mime, "UTF-8", assets.open(name)) }.getOrElse { blocked(404) }
                        }
                    }
                    return blocked(403)
                }
            }
        }
        editor.addJavascriptInterface(object {
            @JavascriptInterface fun ready() { main.post { if (web === editor && meetingId == id) { webReady = true; configureEditor(runtime.state.value, true) } } }
            @JavascriptInterface fun changed(encoded: String) {
                if (destroyed || encoded.length > 2 * 1024 * 1024) return
                val parsed = runCatching { JSONObject(encoded).takeIf { it.optString("type") == "doc" } }.getOrNull() ?: return
                main.post {
                    if (destroyed || !editorLoaded || web !== editor || meetingId != id) return@post
                    editorChanged = true
                    document = parsed; lastConfiguredDoc = parsed.toString(); pendingDocument = parsed; pendingDocumentMeeting = id
                    latestSave = id to parsed
                    pendingSave?.let(main::removeCallbacks)
                    pendingSave = Runnable { flushSave() }.also { main.postDelayed(it, 120) }
                }
            }
        }, "AndroidNotes")
        web = editor
        column.addView(editor, LinearLayout.LayoutParams(-1, 0, 1f))
        val placement = placement(compactWidth(), compactHeight())
        replaceWindow(column, placement)
        column.setOnApplyWindowInsetsListener { _, insets ->
            if (Build.VERSION.SDK_INT >= 30) {
                val next = insets.getInsets(WindowInsets.Type.ime()).bottom
                if (next != imeHeight) { imeHeight = next; resizeCompact() }
            }
            insets
        }
        editor.loadUrl("https://murmur.local/notes/index.html")
        scope.launch {
            val meeting = withContext(Dispatchers.IO) { runtime.get(id) }
            if (meetingId == id && web === editor) {
                val incoming = meeting?.optJSONObject("notesDoc") ?: json("type" to "doc", "content" to org.json.JSONArray())
                val pending = pendingDocument.takeIf { pendingDocumentMeeting == id }
                if (!editorChanged && (pending == null || canonical(incoming) == canonical(pending))) {
                    document = incoming
                    if (pending != null) { pendingDocument = null; pendingDocumentMeeting = null }
                }
                editorLoaded = true; editor.isEnabled = true
                configureEditor(runtime.state.value, true)
            }
        }
        signal?.invalidate()
    }

    private fun blocked(status: Int) = WebResourceResponse("text/plain", "UTF-8", status, if (status == 404) "Not found" else "Forbidden", emptyMap(), ByteArrayInputStream(byteArrayOf()))
    private fun configureEditor(state: UiState, force: Boolean = false) {
        if (!webReady || !editorLoaded || meetingId == null) return
        val encoded = document.toString()
        if (force || encoded != lastConfiguredDoc) {
            val colors = JSONObject().apply { listOf("background", "textPrimary", "textSecondary", "timestamp", "separator", "fillSubtle").forEach { key -> put(key, "#" + (color(key) and 0xffffff).toString(16).padStart(6, '0')) } }
            val config = json("doc" to document, "time" to state.seconds, "placeholder" to text("notes_placeholder"), "colors" to colors,
                "lang" to strings.language, "zoom" to state.settings.optDouble("textZoom", 1.0))
            web?.evaluateJavascript("window.murmur&&window.murmur.configure($config)", null)
            lastConfiguredDoc = encoded; lastTime = state.seconds
        } else if (abs(lastTime - state.seconds) >= .25) {
            web?.evaluateJavascript("window.murmur&&window.murmur.setTime(${state.seconds})", null)
            lastTime = state.seconds
        }
    }
    private fun flushSave() {
        pendingSave?.let(main::removeCallbacks); pendingSave = null
        latestSave?.let { (id, doc) -> runtime.dispatch("notes.save", json("meetingId" to id, "doc" to doc)) }
        latestSave = null
    }
    private fun closeEditor() {
        flushSave()
        web?.let { editor ->
            getSystemService(InputMethodManager::class.java).hideSoftInputFromWindow(editor.windowToken, 0)
            editor.removeJavascriptInterface("AndroidNotes"); editor.stopLoading()
            (editor.parent as? ViewGroup)?.removeView(editor)
            editor.destroy()
        }
        web = null; webReady = false; editorLoaded = false
    }

    private fun labelButton(label: String, description: String, inverse: Boolean = false, click: () -> Unit): TextView = TextView(this).apply {
        text = label; contentDescription = this@OverlayService.text(description); gravity = Gravity.CENTER; textSize = 16f
        setTextColor(color(if (inverse) "onRecordingPill" else "textPrimary")); isFocusable = false; isClickable = true
        setOnClickListener { click() }
        background = android.graphics.drawable.RippleDrawable(ColorStateList.valueOf(color(if (inverse) "recordingPillBorder" else "sidebarSelection")), null, shape(color("textPrimary"), 8))
    }
    private fun openApp(platformAction: String? = null) {
        val intent = (platformAction?.let { InternalPlatformActions.intent(this, it) }
            ?: Intent(this, MainActivity::class.java))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        startActivity(intent)
    }
    private fun capture() {
        if (ProjectionCapture.active) { ProjectionCapture.capture(); camera?.flash() }
        else openApp("screen.capture")
    }
    private fun stopRecording() {
        stopArmed = false; main.removeCallbacks(disarm)
        ContextCompat.startForegroundService(this, Intent(this, RecordingService::class.java).setAction(RecordingService.STOP))
        updateControls()
    }
    private fun updateControls() {
        camera?.control = if (stopArmed) Control.STOP_CONFIRMED else Control.CAMERA
        camera?.contentDescription = text(if (stopArmed) "recording_stop" else "capture")
        stop?.control = if (stopArmed) Control.CANCEL else Control.STOP
        stop?.contentDescription = text(if (stopArmed) "cancel" else "recording_stop")
        camera?.invalidate(); stop?.invalidate()
    }

    private fun metrics(): Rect {
        return if (Build.VERSION.SDK_INT >= 30) windows.currentWindowMetrics.bounds else Rect(0, 0, resources.displayMetrics.widthPixels, resources.displayMetrics.heightPixels)
    }
    private fun safeTop(): Int = resources.getIdentifier("status_bar_height", "dimen", "android").takeIf { it > 0 }?.let(resources::getDimensionPixelSize) ?: px(24)
    private fun safeBottom(): Int = maxOf(imeHeight, resources.getIdentifier("navigation_bar_height", "dimen", "android").takeIf { it > 0 }?.let(resources::getDimensionPixelSize) ?: px(24))
    private fun compactWidth() = minOf(px(360), metrics().width() - px(16))
    private fun compactHeight() = minOf(px(480), metrics().height() - safeTop() - safeBottom() - px(16)).coerceAtLeast(px(160))
    private fun placement(width: Int, height: Int): WindowManager.LayoutParams {
        val bounds = metrics()
        val right = preferences.getBoolean("right", true)
        val pillY = safeTop() + preferences.getFloat("vertical", .5f).coerceIn(0f, 1f) * (bounds.height() - safeTop() - safeBottom() - px(151)).coerceAtLeast(0)
        val y = (pillY + px(151)/2f - height/2f).toInt()
        return WindowManager.LayoutParams(width, height, WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH or
                if (compact && panelFocused) 0 else WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE, PixelFormat.TRANSLUCENT).apply {
            gravity = Gravity.TOP or Gravity.LEFT
            x = if (right) bounds.width() - width - px(6) else px(6)
            this.y = y.coerceIn(safeTop(), maxOf(safeTop(), bounds.height() - safeBottom() - height))
            softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
        }
    }
    private fun replaceWindow(view: View, placement: WindowManager.LayoutParams) {
        root?.let { runCatching { windows.removeView(it) } }
        root = view; params = placement
        try { windows.addView(view, placement) } catch (error: Exception) { runtime.log.event("overlay.attach.failed", error); stopSelf() }
    }
    private fun focusPanel() {
        if (!compact || panelFocused) return
        panelFocused = true
        val p = params ?: return
        p.flags = p.flags and WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE.inv()
        root?.let { runCatching { windows.updateViewLayout(it, p) } }
    }
    private fun releasePanelFocus() {
        if (!compact || !panelFocused) return
        panelFocused = false
        web?.let { editor ->
            getSystemService(InputMethodManager::class.java).hideSoftInputFromWindow(editor.windowToken, 0)
            editor.clearFocus()
        }
        val p = params ?: return
        p.flags = p.flags or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
        root?.let { runCatching { windows.updateViewLayout(it, p) } }
    }
    private fun resizeCompact() {
        if (!compact) return
        val p = params ?: return
        p.height = compactHeight(); p.width = compactWidth()
        p.y = p.y.coerceIn(safeTop(), maxOf(safeTop(), metrics().height() - safeBottom() - p.height))
        root?.let { runCatching { windows.updateViewLayout(it, p) } }
    }
    private fun rememberPosition() {
        val p = params ?: return
        val bounds = metrics()
        val pillY = p.y + p.height / 2f - px(151) / 2f
        val available = (bounds.height() - safeTop() - safeBottom() - px(151)).coerceAtLeast(1)
        preferences.edit().putBoolean("right", p.x + p.width/2 > bounds.width()/2)
            .putFloat("vertical", ((pillY - safeTop()) / available).coerceIn(0f, 1f)).apply()
    }
    private fun dragListener(clickView: View) = object : View.OnTouchListener {
        private var initialX = 0; private var initialY = 0; private var downX = 0f; private var downY = 0f
        override fun onTouch(view: View, event: MotionEvent): Boolean {
            val p = params ?: return false
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> { initialX = p.x; initialY = p.y; downX = event.rawX; downY = event.rawY; dragging = false; return true }
                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - downX; val dy = event.rawY - downY
                    if (abs(dx) + abs(dy) > ViewConfiguration.get(this@OverlayService).scaledTouchSlop) dragging = true
                    if (dragging) {
                        p.x = (initialX + dx.toInt()).coerceIn(0, maxOf(0, metrics().width() - p.width))
                        p.y = (initialY + dy.toInt()).coerceIn(safeTop(), maxOf(safeTop(), metrics().height() - safeBottom() - p.height))
                        root?.let { runCatching { windows.updateViewLayout(it, p) } }
                    }
                    return true
                }
                MotionEvent.ACTION_UP -> {
                    if (dragging) {
                        p.x = if (p.x + p.width/2 > metrics().width()/2) metrics().width() - p.width - px(6) else px(6)
                        root?.let { runCatching { windows.updateViewLayout(it, p) } }; rememberPosition()
                    } else clickView.performClick()
                    return true
                }
                MotionEvent.ACTION_CANCEL -> { if (dragging) rememberPosition(); return true }
            }
            return false
        }
    }

    private inner class SignalView : View(this@OverlayService) {
        var level = 0f
        var paused = false
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        init { isClickable = true; isFocusable = true; importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_YES }
        override fun getAccessibilityClassName(): CharSequence = android.widget.Button::class.java.name
        override fun onDraw(canvas: Canvas) {
            val cx = width/2f; val scaled = sqrt(level.coerceAtLeast(0f)) * 4f
            val reduceMotion = Settings.Global.getFloat(contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f
            paint.color = color("recordingDot")
            paint.alpha = if (paused || reduceMotion) 255 else (155 + 100 * (1 + kotlin.math.sin(System.currentTimeMillis()/450.0)) / 2).toInt()
            canvas.drawCircle(cx, dp(11f), dp(4f), paint)
            paint.color = color("recordingLevel"); paint.alpha = if (paused) 90 else 255
            val amount = if (paused) 0f else scaled.coerceIn(0f, 1f)
            val first = dp(4f + 13f * amount); val second = dp(4f + 11f * amount)
            val cy = dp(31f)
            canvas.drawRoundRect(cx-dp(6f), cy-first/2, cx-dp(2f), cy+first/2, dp(2f), dp(2f), paint)
            canvas.drawRoundRect(cx+dp(2f), cy-second/2, cx+dp(6f), cy+second/2, dp(2f), dp(2f), paint)
        }
    }
    private enum class Control { CAMERA, STOP, STOP_CONFIRMED, CANCEL }
    private inner class ControlView(var control: Control) : View(this@OverlayService) {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        private var checked = false
        init { isClickable = true; isFocusable = true; importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_YES }
        override fun getAccessibilityClassName(): CharSequence = android.widget.Button::class.java.name
        fun flash() { checked = true; invalidate(); main.postDelayed({ checked = false; invalidate() }, 800) }
        override fun onDraw(canvas: Canvas) {
            val cx = width/2f; val cy = height/2f
            val primary = control in listOf(Control.CAMERA, Control.STOP_CONFIRMED)
            if (primary) {
                paint.style = Paint.Style.FILL; paint.color = color(if (control == Control.STOP_CONFIRMED) "recordingDot" else "recordingLevel")
                canvas.drawCircle(cx, cy, dp(21f), paint)
            }
            paint.color = color(if (control == Control.CAMERA) "recordingPill" else "onRecordingPill")
            paint.alpha = if (control == Control.STOP) 160 else 255
            paint.strokeWidth = dp(1.8f); paint.strokeCap = Paint.Cap.ROUND
            when {
                checked && control == Control.CAMERA -> { paint.style = Paint.Style.STROKE; canvas.drawLine(cx-dp(7f), cy, cx-dp(2f), cy+dp(5f), paint); canvas.drawLine(cx-dp(2f), cy+dp(5f), cx+dp(8f), cy-dp(6f), paint) }
                control == Control.CAMERA -> {
                    paint.style = Paint.Style.STROKE
                    canvas.drawRoundRect(RectF(cx-dp(10f), cy-dp(6f), cx+dp(10f), cy+dp(8f)), dp(3f), dp(3f), paint)
                    canvas.drawCircle(cx, cy+dp(1f), dp(4f), paint); canvas.drawLine(cx-dp(4f), cy-dp(9f), cx+dp(4f), cy-dp(9f), paint)
                }
                control == Control.CANCEL -> { paint.style = Paint.Style.STROKE; canvas.drawLine(cx-dp(5f), cy-dp(5f), cx+dp(5f), cy+dp(5f), paint); canvas.drawLine(cx-dp(5f), cy+dp(5f), cx+dp(5f), cy-dp(5f), paint) }
                else -> { paint.style = Paint.Style.FILL; canvas.drawRoundRect(cx-dp(6f), cy-dp(6f), cx+dp(6f), cy+dp(6f), dp(2f), dp(2f), paint) }
            }
        }
    }
    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        appearance = ""; applyAppearance(runtime.state.value.settings)
        if (compact) showCompact() else showPill()
    }
    override fun onDestroy() {
        main.removeCallbacks(disarm); pendingSave?.let(main::removeCallbacks)
        closeEditor(); destroyed = true; scope.cancel(); main.removeCallbacksAndMessages(null)
        root?.let { runCatching { windows.removeView(it) } }; root = null
        super.onDestroy()
    }
    /** Native JSON object key order may differ from TipTap's serialization order. */
    private fun canonical(value: Any?): String = when (value) {
        is JSONObject -> value.keys().asSequence().toList().sorted().joinToString(prefix = "{", postfix = "}") { JSONObject.quote(it) + ":" + canonical(value.opt(it)) }
        is org.json.JSONArray -> (0 until value.length()).joinToString(prefix = "[", postfix = "]") { canonical(value.opt(it)) }
        is String -> JSONObject.quote(value)
        null, JSONObject.NULL -> "null"
        else -> value.toString()
    }
    private fun clock(seconds: Double): String = "${seconds.toLong()/60}:${(seconds.toLong()%60).toString().padStart(2, '0')}"
}
