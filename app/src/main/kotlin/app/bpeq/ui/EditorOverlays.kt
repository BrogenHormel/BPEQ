package app.bpeq.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import app.bpeq.core.device.Planner
import app.bpeq.core.dsp.FreqGrid
import app.bpeq.core.dsp.Response
import app.bpeq.core.model.Eq
import app.bpeq.core.model.Tuning
import app.bpeq.core.tune.AutoTune
import app.bpeq.core.tune.Rebuild

/** Which overlays are visible on the editor graph. */
data class OverlaySet(val device: Boolean = true, val iem: Boolean = true, val target: Boolean = true, val result: Boolean = true)

val OverlaySaver = listSaver<OverlaySet, Boolean>(
    save = { listOf(it.device, it.iem, it.target, it.result) }, restore = { OverlaySet(it[0], it[1], it[2], it[3]) })

@Composable
fun OverlayChips(ov: OverlaySet, hasTuning: Boolean, onChange: (OverlaySet) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 2.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        OverlayChip("Device", ov.device, Palette.warm) { onChange(ov.copy(device = it)) }
        if (hasTuning) {
            OverlayChip("IEM", ov.iem, MEAS_COLOR) { onChange(ov.copy(iem = it)) }
            OverlayChip("Target", ov.target, TARGET_COLOR) { onChange(ov.copy(target = it)) }
            OverlayChip("Result", ov.result, RESULT_COLOR) { onChange(ov.copy(result = it)) }
        }
    }
}

@Composable
private fun OverlayChip(label: String, on: Boolean, color: Color, onChange: (Boolean) -> Unit) {
    FilterChip(on, { onChange(!on) }, label = { Text(label) },
        leadingIcon = { Surface(color = color, shape = CircleShape, modifier = Modifier.size(8.dp)) {} },
        modifier = Modifier.height(30.dp))
}

fun overlaysFor(eq: Eq, plan: Planner.Result, tuning: Tuning?, ov: OverlaySet): List<Overlay> {
    val grid = FreqGrid.DISPLAY
    val out = ArrayList<Overlay>()
    if (tuning != null) {
        val m = tuning.measurement.resample(grid.freqs)
        val t = tuning.target.resample(grid.freqs)
        if (ov.iem) out += Overlay(m, MEAS_COLOR, width = 1.5f)
        if (ov.target) out += Overlay(t, TARGET_COLOR, dashed = true)
        if (ov.result) {
            val r = AutoTune.predicted(tuning.measurement, eq, grid)
            val ix = grid.freqs.indices.filter { grid.freqs[it] in 300.0..3000.0 }
            val off = ix.sumOf { r[it] - t[it] } / ix.size
            out += Overlay(DoubleArray(r.size) { r[it] - off }, RESULT_COLOR)
        }
    }
    if (ov.device) {
        // What the dongle will actually do after clamping/rounding (shape; the preamp is shown separately).
        val d = Planner.deviceCurve(plan, grid)
        out += Overlay(DoubleArray(d.size) { d[it] - plan.preampDb }, Palette.warm, dashed = true, width = 1.5f)
    }
    return out
}

@Composable
fun RebuildDialog(eq: Eq, r: Rebuild.Result, onDismiss: () -> Unit, onApply: () -> Unit) {
    val grid = FreqGrid.DISPLAY
    val original = remember(eq) { Response.ideal(eq.bands, grid) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Rebuild for the device") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                ResponseGraph(r.eq, null, Modifier.fillMaxWidth().height(160.dp), interactive = false, compact = false, showNodes = false,
                    overlays = listOf(Overlay(original, Palette.warm, dashed = true)))
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) { Legend("Original", Palette.warm); Legend("Rebuilt", MaterialTheme.colorScheme.primary) }
                r.changes.forEach { Text("• $it", style = MaterialTheme.typography.bodySmall) }
                Text("Largest difference in shape: ${fmtNum(r.maxDiffDb, 2)} dB" + if (!r.ok) " (still not fully storable)" else "",
                    style = MaterialTheme.typography.bodyMedium, color = if (r.maxDiffDb < 0.5) Palette.good else Palette.warn)
            }
        },
        confirmButton = { Button(onClick = onApply, enabled = r.changes.isNotEmpty()) { Text("Apply") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
