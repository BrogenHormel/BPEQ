package app.bpeq.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import app.bpeq.core.io.LinkImport
import app.bpeq.data.Actions

/** "Import from link": AutoEQ (GitHub), squig.link share/EQ links, or a link to a filter or measurement file. */
@Composable
fun LinkDialog(onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    var link by remember {
        val c = ctx.getSystemService(android.content.ClipboardManager::class.java).primaryClip
        val text = if (c != null && c.itemCount > 0) c.getItemAt(0).coerceToText(ctx)?.toString() else null
        mutableStateOf(text?.let(LinkImport::findLink) ?: "")
    }
    val go = { Actions.importLink(link.trim()); onDismiss() }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Import from link") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("• AutoEQ result on GitHub → a new profile\n• squig.link EQ link → a new profile with graph overlays\n" +
                    "• squig.link graph link → the IEM (and target) in Tune\n• a link to a filter or measurement file",
                    style = MaterialTheme.typography.bodySmall)
                OutlinedTextField(link, { link = it }, Modifier.fillMaxWidth(), label = { Text("https://…") }, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Go),
                    keyboardActions = androidx.compose.foundation.text.KeyboardActions(onGo = { if (link.isNotBlank()) go() }))
                Text("BPEQ only goes online when you import a link.", style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        },
        confirmButton = { Button(onClick = go, enabled = link.trim().startsWith("https://")) { Text("Import") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
