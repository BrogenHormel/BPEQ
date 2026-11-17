package app.bpeq.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.selection.toggleable
import androidx.compose.ui.semantics.Role
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.bpeq.BpeqApp
import app.bpeq.core.device.SelfTest
import app.bpeq.device.DeviceStatus
import kotlinx.coroutines.launch

@Composable
fun DeviceScreen(modifier: Modifier = Modifier, onOpenDiagnostics: () -> Unit) {
    val dm = BpeqApp.instance.devices
    val status by dm.status.collectAsState()
    val simulate by dm.simulate.collectAsState()
    val report by dm.lastSelfTest.collectAsState()
    val scope = rememberCoroutineScope()

    Column(modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Device", style = MaterialTheme.typography.headlineSmall)
        StatusCard(status)

        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)) {
            Row(
                Modifier.fillMaxWidth().toggleable(simulate, role = Role.Switch) { dm.setSimulate(it) }.padding(16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text("Simulated dongle", style = MaterialTheme.typography.titleMedium)
                    Text("A software Protocol Max for trying the app without hardware.",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Switch(checked = simulate, onCheckedChange = null)
            }
        }

        Button(
            onClick = { scope.launch { dm.runSelfTest() } },
            enabled = status !is DeviceStatus.Busy && status !is DeviceStatus.NoDevice,
            modifier = Modifier.fillMaxWidth(),
        ) { Text("Run device self-test (read-only)") }

        report?.let { SelfTestCard(it) }

        Button(onClick = onOpenDiagnostics, modifier = Modifier.fillMaxWidth()) { Text("Open diagnostics log") }
    }
}

@Composable
fun StatusCard(status: DeviceStatus) {
    val (label, color, detail) = when (status) {
        DeviceStatus.NoDevice -> Triple("No device", MaterialTheme.colorScheme.outline, "Plug in the Protocol Max.")
        DeviceStatus.Simulated -> Triple("Simulated", Palette.accent, "Talking to the built-in simulator.")
        is DeviceStatus.Busy -> Triple("Working", Palette.warn, status.what + "…")
        is DeviceStatus.Error -> Triple("Error", Palette.bad, status.message)
    }
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)) {
        Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Surface(shape = CircleShape, color = color, modifier = Modifier.size(12.dp)) {}
            Spacer(Modifier.width(12.dp))
            Column {
                Text(label, style = MaterialTheme.typography.titleMedium)
                Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
fun SelfTestCard(report: SelfTest.Report) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("Self-test: ${report.status}", style = MaterialTheme.typography.titleMedium, color = statusColor(report.status))
            report.checks.forEach { c ->
                Row {
                    Text(c.status.name.padEnd(4), color = statusColor(c.status), fontFamily = FontFamily.Monospace, fontSize = 12.sp)
                    Spacer(Modifier.width(8.dp))
                    Text(c.text, style = MaterialTheme.typography.bodySmall)
                }
            }
            report.state?.let { s ->
                Spacer(Modifier.size(8.dp))
                Text("On the device (firmware ${s.version}, preamp ${s.preampDb} dB):", style = MaterialTheme.typography.labelLarge)
                s.bands.forEach { b ->
                    Text("${b.index + 1}. ${b.registers}", fontFamily = FontFamily.Monospace, fontSize = 12.sp)
                }
            }
        }
    }
}

fun statusColor(s: SelfTest.Status): Color = when (s) {
    SelfTest.Status.PASS -> Palette.good
    SelfTest.Status.WARN -> Palette.warn
    SelfTest.Status.FAIL -> Palette.bad
}
