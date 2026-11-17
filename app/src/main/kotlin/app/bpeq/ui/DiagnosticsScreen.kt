package app.bpeq.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import app.bpeq.BpeqApp
import app.bpeq.core.device.MemoryLog
import app.bpeq.device.AppLog
import kotlinx.coroutines.launch
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun DiagnosticsScreen(modifier: Modifier = Modifier) {
    val app = BpeqApp.instance
    val entries by app.log.entries.collectAsState()
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val listState = rememberLazyListState()
    val busy = app.devices.status.collectAsState().value is app.bpeq.device.DeviceStatus.Busy

    LaunchedEffect(entries.size) { if (entries.isNotEmpty()) listState.scrollToItem(entries.lastIndex) }

    Column(modifier.fillMaxSize().padding(horizontal = 16.dp)) {
        Text("Diagnostics", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.padding(top = 16.dp))
        Text(
            "USB events, every HID report (>> sent, << received) and errors. Copy or share this log to report a problem.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 4.dp, bottom = 12.dp),
        )
        Button(
            onClick = { scope.launch { app.devices.runSelfTest() } },
            enabled = !busy, modifier = Modifier.fillMaxWidth(),
        ) { Text(if (busy) "Working…" else "Run device self-test (read-only)") }
        Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilledTonalButton(onClick = { copyLog(ctx, app.log) }, modifier = Modifier.weight(1f)) { Text("Copy") }
            FilledTonalButton(onClick = { shareLog(ctx, app.log) }, modifier = Modifier.weight(1f)) { Text("Share") }
            OutlinedButton(onClick = { app.log.clear() }, modifier = Modifier.weight(1f)) { Text("Clear") }
        }
        val fmt = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)
        LazyColumn(
            state = listState,
            modifier = Modifier.weight(1f).fillMaxWidth()
                .background(MaterialTheme.colorScheme.surfaceContainerLowest, RoundedCornerShape(12.dp))
                .padding(8.dp),
        ) {
            itemsIndexed(entries) { _, e ->
                Row {
                    Text(fmt.format(Date(e.timeMs)), fontFamily = FontFamily.Monospace, fontSize = 10.sp,
                        color = MaterialTheme.colorScheme.outline)
                    Spacer(Modifier.width(6.dp))
                    Text("${AppLog.tag(e.level)} ${e.text}", fontFamily = FontFamily.Monospace, fontSize = 11.sp,
                        color = levelColor(e.level))
                }
            }
        }
        Spacer(Modifier.padding(bottom = 8.dp))
    }
}

@Composable
private fun levelColor(l: MemoryLog.Level): Color = when (l) {
    MemoryLog.Level.INFO -> MaterialTheme.colorScheme.onSurface
    MemoryLog.Level.WARN -> Palette.warn
    MemoryLog.Level.ERROR -> Palette.bad
    MemoryLog.Level.TX -> Palette.accent
    MemoryLog.Level.RX -> Palette.good
}

private fun copyLog(ctx: Context, log: AppLog) {
    val cm = ctx.getSystemService(ClipboardManager::class.java)
    // The clipboard has a ~1 MB binder limit; keep the newest 400 kB.
    cm.setPrimaryClip(ClipData.newPlainText("BPEQ diagnostics", log.export(maxChars = 400_000)))
    Toast.makeText(ctx, "Log copied", Toast.LENGTH_SHORT).show()
}

private fun shareLog(ctx: Context, log: AppLog) {
    runCatching {
        val dir = File(ctx.cacheDir, "shared").apply { mkdirs() }
        val f = File(dir, "bpeq-diagnostics.txt")
        f.writeText(log.export())
        val uri = FileProvider.getUriForFile(ctx, ctx.packageName + ".files", f)
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, "BPEQ diagnostics")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        ctx.startActivity(Intent.createChooser(send, "Share log"))
    }.onFailure {
        log.error("share failed", it)
        Toast.makeText(ctx, "Share failed: ${it.message}", Toast.LENGTH_LONG).show()
    }
}
