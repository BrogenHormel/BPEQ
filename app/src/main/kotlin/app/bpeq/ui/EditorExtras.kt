package app.bpeq.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.InputChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import app.bpeq.BpeqApp
import app.bpeq.core.dsp.FreqGrid
import app.bpeq.core.dsp.Response
import app.bpeq.core.model.Profile
import app.bpeq.core.model.Tone
import app.bpeq.core.tune.ToneBlend
import app.bpeq.data.Applier
import app.bpeq.device.DeviceManager
import kotlinx.coroutines.launch

val COMPARE_COLOR = Color(0xFFC792EA)

/** Must be a stable instance: FineSlider keys its gesture handler on the scale, so a new one per recomposition
 * would restart the drag after every value change. */
private val TONE_SCALE = Scale(-Tone.RANGE, Tone.RANGE)

/** Tone sliders (feature 8): bass shelf, treble tilt, ear gain, blended into the profile within 10 bands. */
@Composable
fun ToneCard(tone: Tone) {
    val ed = BpeqApp.instance.editor
    val scale = TONE_SCALE
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)) {
        Column(Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Tone", style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                if (!tone.isZero) TextButton({ ed.edit { it.copy(tone = Tone()) } }) { Text("Reset") }
            }
            listOf(
                Triple("Bass", tone.bassDb) { t: Tone, v: Double -> t.copy(bassDb = v) },
                Triple("Treble", tone.trebleDb) { t: Tone, v: Double -> t.copy(trebleDb = v) },
                Triple("Ear gain", tone.earGainDb) { t: Tone, v: Double -> t.copy(earGainDb = v) },
            ).forEach { (label, value, set) ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(label, style = MaterialTheme.typography.labelMedium, modifier = Modifier.width(64.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
                    FineSlider(value, scale, ed::beginDrag, { v -> ed.dragTo { it.copy(tone = set(it.tone, Math.round(v * 2) / 2.0)) } }, ed::endDrag,
                        Modifier.weight(1f).padding(horizontal = 4.dp), color = MaterialTheme.colorScheme.tertiary, centre = 0.0, fine = false)
                    ValueChip("", fmtGain(value), fmtNum(value, 1), { v -> ed.edit { it.copy(tone = set(it.tone, v.coerceIn(-Tone.RANGE, Tone.RANGE))) } }, "dB", Modifier.width(76.dp))
                }
            }
        }
    }
}

/** Profile quick switcher (feature 9): profiles grouped by IEM. */
@Composable
fun QuickSwitcher(open: Boolean, current: Profile, onDismiss: () -> Unit, onOpenLibrary: () -> Unit) {
    val app = BpeqApp.instance
    val profiles by app.library.profiles.collectAsState()
    DropdownMenu(open, onDismiss) {
        val groups = profiles.groupBy { it.iem.ifBlank { "No IEM" } }.toSortedMap(compareBy<String> { if (it == current.iem.ifBlank { "No IEM" }) 0 else 1 }.thenBy { it })
        groups.forEach { (iem, list) ->
            Text(iem, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp))
            list.forEach { p ->
                DropdownMenuItem({ Text((if (p.favorite) "★ " else "") + p.name) }, {
                    app.library.select(p.id); app.editor.select(null); onDismiss()
                }, enabled = p.id != current.id)
            }
        }
        HorizontalDivider()
        DropdownMenuItem({ Text("Open library") }, { onDismiss(); onOpenLibrary() })
    }
}

fun compareOverlay(compareId: String?, currentId: String): List<Overlay> {
    if (compareId == null || compareId == currentId) return emptyList()
    val p = BpeqApp.instance.library.get(compareId) ?: return emptyList()
    return listOf(Overlay(Response.ideal(ToneBlend.apply(p.working).bands, FreqGrid.DISPLAY), COMPARE_COLOR, width = 2f))
}

@Composable
fun CompareChip(compareId: String?) {
    val app = BpeqApp.instance
    val p = compareId?.let { app.library.get(it) } ?: return
    Row(Modifier.padding(horizontal = 8.dp)) {
        InputChip(selected = true, onClick = { app.editor.compare(null) },
            label = { Text("vs ${p.name}  ✕") },
            leadingIcon = { Surface(color = COMPARE_COLOR, shape = CircleShape, modifier = Modifier.size(8.dp)) {} },
            modifier = Modifier.height(30.dp))
    }
}

/** A/B toggle (feature 11), only when the hardware test proved RAM-only writes and this profile is on the dongle. */
@Composable
fun AbButton(profile: Profile, onDevice: Boolean) {
    val app = BpeqApp.instance
    val verdict by app.devices.abVerdict.collectAsState()
    val flat by Applier.abFlat.collectAsState()
    val scope = rememberCoroutineScope()
    if (verdict != DeviceManager.AbVerdict.RAM_ONLY || !(onDevice || flat)) return
    OutlinedButton(onClick = { scope.launch { Applier.toggleAb(profile.saved) } }, modifier = Modifier.padding(end = 8.dp)) {
        Text(if (flat) "B: EQ off" else "A/B")
    }
    Spacer(Modifier.width(0.dp))
}
