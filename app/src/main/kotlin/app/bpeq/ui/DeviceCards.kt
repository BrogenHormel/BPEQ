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
import app.bpeq.device.DeviceStatus
import kotlinx.coroutines.launch

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

@Composable
fun AboutCard() {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("About BPEQ ${app.bpeq.BuildConfig.VERSION_NAME}", style = MaterialTheme.typography.titleMedium)
            Text(
                "Unofficial parametric EQ for the CrinEar Protocol Max. Not affiliated with CrinEar or WalkPlay.\n\n" +
                    "Credits: WalkPlay protocol and biquad maths adapted from Contour (Apache-2.0, github.com/Iwakura9/contour), " +
                    "which ported them from devicePEQ (0BSD, Jerome O'Flaherty, github.com/jeromeof/devicePEQ). " +
                    "Target curves from AutoEQ (MIT, Jaakko Pasanen). Built with Kotlin and Jetpack Compose (Apache-2.0).",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
