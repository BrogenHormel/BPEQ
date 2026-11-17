package app.bpeq.ui

import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import app.bpeq.BpeqApp
import app.bpeq.core.device.DeviceBackup
import app.bpeq.core.device.MaxLimits
import app.bpeq.core.device.WritePlan
import app.bpeq.device.DeviceManager.AbVerdict
import app.bpeq.device.DeviceStatus
import kotlinx.coroutines.launch

/**
 * Feature 11 investigation: does a write applied with TEMP_WRITE (no flash commit) vanish on replug?
 * Step 1 plays flat (preamp kept) from RAM; step 2, after a replug, reads back: the old EQ back = RAM-only = A/B on.
 */
@Composable
fun AbTestCard(status: DeviceStatus) {
    val dm = BpeqApp.instance.devices
    val verdict by dm.abVerdict.collectAsState()
    val scope = rememberCoroutineScope()
    var before by remember { mutableStateOf(dm.abBefore) }
    var note by remember { mutableStateOf<String?>(null) }
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("A/B test (hardware check)", style = MaterialTheme.typography.titleMedium)
            Text(when (verdict) {
                AbVerdict.UNKNOWN -> "A/B needs proof that the dongle can switch EQ without saving it to its memory. This test checks that, without wearing out the flash."
                AbVerdict.RAM_ONLY -> "Proven: temporary writes are lost on replug. A/B is enabled on the EQ tab when the profile is on the dongle."
                AbVerdict.PERSISTS -> "The temporary write survived a replug, so A/B stays off (it would wear the flash)."
            }, style = MaterialTheme.typography.bodySmall, color = when (verdict) {
                AbVerdict.RAM_ONLY -> Palette.good; AbVerdict.PERSISTS -> Palette.warn; else -> MaterialTheme.colorScheme.onSurfaceVariant })
            FilledTonalButton(onClick = {
                scope.launch {
                    val st = dm.read().getOrElse { note = "Read failed: ${it.message}"; return@launch }
                    val b = DeviceBackup.of(st)
                    dm.abBefore = b; before = b
                    val ok = dm.writeTemporary(WritePlan(List(MaxLimits.BANDS) { MaxLimits.flatRegisters(it) }, st.preampDb.coerceIn(MaxLimits.PREAMP_MIN, MaxLimits.PREAMP_MAX)))
                    note = if (ok) "EQ switched off temporarily. Listen: does it sound flat now? Then unplug the dongle, plug it back in, and tap step 2."
                        else "Temporary write failed; see the Log."
                }
            }, enabled = status.canTalk, modifier = Modifier.fillMaxWidth()) { Text("1. Switch EQ off temporarily (not saved)") }
            Button(onClick = {
                scope.launch {
                    val b = before ?: return@launch
                    val st = dm.read().getOrElse { note = "Read failed: ${it.message}"; return@launch }
                    if (st.registers == b.registers && st.preampDb == b.preampDb) {
                        dm.setAbVerdict(AbVerdict.RAM_ONLY)
                        note = "Your EQ came back after the replug: temporary writes are RAM-only. A/B is now enabled."
                    } else {
                        dm.setAbVerdict(AbVerdict.PERSISTS)
                        val r = b.toPlan().getOrNull()?.let { dm.write(it, b.slot, "Restore after A/B test") }
                        note = "The flat EQ was still there after the replug, so A/B stays off. " +
                            if (r?.ok == true) "Your EQ has been restored." else "Restore it from the Library."
                    }
                    dm.abBefore = null; before = null
                }
            }, enabled = status.canTalk && before != null, modifier = Modifier.fillMaxWidth()) { Text("2. After replugging: check") }
            if (status == DeviceStatus.Simulated) OutlinedButton({ dm.simulator.replug(); note = "Simulator replugged (RAM reloaded from flash)." },
                Modifier.fillMaxWidth()) { Text("Simulator: unplug and replug") }
            note?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
            if (verdict != AbVerdict.UNKNOWN) TextButton({ dm.setAbVerdict(AbVerdict.UNKNOWN) }) { Text("Forget the result") }
        }
    }
}

@Composable
fun SettingsCard() {
    val ctx = LocalContext.current
    val prefs = remember { ctx.getSharedPreferences("settings", Context.MODE_PRIVATE) }
    var prompt by remember { mutableStateOf(prefs.getBoolean("plugin_prompt", true)) }
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)) {
        Column(Modifier.fillMaxWidth().padding(16.dp)) {
            Text("Settings", style = MaterialTheme.typography.titleMedium)
            Row(Modifier.fillMaxWidth().toggleable(prompt, role = Role.Switch) { prompt = it; prefs.edit().putBoolean("plugin_prompt", it).apply() }.padding(vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically) {
                Text("On plug-in, offer to apply the last profile", Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                Switch(prompt, null)
            }
            Text("Quick Settings tile: edit your quick settings and add “BPEQ profile”. Each tap applies the next ★ favorite.\n" +
                "Widget: long-press the home screen → Widgets → BPEQ.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
