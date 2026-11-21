package app.bpeq.data

import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import androidx.core.content.FileProvider
import app.bpeq.BpeqApp
import app.bpeq.Tab
import app.bpeq.core.device.Planner
import app.bpeq.core.io.ApoText
import app.bpeq.core.io.EqImport
import app.bpeq.core.io.HttpFetch
import app.bpeq.core.io.ImportException
import app.bpeq.core.io.LinkImport
import app.bpeq.core.model.Tuning
import app.bpeq.core.tune.AutoTune
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import app.bpeq.core.io.LibraryBackup
import app.bpeq.core.tune.ToneBlend
import app.bpeq.core.model.Eq
import app.bpeq.core.model.Profile
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Import/export actions shared by the library, the editor and incoming intents (Open with / Share). */
object Actions {
    private val app get() = BpeqApp.instance
    private const val MAX_IMPORT_BYTES = 512 * 1024

    /**
     * Imports EQ text as a new profile and opens it. Returns true on success. Text that is just a link (pasted or
     * shared from a browser) is imported from the web instead, in the background; that returns false and switches
     * tab itself when done.
     */
    fun importText(text: String, nameHint: String?): Boolean {
        LinkImport.findLink(text)?.let { importLink(it); return false }
        app.log.info("import: ${text.length} chars, name hint \"$nameHint\"")
        val r = runCatching { EqImport.parse(text) }.getOrElse { app.log.warn("import failed: ${it.message}"); app.messages.show(it.message ?: "Import failed"); return false }
        val p = app.library.create(nameHint ?: "Imported ${stamp()}", r.eq)
        app.library.select(p.id)
        app.editor.select(null)
        val extra = if (r.notes.isEmpty()) "" else " (" + r.notes.joinToString("; ") + ")"
        app.messages.show("Imported “${p.name}”: ${r.eq.bands.size} band(s)$extra")
        return true
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val _linkBusy = MutableStateFlow(false)
    /** A link import is downloading. */
    val linkBusy: StateFlow<Boolean> = _linkBusy

    /**
     * Imports from a web link (AutoEQ on GitHub, squig.link, a file URL). Filters become a new profile (with the
     * IEM/target as overlays when the link has them) and open in the editor; a measurement goes to Tune.
     */
    fun importLink(link: String) {
        if (_linkBusy.value) return
        _linkBusy.value = true
        app.log.info("link import: $link")
        app.messages.show("Downloading…")
        scope.launch {
            val r = runCatching { withContext(Dispatchers.IO) { LinkImport.import(link, HttpFetch { app.log.info("GET $it") }) } }
            _linkBusy.value = false
            r.onFailure { e ->
                app.log.warn("link import failed: ${e.message}")
                app.messages.show(if (e is ImportException) e.message ?: "Import failed" else "Import failed: ${e.message}")
            }
            when (val res = r.getOrNull()) {
                null -> {}
                is LinkImport.Result.Filters -> {
                    val eq = res.import.eq
                    val p = app.library.create(res.name ?: "Imported ${stamp()}", eq, iem = res.measurement?.name ?: "")
                    val m = res.measurement; val t = res.target
                    if (m != null && t != null) {
                        val grid = AutoTune.GRID.freqs
                        app.library.update(p.id, 0) { it.copy(tuning = Tuning(m.normalized().resampled(grid), t.normalized().resampled(grid))) }
                    }
                    app.library.select(p.id)
                    app.editor.select(null)
                    app.tabRequest.value = Tab.EDITOR
                    val notes = res.import.notes
                    app.messages.show("Imported “${p.name}”: ${eq.bands.size} band(s)" + if (notes.isEmpty()) "" else " (" + notes.joinToString("; ") + ")")
                }
                is LinkImport.Result.Measurement -> {
                    app.tune.setMeasurement(res.measurement)
                    res.target?.let(app.tune::setCustomTarget)
                    app.tabRequest.value = Tab.TUNE
                    val t = res.target?.let { " and target “${it.name}”" } ?: ""
                    app.messages.show("Loaded “${res.measurement.name}”$t. Tap Auto-tune." + if (res.notes.isEmpty()) "" else " (" + res.notes.joinToString("; ") + ")")
                }
            }
        }
    }

    fun importClipboard(ctx: Context) {
        val clip = ctx.getSystemService(ClipboardManager::class.java).primaryClip
        val text = clip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(ctx)?.toString()
        if (text.isNullOrBlank()) { app.messages.show("The clipboard is empty"); return }
        importText(text, null)
    }

    fun importUri(ctx: Context, uri: Uri) {
        runCatching {
            val name = ctx.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                if (c.moveToFirst()) c.getString(0) else null
            }
            val bytes = ctx.contentResolver.openInputStream(uri)!!.use { input ->
                val out = java.io.ByteArrayOutputStream()
                val buf = ByteArray(8192)
                while (out.size() <= MAX_IMPORT_BYTES) { val n = input.read(buf); if (n < 0) break; out.write(buf, 0, n) }
                out.toByteArray()
            }
            require(bytes.size <= MAX_IMPORT_BYTES) { "File too large for an EQ file" }
            importText(String(bytes, Charsets.UTF_8), EqImport.nameFromFile(name))
        }.onFailure { app.messages.show("Couldn't read the file: ${it.message}") }
    }

    /** Handles VIEW (Open with) and SEND (Share) intents carrying EQ text. Returns true if it was one. */
    fun handleIntent(ctx: Context, intent: Intent?): Boolean {
        intent ?: return false
        if (intent.action == Intent.ACTION_VIEW || intent.action == Intent.ACTION_SEND) app.log.info("incoming ${intent.action} type=${intent.type} data=${intent.data}")
        when (intent.action) {
            Intent.ACTION_VIEW -> intent.data?.let { importUri(ctx, it); return true }
            Intent.ACTION_SEND -> {
                @Suppress("DEPRECATION")
                val stream = intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)
                if (stream != null) { importUri(ctx, stream); return true }
                intent.getStringExtra(Intent.EXTRA_TEXT)?.let { importText(it, intent.getStringExtra(Intent.EXTRA_SUBJECT)); return true }
            }
        }
        return false
    }

    suspend fun readFromDevice(): Boolean {
        val state = app.devices.read().getOrElse { app.messages.show("Read failed: ${it.message}"); return false }
        val eq = Planner.eqFromDevice(state)
        val p = app.library.create("From device ${stamp()}", eq)
        app.library.select(p.id)
        app.library.markOnDevice(p.id, eq)
        app.messages.show("Read ${eq.bands.size} band(s) and preamp ${state.preampDb} dB from the dongle")
        return true
    }

    fun newFlat() {
        val p = app.library.create("New profile", Eq.FLAT)
        app.library.select(p.id)
    }

    /** The Equalizer APO / Peace text of [p] (working copy, with the preamp the device would use). */
    fun apoText(p: Profile): String =
        ApoText.format(ToneBlend.apply(p.working).bands, Planner.plan(p.working).preampDb.toDouble())

    fun backupJson(): String = LibraryBackup(profiles = BpeqApp.instance.library.profiles.value.map { it.copy(undo = emptyList(), redo = emptyList()) }).toJson()

    fun restoreBackup(ctx: Context, uri: Uri) {
        runCatching {
            val text = ctx.contentResolver.openInputStream(uri)!!.use { it.bufferedReader().readText() }
            val b = LibraryBackup.fromJson(text)
            val n = BpeqApp.instance.library.importAll(b.profiles)
            BpeqApp.instance.messages.show("Restored $n profile(s) from the backup (${b.profiles.size - n} already in the library)")
        }.onFailure { BpeqApp.instance.messages.show("Restore failed: ${it.message}") }
    }

    fun fileName(p: Profile): String =
        p.name.replace(Regex("""[\\/:*?"<>|\u0000-\u001f]"""), " ").trim().ifEmpty { "Profile" } + " ParametricEQ.txt"

    fun share(ctx: Context, p: Profile) {
        runCatching {
            val dir = File(ctx.cacheDir, "shared").apply { mkdirs() }
            val f = File(dir, fileName(p)).apply { writeText(apoText(p)) }
            val uri = FileProvider.getUriForFile(ctx, ctx.packageName + ".files", f)
            val send = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_STREAM, uri)
                putExtra(Intent.EXTRA_TEXT, apoText(p))
                putExtra(Intent.EXTRA_SUBJECT, p.name)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            ctx.startActivity(Intent.createChooser(send, "Share ${p.name}"))
        }.onFailure { app.messages.show("Share failed: ${it.message}") }
    }

    fun writeTo(ctx: Context, uri: Uri, text: String) {
        runCatching { ctx.contentResolver.openOutputStream(uri, "wt")!!.use { it.write(text.toByteArray()) } }
            .onSuccess { app.messages.show("Saved") }
            .onFailure { app.messages.show("Save failed: ${it.message}") }
    }

    private fun stamp() = SimpleDateFormat("MMM d HH:mm", Locale.getDefault()).format(Date())
}
