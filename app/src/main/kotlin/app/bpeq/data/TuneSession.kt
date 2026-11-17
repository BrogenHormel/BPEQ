package app.bpeq.data

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import app.bpeq.core.io.EqImport
import app.bpeq.core.model.Tuning
import app.bpeq.core.tune.AutoTune
import app.bpeq.core.tune.Curve
import app.bpeq.core.tune.Targets
import app.bpeq.core.tune.TuneResult
import app.bpeq.core.tune.TuneSettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream

/** Auto-tune inputs and result; lives as long as the app so switching tabs keeps the work. */
class TuneSession(private val library: Library, private val messages: Messages) {
    data class State(
        val measurement: Curve? = null,
        val targetId: String? = Targets.ALL.first().id,
        val customTarget: Curve? = null,
        val settings: TuneSettings = TuneSettings(),
        val running: Boolean = false,
        val result: TuneResult? = null,
    ) {
        val target: Curve? get() = customTarget ?: targetId?.let { runCatching { Targets.load(it) }.getOrNull() }
        val targetName: String get() = customTarget?.name ?: Targets.ALL.firstOrNull { it.id == targetId }?.name ?: "Target"
    }

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    fun update(f: (State) -> State) { _state.value = f(_state.value) }

    fun loadCurve(ctx: Context, uri: Uri): Curve? = runCatching {
        val name = ctx.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        }
        val text = ctx.contentResolver.openInputStream(uri)!!.use { input ->
            val out = ByteArrayOutputStream(); val buf = ByteArray(8192)
            while (out.size() < 4_000_000) { val n = input.read(buf); if (n < 0) break; out.write(buf, 0, n) }
            out.toString(Charsets.UTF_8.name())
        }
        Curve.parse(text, EqImport.nameFromFile(name)?.removeSuffix(" L")?.removeSuffix(" R") ?: "Measurement")
    }.onFailure { messages.show(it.message ?: "Couldn't read the curve") }.getOrNull()

    fun setMeasurement(c: Curve) = update { it.copy(measurement = c, result = null) }
    fun setCustomTarget(c: Curve) = update { it.copy(customTarget = c, targetId = null, result = null) }
    fun setTarget(id: String) = update { it.copy(targetId = id, customTarget = null, result = null) }

    suspend fun run() {
        val s = _state.value
        val m = s.measurement ?: return
        val t = s.target ?: return
        update { it.copy(running = true) }
        val r = runCatching { withContext(Dispatchers.Default) { AutoTune.run(m, t, s.settings) } }
        update { it.copy(running = false, result = r.getOrNull()) }
        r.onFailure { messages.show("Auto-tune failed: ${it.message}") }
    }

    /** Saves the result as a new profile tagged with the IEM, with measurement/target kept for overlays. */
    fun createProfile(): String? {
        val s = _state.value
        val r = s.result ?: return null
        val m = s.measurement ?: return null
        val t = s.target ?: return null
        val grid = AutoTune.GRID.freqs
        val tuning = Tuning(m.normalized().resampled(grid), t.normalized().resampled(grid))
        val p = library.create("${m.name} → ${s.targetName}", r.eq, iem = m.name)
        library.update(p.id, 0) { it.copy(tuning = tuning) }
        library.select(p.id)
        messages.show("Created “${p.name}” with ${r.eq.bands.size} filter(s)")
        return p.id
    }
}
