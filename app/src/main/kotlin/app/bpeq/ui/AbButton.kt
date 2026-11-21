package app.bpeq.ui

import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.bpeq.data.Applier

/**
 * A/B on the send bar: the profile on the dongle (A) vs EQ off with the same preamp (B). Every switch is written to
 * the Protocol Max's permanent memory (firmware 1.4), so the first use explains that, and past the daily allowance
 * each further block of switches needs a confirmation.
 */
@Composable
fun AbButton(profileId: String, onDevice: Boolean) {
    val flatFor by Applier.abFlatFor.collectAsState()
    val stats by Applier.abStats.collectAsState()
    val flat = flatFor == profileId
    if (!onDevice && !flat) return
    var ask by remember { mutableStateOf<String?>(null) }
    val busy by Applier.abBusy.collectAsState()
    val switch = { Applier.toggleAbInBackground(profileId) }
    OutlinedButton(
        onClick = {
            when {
                stats.total == 0 -> ask = FIRST
                Applier.abNeedsConfirm() -> ask = "You've switched A/B ${stats.today} times today. Each switch writes the " +
                    "dongle's permanent memory, which only takes a limited number of writes. Allow ${20} more today?"
                else -> switch()
            }
        },
        enabled = !busy,
        modifier = Modifier.padding(end = 8.dp),
    ) { Text(if (flat) "B: EQ off" else "A/B") }

    ask?.let { text ->
        AlertDialog(
            onDismissRequest = { ask = null },
            title = { Text("A/B on the Protocol Max") },
            text = { Text(text, style = MaterialTheme.typography.bodyMedium) },
            confirmButton = {
                Button({ if (text != FIRST) Applier.abConfirmMore(); ask = null; switch() }) { Text("Switch") }
            },
            dismissButton = { TextButton({ ask = null }) { Text("Cancel") } },
        )
    }
}

private const val FIRST = "A/B switches between this profile (A) and the EQ off with the same preamp (B), so you hear " +
    "only the EQ, not a volume change.\n\nThe Protocol Max stores every change in its permanent memory, which only " +
    "takes a limited number of writes. BPEQ writes only the bands that change and skips the extra save step, and " +
    "counts every switch (Device tab). After 20 switches in a day it asks before going on.\n\nIf you unplug while on B, " +
    "the dongle stays on B: BPEQ offers to put the profile back next time it's connected."
