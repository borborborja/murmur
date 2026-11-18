package dev.murmur.android.ui

import android.content.Context
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.unit.dp
import org.json.JSONObject
import java.util.Locale

/** Palettes come from the original neutral design tokens, including alpha order. */
class MurmurPalette(private val values: JSONObject, val dark: Boolean) {
    fun color(key: String): Color {
        val hex = values.getString(key).removePrefix("#")
        val raw = hex.take(6).toLong(16)
        val alpha = if (hex.length == 8) hex.takeLast(2).toLong(16) else 255L
        return Color((alpha shl 24) or raw)
    }
    val background get() = color("background")
    val surface get() = color("sidebar")
    val subtle get() = color("fillSubtle")
    val text get() = color("textPrimary")
    val secondary get() = color("textSecondary")
    val border get() = color("separator")
    val accent get() = color("accent")
    val onAccent get() = color("onAccent")
    val timestamp get() = color("timestamp")
    val recording get() = color("recordingDot")
}

class AppStrings(context: Context, val language: String) {
    private val namespaces = mutableMapOf<String, JSONObject>()
    private val assets = context.assets
    fun text(key: String): String {
        val split = key.split(':', limit = 2)
        val namespace = if (split.size == 2) split[0] else "android"
        val path = if (split.size == 2) split[1] else key
        val root = namespaces.getOrPut(namespace) {
            runCatching { JSONObject(assets.open("locales/$language/$namespace.json").bufferedReader().use { it.readText() }) }.getOrDefault(JSONObject())
        }
        var current: Any? = root
        for (part in path.split('.')) current = (current as? JSONObject)?.opt(part)
        return current as? String ?: key
    }
}

val LocalPalette = staticCompositionLocalOf<MurmurPalette> { error("Murmur palette missing") }
val LocalStrings = staticCompositionLocalOf<AppStrings> { error("Murmur strings missing") }

@Composable fun tr(key: String): String = LocalStrings.current.text(key)

@Composable
fun MurmurTheme(settings: JSONObject, content: @Composable () -> Unit) {
    val context = LocalContext.current
    val systemDark = isSystemInDarkTheme()
    val dark = when (settings.optString("mode", "system")) { "dark" -> true; "light" -> false; else -> systemDark }
    val theme = settings.optString("theme", "acid").takeIf { it in setOf("acid", "mono", "formigo") } ?: "acid"
    val language = settings.optString("uiLanguage", "system").takeIf { it in setOf("ca", "es", "en") }
        ?: Locale.getDefault().language.takeIf { it in setOf("ca", "es", "en") } ?: "en"
    val strings = remember(language, context) { AppStrings(context, language) }
    val palette = remember(theme, dark, context) {
        val tokens = JSONObject(context.assets.open("murmur-tokens.json").bufferedReader().use { it.readText() })
        MurmurPalette(tokens.getJSONObject("themes").getJSONObject(theme).getJSONObject(if (dark) "dark" else "light"), dark)
    }
    val baseColors = if (dark) darkColorScheme(
        primary = palette.timestamp, onPrimary = palette.background,
        secondary = palette.text, onSecondary = palette.background,
        background = palette.background, onBackground = palette.text,
        surface = palette.background, onSurface = palette.text,
        surfaceVariant = palette.subtle, onSurfaceVariant = palette.secondary,
        outline = palette.border, error = palette.color("recordingLabel")
    ) else lightColorScheme(
        primary = palette.timestamp, onPrimary = palette.background,
        secondary = palette.text, onSecondary = palette.background,
        background = palette.background, onBackground = palette.text,
        surface = palette.background, onSurface = palette.text,
        surfaceVariant = palette.subtle, onSurfaceVariant = palette.secondary,
        outline = palette.border, error = palette.color("recordingLabel")
    )
    val colors = baseColors.copy(
        primaryContainer = palette.accent, onPrimaryContainer = palette.onAccent,
        secondaryContainer = palette.color("sidebarSelection"), onSecondaryContainer = palette.text,
        tertiary = palette.timestamp, onTertiary = palette.background,
        tertiaryContainer = palette.subtle, onTertiaryContainer = palette.text,
        surfaceTint = palette.background.copy(alpha = 0f),
        outlineVariant = palette.border,
        inverseSurface = palette.text, inverseOnSurface = palette.background, inversePrimary = palette.accent,
        errorContainer = palette.color("recordingLabel").copy(alpha = .12f), onErrorContainer = palette.color("recordingLabel")
    )
    CompositionLocalProvider(LocalPalette provides palette, LocalStrings provides strings) {
        MaterialTheme(colorScheme = colors, shapes = Shapes(
            extraSmall = RoundedCornerShape(5.dp), small = RoundedCornerShape(8.dp),
            medium = RoundedCornerShape(12.dp), large = RoundedCornerShape(20.dp), extraLarge = RoundedCornerShape(28.dp)
        ), content = content)
    }
}
