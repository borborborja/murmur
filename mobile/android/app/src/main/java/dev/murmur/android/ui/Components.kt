package dev.murmur.android.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

typealias Dispatch = (String, JSONObject) -> Unit
fun payload(vararg fields: Pair<String, Any?>) = JSONObject().apply { fields.forEach { put(it.first, it.second ?: JSONObject.NULL) } }
fun JSONArray.objects(): List<JSONObject> = (0 until length()).mapNotNull { optJSONObject(it) }
fun JSONArray.strings(): List<String> = (0 until length()).mapNotNull { optString(it).takeIf(String::isNotBlank) }
fun JSONObject.array(key: String): List<JSONObject> = optJSONArray(key)?.objects().orEmpty()
fun JSONObject.textOrNull(key: String): String? = optString(key).takeIf { !isNull(key) && it.isNotBlank() && it != "null" }
fun clock(seconds: Double): String {
    val value = seconds.toLong().coerceAtLeast(0)
    return if (value >= 3600) "%d:%02d:%02d".format(Locale.ROOT, value / 3600, value / 60 % 60, value % 60)
    else "%d:%02d".format(Locale.ROOT, value / 60, value % 60)
}

@Composable internal fun modelName(id: String): String = when (id) {
    "llm-mobile" -> tr("model_llm_mobile")
    "whisper-small" -> tr("model_whisper_small")
    else -> tr("models:names.$id")
}

/** Pairing QR colours are fixed to the original light palette for camera readability. */
@Composable internal fun PairingQr(value: String, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val colors = remember(context) {
        val tokens = JSONObject(context.assets.open("murmur-tokens.json").bufferedReader().use { it.readText() })
        val palette = MurmurPalette(tokens.getJSONObject("themes").getJSONObject("acid").getJSONObject("light"), false)
        palette.background to palette.text
    }
    val matrix = remember(value) {
        runCatching { QRCodeWriter().encode(value, BarcodeFormat.QR_CODE, 256, 256, mapOf(EncodeHintType.MARGIN to 3)) }.getOrNull()
    }
    Canvas(modifier) {
        drawRect(colors.first)
        matrix?.let { qr ->
            val cell = minOf(size.width / qr.width, size.height / qr.height)
            val origin = Offset((size.width - qr.width * cell) / 2, (size.height - qr.height * cell) / 2)
            for (y in 0 until qr.height) for (x in 0 until qr.width) if (qr[x, y]) {
                drawRect(colors.second, origin + Offset(x * cell, y * cell), Size(cell + .1f, cell + .1f))
            }
        }
    }
}

@Composable fun MurmurLogo(modifier: Modifier = Modifier) {
    val palette = LocalPalette.current
    Canvas(modifier.clip(RoundedCornerShape(12.dp)).background(palette.color("logoBackground"))) {
        scale(size.width / 200f, size.height / 200f, pivot = androidx.compose.ui.geometry.Offset.Zero) {
            val m = Path().apply {
                moveTo(60f, 140f); lineTo(60f, 100f)
                cubicTo(60f, 76f, 96f, 76f, 96f, 100f); lineTo(96f, 140f)
                moveTo(96f, 100f); cubicTo(96f, 76f, 132f, 76f, 132f, 100f); lineTo(132f, 140f)
            }
            translate(8f, -15f) { drawPath(m, palette.color("logoBar2"), style = Stroke(18f, cap = StrokeCap.Round, join = StrokeJoin.Round)) }
            translate(-4f, -3f) {
                drawPath(m, palette.color("logoBackground"), style = Stroke(32f, cap = StrokeCap.Round, join = StrokeJoin.Round))
                drawPath(m, palette.color("logoBar1"), style = Stroke(18f, cap = StrokeCap.Round, join = StrokeJoin.Round))
            }
        }
    }
}

@Composable fun SectionTitle(text: String, modifier: Modifier = Modifier) {
    Text(text, modifier.padding(top = 16.dp, bottom = 8.dp), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
}

@Composable fun Hint(text: String, modifier: Modifier = Modifier) {
    Text(text, modifier, color = LocalPalette.current.secondary, style = MaterialTheme.typography.bodySmall, lineHeight = MaterialTheme.typography.bodySmall.lineHeight * 1.3f)
}

@Composable fun ActionRow(text: String, icon: ImageVector? = null, subtitle: String? = null, onClick: () -> Unit) {
    Surface(onClick = onClick, color = LocalPalette.current.subtle, shape = RoundedCornerShape(12.dp), modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            if (icon != null) Icon(icon, contentDescription = null, modifier = Modifier.size(20.dp))
            Column(Modifier.weight(1f)) { Text(text, style = MaterialTheme.typography.bodyMedium); if (subtitle != null) Hint(subtitle) }
        }
    }
}

@Composable fun ToggleRow(text: String, checked: Boolean, subtitle: String? = null, onCheckedChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Column(Modifier.weight(1f)) { Text(text, style = MaterialTheme.typography.bodyMedium); if (subtitle != null) Hint(subtitle) }
        Switch(checked = checked, onCheckedChange = onCheckedChange, colors = SwitchDefaults.colors(
            checkedTrackColor = LocalPalette.current.color("toggleOn"), checkedThumbColor = LocalPalette.current.color("toggleKnob")
        ))
    }
}

@Composable fun ChoiceRow(label: String, value: String, choices: List<Pair<String, String>>, onSelect: (String) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        OutlinedButton(onClick = { expanded = true }, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(10.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(label, color = LocalPalette.current.text)
                Text(choices.firstOrNull { it.first == value }?.second ?: value)
            }
        }
        DropdownMenu(expanded, { expanded = false }) {
            choices.forEach { (id, text) -> DropdownMenuItem(text = { Text(text) }, onClick = { expanded = false; onSelect(id) }) }
        }
    }
}

@Composable fun EditDialog(title: String, initial: String, multiline: Boolean = false, onDismiss: () -> Unit, onSave: (String) -> Unit) {
    var value by remember(initial) { mutableStateOf(initial) }
    AlertDialog(onDismissRequest = onDismiss, title = { Text(title) }, text = {
        OutlinedTextField(value, { value = it }, modifier = Modifier.fillMaxWidth(), singleLine = !multiline, minLines = if (multiline) 4 else 1)
    }, confirmButton = { TextButton(onClick = { onSave(value); onDismiss() }) { Text(tr("save")) } }, dismissButton = { TextButton(onClick = onDismiss) { Text(tr("cancel")) } })
}
