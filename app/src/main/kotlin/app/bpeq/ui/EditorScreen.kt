package app.bpeq.ui

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.bpeq.BpeqApp
import app.bpeq.data.Applier
import app.bpeq.core.device.MaxLimits
import app.bpeq.core.device.Planner
import app.bpeq.core.tune.Rebuild
import app.bpeq.core.tune.ToneBlend
import androidx.compose.runtime.saveable.rememberSaveable
import app.bpeq.core.model.Band
import app.bpeq.core.model.FilterType
import app.bpeq.core.model.PreampMode
import app.bpeq.device.DeviceStatus
import kotlinx.coroutines.launch

private val FREQ = Scale(20.0, 20000.0, log = true)
private val GAIN = Scale(MaxLimits.GAIN_MIN, MaxLimits.GAIN_MAX)
private val QS = Scale(MaxLimits.Q_MIN, MaxLimits.Q_MAX, log = true)
private val PREAMP = Scale(MaxLimits.PREAMP_MIN.toDouble(), 0.0)

@Composable
fun EditorScreen(modifier: Modifier = Modifier, onOpenLibrary: () -> Unit) {
    val app = BpeqApp.instance
    val ed = app.editor
    val profiles by app.library.profiles.collectAsState()
    val currentId by app.library.currentId.collectAsState()
    val profile = profiles.firstOrNull { it.id == currentId } ?: return
    val eq = profile.working
    val selected by ed.selected.collectAsState()
    val compareId by ed.compareWith.collectAsState()
    val plan = remember(eq) { Planner.plan(eq) }
    var ov by rememberSaveable(stateSaver = OverlaySaver) { mutableStateOf(OverlaySet()) }
    var rebuild by remember { mutableStateOf<Rebuild.Result?>(null) }
    rebuild?.let { r -> RebuildDialog(eq, r, onDismiss = { rebuild = null }, onApply = { ed.edit { r.eq }; rebuild = null }) }

    Column(modifier.fillMaxSize()) {
        // Header: profile name (tap: library), undo/redo, save
        Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            var switcher by remember { mutableStateOf(false) }
            Column(Modifier.weight(1f).clickable { switcher = true }) {
                QuickSwitcher(switcher, profile, onDismiss = { switcher = false }, onOpenLibrary = onOpenLibrary)
                Text(profile.name, style = MaterialTheme.typography.titleLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(if (profile.dirty) "Unsaved changes" else "Saved" + if (profile.iem.isNotBlank()) " · ${profile.iem}" else "",
                    style = MaterialTheme.typography.labelSmall,
                    color = if (profile.dirty) Palette.warm else MaterialTheme.colorScheme.onSurfaceVariant)
            }
            TextButton(onClick = ed::undo, enabled = profile.undo.isNotEmpty()) { Text("↶", style = MaterialTheme.typography.titleLarge) }
            TextButton(onClick = ed::redo, enabled = profile.redo.isNotEmpty()) { Text("↷", style = MaterialTheme.typography.titleLarge) }
            if (profile.dirty) TextButton(onClick = ed::save) { Text("Save") }
        }

        LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            item {
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)) {
                    ResponseGraph(
                        eq, selected, Modifier.fillMaxWidth().height(230.dp).padding(6.dp),
                        overlays = overlaysFor(eq, plan, profile.tuning, ov) + compareOverlay(compareId, profile.id),
                        totalBands = if (eq.tone.isZero) null else remember(eq) { ToneBlend.apply(eq).bands },
                        totalOffsetDb = ToneBlend.levelDb(eq.tone),
                        onSelect = ed::select,
                        onDragStart = ed::beginDrag,
                        onDrag = { id, f, g -> ed.dragBand(id) { it.copy(freqHz = MaxLimits.clampFreq(f), gainDb = MaxLimits.clampGain(g)) } },
                        onPinch = { id, k -> ed.dragBand(id) { it.copy(q = MaxLimits.clampQ(Math.round(it.q * k * 100) / 100.0)) } },
                        onDragEnd = ed::endDrag,
                    )
                    OverlayChips(ov, profile.tuning != null) { ov = it }
                    CompareChip(compareId)
                }
            }
            if (!plan.ok || plan.clampNotes.isNotEmpty() || plan.warnings.isNotEmpty()) item {
                IssuesCard(plan.issues, plan.warnings, plan.clampNotes, if (plan.unstorable.isNotEmpty()) ({ rebuild = Rebuild.rebuild(eq) }) else null)
            }
            item { PreampCard(eq.preampMode, eq.manualPreampDb, plan.preampDb - plan.levelDb) }
            item { ToneCard(eq.tone) }
            itemsIndexed(eq.bands, key = { _, b -> b.id }) { i, b ->
                BandCard(i, b, b.id == selected, plan.unstorable.any { it.bandIndex == i })
            }
            item {
                OutlinedButton(onClick = { ed.addBand() }, enabled = eq.bands.size < MaxLimits.BANDS, modifier = Modifier.fillMaxWidth()) {
                    Text(if (eq.bands.size < MaxLimits.BANDS) "+ Add band (${eq.bands.size}/${MaxLimits.BANDS})" else "All ${MaxLimits.BANDS} bands used")
                }
            }
            item { Spacer(Modifier.height(8.dp)) }
        }
        SendBar(profile.id, plan.ok)
    }
}

@Composable
private fun IssuesCard(issues: List<String>, warnings: List<String>, notes: List<String>, onRebuild: (() -> Unit)?) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            issues.forEach { Text("⚠ $it", color = Palette.bad, style = MaterialTheme.typography.bodySmall) }
            warnings.forEach { Text("⚠ $it", color = Palette.warn, style = MaterialTheme.typography.bodySmall) }
            notes.forEach { Text("ℹ $it", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall) }
            if (onRebuild != null) FilledTonalButton(onClick = onRebuild, modifier = Modifier.fillMaxWidth().padding(top = 6.dp)) {
                Text("Rebuild for the device")
            }
        }
    }
}

@Composable
/**
 * [shownDb]: the preamp relative to the curve as drawn. It differs from the device value only when shelves are sent
 * flipped (Planner.Result.levelDb); a manual preamp is stored in the same terms, so switching modes keeps the level.
 */
private fun PreampCard(mode: PreampMode, manual: Double, shownDb: Double) {
    val ed = BpeqApp.instance.editor
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)) {
        Column(Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Preamp", style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                Segmented(listOf(PreampMode.AUTO, PreampMode.MANUAL), mode, { if (it == PreampMode.AUTO) "Auto" else "Manual" },
                    { m -> ed.edit { it.copy(preampMode = m, manualPreampDb = if (m == PreampMode.MANUAL) shownDb else it.manualPreampDb) } })
            }
            if (mode == PreampMode.MANUAL) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    FineSlider(manual, PREAMP, ed::beginDrag, { v -> ed.dragTo { it.copy(manualPreampDb = Math.round(v).toDouble()) } }, ed::endDrag,
                        Modifier.weight(1f).padding(end = 8.dp))
                    ValueChip("dB", fmtNum(manual, 1), fmtNum(manual, 1), { v -> ed.edit { it.copy(manualPreampDb = v.coerceIn(-30.0, 0.0)) } })
                }
            } else {
                Text("${fmtNum(shownDb, 1)} dB · keeps the loudest point at or below 0 dB (anti-clipping)",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

private val TYPES = listOf(FilterType.PEAK, FilterType.LOW_SHELF, FilterType.HIGH_SHELF)

@Composable
private fun BandCard(index: Int, b: Band, selected: Boolean, unstorable: Boolean) {
    val ed = BpeqApp.instance.editor
    val border = if (selected) Palette.warm else Color.Transparent
    Card(
        colors = CardDefaults.cardColors(containerColor = if (selected) MaterialTheme.colorScheme.surfaceContainerHigh else MaterialTheme.colorScheme.surfaceContainer),
        modifier = Modifier.fillMaxWidth().animateContentSize().clickable { ed.select(if (selected) null else b.id) },
    ) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Surface(shape = CircleShape, color = if (selected) Palette.warm else MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(24.dp).alpha(if (b.enabled) 1f else 0.4f)) {
                    Box(contentAlignment = Alignment.Center) { Text("${index + 1}", color = Color.Black, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold) }
                }
                Spacer(Modifier.width(10.dp))
                Text("${b.type.short}  ${fmtFreq(b.freqHz)} Hz  ${fmtGain(b.gainDb)} dB  Q ${fmtQ(b.q)}",
                    style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f).alpha(if (b.enabled) 1f else 0.5f),
                    color = if (unstorable) Palette.bad else MaterialTheme.colorScheme.onSurface)
                Switch(b.enabled, { on -> ed.updateBand(b.id) { it.copy(enabled = on) } })
            }
            if (selected) {
                Spacer(Modifier.height(8.dp))
                Segmented(TYPES, b.type, { it.label }, { t -> ed.updateBand(b.id) { it.copy(type = t) } }, Modifier.fillMaxWidth())
                SliderRow("Freq", fmtFreq(b.freqHz), "%.0f".format(java.util.Locale.US, b.freqHz), "Hz", b.freqHz, FREQ, null,
                    { v -> ed.dragBand(b.id) { it.copy(freqHz = Math.round(v).toDouble()) } },
                    { v -> ed.updateBand(b.id) { it.copy(freqHz = MaxLimits.clampFreq(Math.round(v).toDouble())) } })
                SliderRow("Gain", fmtGain(b.gainDb), fmtNum(b.gainDb, 1), "dB", b.gainDb, GAIN, 0.0,
                    { v -> ed.dragBand(b.id) { it.copy(gainDb = Math.round(v * 10) / 10.0) } },
                    { v -> ed.updateBand(b.id) { it.copy(gainDb = MaxLimits.clampGain(v)) } })
                SliderRow("Q", fmtQ(b.q), fmtNum(b.q, 2), "", b.q, QS, null,
                    { v -> ed.dragBand(b.id) { it.copy(q = Math.round(v * 100) / 100.0) } },
                    { v -> ed.updateBand(b.id) { it.copy(q = MaxLimits.clampQ(v)) } })
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = { ed.removeBand(b.id) }) { Text("Delete band", color = Palette.bad) }
                }
            }
        }
    }
}

@Composable
private fun SliderRow(label: String, shown: String, initial: String, unit: String, value: Double, scale: Scale, centre: Double?,
                      onDrag: (Double) -> Unit, onExact: (Double) -> Unit) {
    val ed = BpeqApp.instance.editor
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = MaterialTheme.typography.labelMedium, modifier = Modifier.width(40.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
        FineSlider(value, scale, ed::beginDrag, onDrag, ed::endDrag, Modifier.weight(1f).padding(horizontal = 4.dp), centre = centre)
        ValueChip("", shown, initial, onExact, unit, Modifier.width(76.dp))
    }
}

/** Device status + Send, at the bottom for one-handed reach. */
@Composable
fun SendBar(profileId: String, canSend: Boolean) {
    val app = BpeqApp.instance
    val status by app.devices.status.collectAsState()
    val state by app.devices.lastState.collectAsState()
    val onDevice by app.library.onDevice.collectAsState()
    val firstDone by app.devices.firstWriteDone.collectAsState()
    val abFlatFor by Applier.abFlatFor.collectAsState()
    val profile = app.library.profiles.collectAsState().value.firstOrNull { it.id == profileId } ?: return
    val scope = rememberCoroutineScope()
    var preview by remember { mutableStateOf<PendingWrite?>(null) }
    val matches = state?.let { Planner.matchesDevice(profile.working, it) } == true
    val (label, color) = when {
        status is DeviceStatus.Busy -> (status as DeviceStatus.Busy).what + "…" to Palette.warn
        status == DeviceStatus.NoDevice -> "No device" to MaterialTheme.colorScheme.outline
        status == DeviceStatus.NeedsPermission || status == DeviceStatus.PermissionDenied -> "No permission" to Palette.warn
        abFlatFor == profile.id -> "EQ off (A/B)" to Palette.warn
        matches -> "On device" to Palette.good
        status.canTalk -> (if (status == DeviceStatus.Simulated) "Simulator" else "Connected") to Palette.accent
        else -> "Error" to Palette.bad
    }
    Surface(color = MaterialTheme.colorScheme.surfaceContainer, modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Row(Modifier.weight(1f).background(color.copy(alpha = 0.15f), RoundedCornerShape(16.dp)).padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically) {
                Surface(shape = CircleShape, color = color, modifier = Modifier.size(8.dp)) {}
                Spacer(Modifier.width(8.dp))
                Text(label, style = MaterialTheme.typography.labelLarge, maxLines = 1)
            }
            Spacer(Modifier.width(8.dp))
            if (status == DeviceStatus.NeedsPermission || status == DeviceStatus.PermissionDenied) {
                FilledTonalButton(onClick = { app.devices.requestPermission() }) { Text("Allow USB") }
            } else {
                if (status.canTalk) AbButton(profile.id, matches && onDevice?.first == profile.id)
                Button(
                    onClick = {
                        val plan = Planner.plan(profile.working).plan ?: run { scope.launch { app.editor.send() }; return@Button }
                        if (!firstDone) preview = PendingWrite(profile.name, plan, state?.slot ?: 0x01)
                        else scope.launch { app.editor.send() }
                    },
                    enabled = status.canTalk && canSend && !matches,
                ) { Text(if (matches) "Sent" else "Send to device") }
            }
        }
    }
    preview?.let { p -> WritePreviewDialog(p, onDismiss = { preview = null }, onConfirm = { preview = null; scope.launch { app.editor.send() } }) }
}
