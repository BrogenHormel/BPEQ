package app.bpeq.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.bpeq.BpeqApp
import app.bpeq.core.device.DeviceLog
import app.bpeq.core.device.MaxSession
import app.bpeq.core.device.TestPlans
import app.bpeq.core.device.WritePlan
import app.bpeq.device.DeviceStatus
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.util.Date

private data class PendingWrite(val label: String, val plan: WritePlan, val slot: Int)

/** Milestone-3 hardware write test: read, send a known test EQ, restore, reset. */
@Composable
fun WriteTestCard(status: DeviceStatus) {
    val dm = BpeqApp.instance.devices
    val state by dm.lastState.collectAsState()
    val original by dm.originalBackup.collectAsState()
    val firstDone by dm.firstWriteDone.collectAsState()
    val last by dm.lastWrite.collectAsState()
    val scope = rememberCoroutineScope()
    var alwaysPreview by rememberSaveable { mutableStateOf(false) }
    var pending by remember { mutableStateOf<PendingWrite?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    val enabled = status.canTalk

    fun request(label: String, plan: WritePlan) {
        val slot = state?.slot ?: return
        val p = PendingWrite(label, plan, slot)
        if (!firstDone || alwaysPreview) pending = p
        else scope.launch { dm.write(p.plan, p.slot, p.label) }
    }

    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Write test", style = MaterialTheme.typography.titleMedium)
            Text(
                "Every write is read back and compared before it is saved to the dongle. " +
                    if (firstDone) "" else "The first write shows you every byte first.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            FilledTonalButton(onClick = {
                scope.launch { dm.read().onFailure { error = it.message } }
            }, enabled = enabled, modifier = Modifier.fillMaxWidth()) { Text(if (state == null) "1. Read the dongle" else "Read again") }

            state?.let { s ->
                Text("Read: firmware ${s.version}, slot 0x%02x, preamp ${s.preampDb} dB".format(s.slot), style = MaterialTheme.typography.bodySmall)
            }
            Button(onClick = { request("Test EQ (cuts at 100 Hz, 1 kHz, 8 kHz; preamp -1 dB)", TestPlans.audibleCut()) },
                enabled = enabled && state != null, modifier = Modifier.fillMaxWidth()) { Text("2. Send test EQ") }
            OutlinedButton(onClick = {
                val b = original ?: return@OutlinedButton
                b.toPlan().onSuccess { request("Restore original (read ${DateFormat.getDateTimeInstance().format(Date(b.timeMs))})", it) }
                    .onFailure { error = "Original can't be restored within limits: ${it.message}" }
            }, enabled = enabled && state != null && original != null, modifier = Modifier.fillMaxWidth()) { Text("3. Restore original") }
            OutlinedButton(onClick = { request("Reset to flat (factory flat, preamp 0 dB)", TestPlans.flat()) },
                enabled = enabled && state != null, modifier = Modifier.fillMaxWidth()) { Text("Reset to flat") }
            Row(
                Modifier.fillMaxWidth().toggleable(alwaysPreview, role = Role.Switch) { alwaysPreview = it },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("Always show bytes before sending", Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                Switch(checked = alwaysPreview || !firstDone, onCheckedChange = null, enabled = firstDone)
            }
            original?.let { Text("Restore point saved (original state, preamp ${it.preampDb} dB).",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            last?.let { o ->
                Spacer(Modifier.size(4.dp))
                Text((if (o.ok) "OK: " else "FAILED: ") + o.label, color = if (o.ok) Palette.good else Palette.bad,
                    style = MaterialTheme.typography.labelLarge)
                o.lines.forEach { Text(it, style = MaterialTheme.typography.bodySmall) }
            }
        }
    }

    pending?.let { p ->
        WritePreviewDialog(p, onDismiss = { pending = null }, onConfirm = {
            pending = null
            scope.launch { dm.write(p.plan, p.slot, p.label) }
        })
    }
    error?.let { e ->
        AlertDialog(onDismissRequest = { error = null }, confirmButton = { TextButton({ error = null }) { Text("OK") } },
            title = { Text("Problem") }, text = { Text(e) })
    }
}

@Composable
private fun WritePreviewDialog(p: PendingWrite, onDismiss: () -> Unit, onConfirm: () -> Unit) {
    val phase1 = MaxSession.registerSteps(p.plan, p.slot)
    val phase2 = MaxSession.commitSteps()
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Check before sending") },
        text = {
            Column(Modifier.heightIn(max = 520.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(p.label, style = MaterialTheme.typography.titleSmall)
                Text("Every value is inside the Protocol Max limits. Step 1 sends these ${phase1.size} reports " +
                    "(they change the dongle's registers but are not saved):", style = MaterialTheme.typography.bodySmall)
                phase1.forEach { StepText(it) }
                Text("Then BPEQ reads all 10 bands and the preamp back. Only if every value matches does step 2 " +
                    "send these ${phase2.size} reports to apply and save to the dongle's memory, then read back again:",
                    style = MaterialTheme.typography.bodySmall)
                phase2.forEach { StepText(it) }
            }
        },
        confirmButton = { Button(onClick = onConfirm) { Text("Send") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun StepText(s: MaxSession.Step) {
    Column {
        Text(s.what, style = MaterialTheme.typography.labelMedium)
        Text(DeviceLog.hex(s.report), fontFamily = FontFamily.Monospace, fontSize = 10.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
