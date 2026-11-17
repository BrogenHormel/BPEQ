package app.bpeq.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.ln

/** Maps a value to 0..1 and back (linear or logarithmic). */
class Scale(val min: Double, val max: Double, val log: Boolean = false) {
    fun toT(v: Double): Float = (if (log) ln(v / min) / ln(max / min) else (v - min) / (max - min)).toFloat().coerceIn(0f, 1f)
    fun fromT(t: Float): Double { val c = t.coerceIn(0f, 1f).toDouble(); return if (log) min * exp(c * ln(max / min)) else min + c * (max - min) }
}

/**
 * Relative slider for precise one-handed edits: dragging moves the value from where it is (no jump on touch),
 * at full speed when the finger moves fast and at 1/5 speed when it moves slowly.
 */
@Composable
fun FineSlider(
    value: Double,
    scale: Scale,
    onStart: () -> Unit,
    onChange: (Double) -> Unit,
    onEnd: () -> Unit,
    modifier: Modifier = Modifier,
    color: Color = MaterialTheme.colorScheme.primary,
    centre: Double? = null,
) {
    val cur by rememberUpdatedState(value)
    val track = MaterialTheme.colorScheme.surfaceVariant
    Box(
        modifier.fillMaxWidth().height(36.dp).pointerInput(scale) {
            awaitEachGesture {
                val down = awaitFirstDown()
                var t = scale.toT(cur)
                var lastTime = down.uptimeMillis
                var started = false
                while (true) {
                    val ev = awaitPointerEvent()
                    val c = ev.changes.firstOrNull { it.id == down.id } ?: break
                    if (!c.pressed) break
                    val dx = c.positionChange().x
                    if (dx != 0f) {
                        if (!started) { started = true; onStart() }
                        val dt = (c.uptimeMillis - lastTime).coerceAtLeast(1)
                        val speed = abs(dx) / dt // px per ms
                        val factor = if (speed < 0.25f) 0.2f else if (speed < 0.8f) 0.5f else 1f
                        t = (t + dx / size.width * factor).coerceIn(0f, 1f)
                        onChange(scale.fromT(t))
                        c.consume()
                    }
                    lastTime = c.uptimeMillis
                }
                if (started) onEnd()
            }
        },
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.fillMaxWidth().height(36.dp)) {
            val y = size.height / 2
            val r = 10.dp.toPx()
            drawRoundRect(track, Offset(0f, y - 3.dp.toPx()), Size(size.width, 6.dp.toPx()), CornerRadius(3.dp.toPx()))
            val x = scale.toT(value) * size.width
            val x0 = (centre?.let { scale.toT(it) } ?: 0f) * size.width
            drawRect(color, Offset(minOf(x, x0), y - 3.dp.toPx()), Size(abs(x - x0), 6.dp.toPx()))
            drawCircle(color, r, Offset(x.coerceIn(r, size.width - r), y))
        }
    }
}

/** A value shown as a tappable chip that opens an exact-entry dialog. */
@Composable
fun ValueChip(label: String, text: String, initial: String, onEntered: (Double) -> Unit, unit: String = "", modifier: Modifier = Modifier) {
    var open by remember { mutableStateOf(false) }
    Row(
        modifier.background(MaterialTheme.colorScheme.surfaceContainerHighest, RoundedCornerShape(8.dp))
            .clickable { open = true }.padding(horizontal = 10.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("$label ", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(text, style = MaterialTheme.typography.labelLarge)
    }
    if (open) NumberDialog("$label${if (unit.isNotEmpty()) " ($unit)" else ""}", initial,
        onDismiss = { open = false }, onDone = { open = false; onEntered(it) })
}

@Composable
fun NumberDialog(title: String, initial: String, onDismiss: () -> Unit, onDone: (Double) -> Unit) {
    var s by remember { mutableStateOf(initial) }
    val v = s.replace(',', '.').trim().removeSuffix("k").toDoubleOrNull()?.let { if (s.trim().endsWith("k")) it * 1000 else it }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(s, { s = it }, singleLine = true, isError = v == null,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal))
        },
        confirmButton = { TextButton(onClick = { v?.let(onDone) }, enabled = v != null) { Text("Set") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
fun <T> Segmented(options: List<T>, selected: T, label: (T) -> String, onSelect: (T) -> Unit, modifier: Modifier = Modifier) {
    SingleChoiceSegmentedButtonRow(modifier) {
        options.forEachIndexed { i, o ->
            SegmentedButton(selected = o == selected, onClick = { onSelect(o) },
                shape = SegmentedButtonDefaults.itemShape(i, options.size), label = { Text(label(o)) })
        }
    }
}

private val US = java.util.Locale.US
fun fmtFreq(f: Double): String = if (f >= 1000) String.format(US, "%.2f", f / 1000).trimEnd('0').trimEnd('.') + "k" else String.format(US, "%.0f", f)
fun fmtGain(g: Double): String = (if (g > 0.0) "+" else "") + String.format(US, "%.1f", g)
fun fmtQ(q: Double): String = String.format(US, "%.2f", q)
fun fmtNum(x: Double, decimals: Int): String = String.format(US, "%.${decimals}f", x)
