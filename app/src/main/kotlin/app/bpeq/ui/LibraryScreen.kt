package app.bpeq.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.bpeq.BpeqApp
import app.bpeq.core.device.Planner
import app.bpeq.core.model.Profile
import app.bpeq.data.Actions
import kotlinx.coroutines.launch

@Composable
fun LibraryScreen(modifier: Modifier = Modifier, onOpen: () -> Unit) {
    val app = BpeqApp.instance
    val ctx = LocalContext.current
    val profiles by app.library.profiles.collectAsState()
    val currentId by app.library.currentId.collectAsState()
    val onDevice by app.library.onDevice.collectAsState()
    val deviceState by app.devices.lastState.collectAsState()
    val status by app.devices.status.collectAsState()
    val scope = rememberCoroutineScope()
    var newMenu by remember { mutableStateOf(false) }
    var renaming by remember { mutableStateOf<Profile?>(null) }
    var exporting by remember { mutableStateOf<Profile?>(null) }

    val openFile = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null && Actions.importUri(ctx, uri).let { true }) onOpen()
    }
    val saveFile = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { uri ->
        val p = exporting; exporting = null
        if (uri != null && p != null) Actions.writeTo(ctx, uri, Actions.apoText(p))
    }

    /** Which profile is on the dongle: an exact register match with the last read wins, else the last profile sent. */
    fun isOnDevice(p: Profile): Boolean = deviceState?.let { Planner.matchesDevice(p.working, it) || Planner.matchesDevice(p.saved, it) }
        ?: (onDevice?.first == p.id && onDevice?.second?.sameSettings(p.working) == true)

    Box(modifier.fillMaxSize()) {
        LazyColumn(contentPadding = PaddingValues(start = 12.dp, end = 12.dp, top = 8.dp, bottom = 96.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            item {
                Text("Library", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.padding(start = 4.dp, bottom = 4.dp))
            }
            items(profiles, key = { it.id }) { p ->
                ProfileRow(
                    p, current = p.id == currentId, onDevice = isOnDevice(p),
                    onClick = { app.library.select(p.id); app.editor.select(null); onOpen() },
                    onRename = { renaming = p },
                    onDuplicate = { app.library.duplicate(p.id)?.let { app.messages.show("Duplicated as “${it.name}”") } },
                    onShare = { Actions.share(ctx, p) },
                    onSave = { exporting = p; saveFile.launch(Actions.fileName(p)) },
                    onDelete = {
                        val removed = app.library.delete(p.id)
                        if (removed != null) app.messages.show("Deleted “${removed.name}”", "Undo") { app.library.restore(removed) }
                    },
                )
            }
        }
        Box(Modifier.align(Alignment.BottomEnd).padding(16.dp)) {
            ExtendedFloatingActionButton(onClick = { newMenu = true }, icon = { Icon(Icons.Filled.Add, null) }, text = { Text("New") })
            DropdownMenu(newMenu, { newMenu = false }) {
                DropdownMenuItem({ Text("Flat") }, { newMenu = false; Actions.newFlat(); onOpen() })
                DropdownMenuItem({ Text("Paste from clipboard") }, { newMenu = false; if (Actions.importText(clip(ctx) ?: "", null)) onOpen() })
                DropdownMenuItem({ Text("Import file (.txt / .json)") }, { newMenu = false; openFile.launch(arrayOf("text/*", "application/json", "application/octet-stream")) })
                DropdownMenuItem({ Text("Read from device") }, { newMenu = false; scope.launch { if (Actions.readFromDevice()) onOpen() } }, enabled = status.canTalk)
            }
        }
    }

    renaming?.let { p ->
        var name by remember(p.id) { mutableStateOf(p.name) }
        var iem by remember(p.id) { mutableStateOf(p.iem) }
        AlertDialog(
            onDismissRequest = { renaming = null },
            title = { Text("Rename") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(name, { name = it }, label = { Text("Name") }, singleLine = true)
                    OutlinedTextField(iem, { iem = it }, label = { Text("IEM (optional)") }, singleLine = true)
                }
            },
            confirmButton = { TextButton({ app.library.rename(p.id, name); app.library.update(p.id, 0) { it.copy(iem = iem.trim()) }; renaming = null }) { Text("OK") } },
            dismissButton = { TextButton({ renaming = null }) { Text("Cancel") } },
        )
    }
}

private fun clip(ctx: android.content.Context): String? {
    val c = ctx.getSystemService(android.content.ClipboardManager::class.java).primaryClip ?: return null
    return if (c.itemCount > 0) c.getItemAt(0).coerceToText(ctx)?.toString() else null
}

@Composable
private fun ProfileRow(
    p: Profile, current: Boolean, onDevice: Boolean,
    onClick: () -> Unit, onRename: () -> Unit, onDuplicate: () -> Unit, onShare: () -> Unit, onSave: () -> Unit, onDelete: () -> Unit,
) {
    var menu by remember { mutableStateOf(false) }
    Card(
        colors = CardDefaults.cardColors(containerColor = if (current) MaterialTheme.colorScheme.surfaceContainerHigh else MaterialTheme.colorScheme.surfaceContainer),
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
    ) {
        Row(Modifier.padding(start = 12.dp, top = 8.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            ResponseGraph(p.working, null, Modifier.width(72.dp).height(44.dp), interactive = false)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(p.name, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                val eq = p.working
                Text(buildString {
                    append("${eq.activeBands.size} band${if (eq.activeBands.size == 1) "" else "s"}")
                    if (p.iem.isNotBlank()) append(" · ${p.iem}")
                    if (p.dirty) append(" · unsaved")
                }, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (onDevice) {
                    Spacer(Modifier.height(4.dp))
                    AssistChip(onClick = {}, label = { Text("On device") }, modifier = Modifier.height(26.dp))
                }
            }
            Box {
                IconButton({ menu = true }) { Icon(Icons.Filled.MoreVert, "More") }
                DropdownMenu(menu, { menu = false }) {
                    DropdownMenuItem({ Text("Rename / IEM") }, { menu = false; onRename() })
                    DropdownMenuItem({ Text("Duplicate") }, { menu = false; onDuplicate() })
                    DropdownMenuItem({ Text("Share as APO / Peace text") }, { menu = false; onShare() })
                    DropdownMenuItem({ Text("Save .txt file") }, { menu = false; onSave() })
                    DropdownMenuItem({ Text("Delete", color = Palette.bad) }, { menu = false; onDelete() })
                }
            }
        }
    }
}
