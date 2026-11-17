package app.bpeq.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import app.bpeq.BpeqApp
import app.bpeq.core.dsp.FreqGrid
import app.bpeq.core.model.Eq
import app.bpeq.core.tune.AutoTune
import app.bpeq.core.tune.Targets
import kotlinx.coroutines.launch

val MEAS_COLOR = Color(0xFFB0B7C3)
val TARGET_COLOR = Palette.good
val RESULT_COLOR = Palette.warm

@Composable
fun TuneScreen(modifier: Modifier = Modifier, onOpenEditor: () -> Unit) {
    val app = BpeqApp.instance
    val session = app.tune
    val st by session.state.collectAsState()
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val pickMeas = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let { session.loadCurve(ctx, it)?.let(session::setMeasurement) }
    }
    val pickTarget = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let { session.loadCurve(ctx, it)?.let(session::setCustomTarget) }
    }
    val types = arrayOf("text/*", "application/octet-stream", "application/vnd.ms-excel")

    val grid = FreqGrid.DISPLAY
    val meas = remember(st.measurement) { st.measurement?.normalized()?.resample(grid.freqs) }
    val target = remember(st.customTarget, st.targetId) { st.target?.normalized()?.resample(grid.freqs) }
    val eq = st.result?.eq ?: Eq.FLAT
    val predicted = remember(st.result, st.measurement) {
        st.measurement?.let { m -> st.result?.let { r -> m.normalized().let { n -> AutoTune.predicted(n, r.eq, grid) } } }
            ?.let { p -> val off = (target?.let { t -> grid.freqs.indices.filter { grid.freqs[it] in 300.0..3000.0 }.let { ix -> ix.sumOf { p[it] - t[it] } / ix.size } } ?: 0.0); DoubleArray(p.size) { p[it] - off } }
    }

    Column(modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("Auto-tune", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.padding(start = 4.dp))
        Text("Load your IEM's frequency response, pick a target, and BPEQ fits up to 10 Protocol Max filters.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(start = 4.dp))

        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)) {
            Column(Modifier.padding(6.dp)) {
                ResponseGraph(eq, null, Modifier.fillMaxWidth().height(220.dp), interactive = false, compact = false, showNodes = false,
                    overlays = listOfNotNull(
                        meas?.let { Overlay(it, MEAS_COLOR, width = 1.5f) },
                        target?.let { Overlay(it, TARGET_COLOR, dashed = true) },
                        predicted?.let { Overlay(it, RESULT_COLOR) },
                    ))
                Row(Modifier.padding(6.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Legend("IEM", MEAS_COLOR); Legend("Target", TARGET_COLOR); Legend("Result", RESULT_COLOR); Legend("EQ", MaterialTheme.colorScheme.primary)
                }
            }
        }

        Section("1. IEM measurement") {
            Text(st.measurement?.let { "${it.name} · ${it.freqs.size} points" } ?: "squig.link / Crinacle / AutoEQ CSV or TXT (frequency, dB).",
                style = MaterialTheme.typography.bodySmall)
            FilledTonalButton({ pickMeas.launch(types) }, Modifier.fillMaxWidth()) { Text(if (st.measurement == null) "Load measurement file" else "Load another") }
        }

        Section("2. Target") {
            Targets.ALL.forEach { t ->
                Row(Modifier.fillMaxWidth().selectable(st.targetId == t.id, role = Role.RadioButton) { session.setTarget(t.id) },
                    verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(st.targetId == t.id, null)
                    Column(Modifier.padding(start = 8.dp)) {
                        Text(t.name, style = MaterialTheme.typography.bodyMedium)
                        Text(t.description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            st.customTarget?.let { Text("Custom: ${it.name}", color = Palette.good, style = MaterialTheme.typography.bodyMedium) }
            TextButton({ pickTarget.launch(types) }) { Text("Load a custom target file") }
        }

        Section("3. Settings") {
            Stepper("Filters", st.settings.maxFilters, 1..10) { v -> session.update { it.copy(settings = it.settings.copy(maxFilters = v), result = null) } }
            Stepper("Max boost per filter (dB)", st.settings.maxBoostDb.toInt(), 0..10) { v -> session.update { it.copy(settings = it.settings.copy(maxBoostDb = v.toDouble()), result = null) } }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Fit up to", Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                Segmented(listOf(10_000.0, 16_000.0), st.settings.fitMaxHz, { if (it < 12_000) "10 kHz" else "16 kHz" },
                    { v -> session.update { it.copy(settings = it.settings.copy(fitMaxHz = v, softAboveHz = minOf(10_000.0, v)), result = null) } })
            }
            Row(Modifier.fillMaxWidth().toggleable(st.settings.useShelves, role = Role.Switch) { v -> session.update { it.copy(settings = it.settings.copy(useShelves = v), result = null) } },
                verticalAlignment = Alignment.CenterVertically) {
                Text("Use bass and treble shelves", Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                Switch(st.settings.useShelves, null)
            }
        }

        Button({ scope.launch { session.run() } }, Modifier.fillMaxWidth().height(52.dp), enabled = st.measurement != null && st.target != null && !st.running) {
            if (st.running) { CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp); Text("  Fitting…") } else Text("Auto-tune")
        }
        st.result?.let { r ->
            Section("Result") {
                Text("Error vs target: ${fmtNum(r.rmsBefore, 1)} dB → ${fmtNum(r.rmsAfter, 1)} dB RMS · ${r.eq.bands.size} filters · ${r.millis} ms",
                    style = MaterialTheme.typography.bodyMedium)
                r.eq.bands.forEachIndexed { i, b ->
                    Text("${i + 1}. ${b.type.short} ${fmtFreq(b.freqHz)} Hz ${fmtGain(b.gainDb)} dB Q ${fmtQ(b.q)}", style = MaterialTheme.typography.bodySmall)
                }
                Button({ if (session.createProfile() != null) onOpenEditor() }, Modifier.fillMaxWidth()) { Text("Save as new profile") }
            }
        }
    }
}

@Composable
private fun Section(title: String, content: @Composable () -> Unit) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)) {
        Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            content()
        }
    }
}

@Composable
private fun Stepper(label: String, value: Int, range: IntRange, onChange: (Int) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
        OutlinedButton({ onChange((value - 1).coerceIn(range)) }, enabled = value > range.first, modifier = Modifier.size(44.dp),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp)) { Text("−") }
        Text("$value", Modifier.width(40.dp), textAlign = androidx.compose.ui.text.style.TextAlign.Center, style = MaterialTheme.typography.titleMedium)
        OutlinedButton({ onChange((value + 1).coerceIn(range)) }, enabled = value < range.last, modifier = Modifier.size(44.dp),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp)) { Text("+") }
    }
}

@Composable
fun Legend(label: String, color: Color) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        androidx.compose.material3.Surface(color = color, modifier = Modifier.size(width = 14.dp, height = 3.dp)) {}
        Text(" $label", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
