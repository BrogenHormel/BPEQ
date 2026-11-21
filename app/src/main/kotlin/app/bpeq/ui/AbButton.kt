package app.bpeq.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedButton
import androidx.compose.ui.text.style.TextAlign
import kotlinx.coroutines.launch
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.bpeq.data.Applier

/**
 * Compare on the send bar: EQ On (the profile on the dongle) vs EQ Off (every band flat, preamp set so it sounds as
 * loud as the EQ: Planner.loudnessMatchedFlatPreamp). Every switch is written to the Protocol Max's permanent memory (firmware 1.4), so the first use
 * explains that, and past the daily allowance each further block of switches needs a confirmation.
 */
@Composable
fun EqOnOffToggle(profileId: String, onPreampDb: Int, offPreampDb: Int, trimDb: Int, enabled: Boolean) {
    val flatFor by Applier.abFlatFor.collectAsState()
    val stats by Applier.abStats.collectAsState()
    val busy by Applier.abBusy.collectAsState()
    val off = flatFor == profileId
    var ask by remember { mutableStateOf<String?>(null) }
    val switch = { Applier.toggleAbInBackground(profileId) }
    val request = { wantOff: Boolean ->
        if (wantOff != off && !busy) when {
            stats.total == 0 -> ask = FIRST
            Applier.abNeedsConfirm() -> ask = "You've switched ${stats.today} times today. Each switch is written to the " +
                "dongle's permanent memory, which only takes a limited number of writes. Allow 20 more today?"
            else -> switch()
        }
    }
    Column(Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, top = 8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Compare", style = MaterialTheme.typography.labelLarge, modifier = Modifier.weight(1f))
            SingleChoiceSegmentedButtonRow {
                listOf(false, true).forEachIndexed { i, wantOff ->
                    SegmentedButton(selected = off == wantOff, onClick = { request(wantOff) }, enabled = enabled && !busy,
                        shape = SegmentedButtonDefaults.itemShape(i, 2), label = { Text(if (wantOff) "EQ Off" else "EQ On") })
                }
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 2.dp)) {
            Text("EQ Off level", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
            val adjust = { d: Int -> Applier.scope.launch { Applier.adjustOffLevel(profileId, d)?.let { app.bpeq.BpeqApp.instance.messages.show(it) } }; Unit }
            OutlinedButton({ adjust(-1) }, enabled = enabled && !busy && trimDb > -6, modifier = Modifier.size(40.dp),
                contentPadding = PaddingValues(0.dp)) { Text("−") }
            Text("$offPreampDb dB" + if (trimDb != 0) " (${if (trimDb > 0) "+" else ""}$trimDb)" else "",
                style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center, modifier = Modifier.width(96.dp))
            OutlinedButton({ adjust(+1) }, enabled = enabled && !busy && trimDb < 6, modifier = Modifier.size(40.dp),
                contentPadding = PaddingValues(0.dp)) { Text("+") }
        }
        Text(
            when {
                busy -> "Switching…"
                off -> "EQ Off: all bands flat, preamp $offPreampDb dB (EQ On uses $onPreampDb dB). If one still sounds louder, " +
                    "adjust the EQ Off level until they match."
                else -> "EQ Off flattens every band and uses the EQ Off level as its preamp: loudness-matched to the EQ by " +
                    "default (EQ On uses $onPreampDb dB). Fine-tune it by ear with − / +."
            },
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 4.dp),
        )
    }

    ask?.let { text ->
        AlertDialog(
            onDismissRequest = { ask = null },
            title = { Text("Compare EQ On / Off") },
            text = { Text(text, style = MaterialTheme.typography.bodyMedium) },
            confirmButton = { Button({ if (text != FIRST) Applier.abConfirmMore(); ask = null; switch() }) { Text("Switch") } },
            dismissButton = { TextButton({ ask = null }) { Text("Cancel") } },
        )
    }
}

private const val FIRST = "EQ Off sets every band flat and adjusts the preamp so it sounds as loud as the EQ " +
    "(measured the way loudness meters do), so you hear only what the EQ does to the tone. The preamp moves in whole dB, " +
    "so a small difference can remain; the EQ Off level − / + lets you match it by ear. EQ On puts the profile back. Each switch takes about a second.\n\n" +
    "The Protocol Max stores every change in its permanent memory, which only takes a limited number of writes. " +
    "BPEQ writes only the bands that change, skips the extra save step, and counts every switch (Device tab). " +
    "After 20 switches in a day it asks before going on.\n\n" +
    "If you unplug while on EQ Off, the dongle stays off: BPEQ offers to put the profile back next time it's connected."
