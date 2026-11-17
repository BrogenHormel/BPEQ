package app.bpeq.data

import app.bpeq.core.device.Planner
import app.bpeq.core.model.Band
import app.bpeq.core.model.Eq
import app.bpeq.core.model.FilterType
import app.bpeq.core.model.Profile
import app.bpeq.device.DeviceManager
import app.bpeq.device.WriteOutcome
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.math.ln
import kotlin.math.sqrt

/** A message for the snackbar, optionally with an action (e.g. Undo). */
data class UiMessage(val text: String, val actionLabel: String? = null, val action: (() -> Unit)? = null)

/** Snackbar messages: buffered until the UI collects them (e.g. an import on a cold start), delivered once. */
class Messages {
    private val channel = kotlinx.coroutines.channels.Channel<UiMessage>(16, kotlinx.coroutines.channels.BufferOverflow.DROP_OLDEST)
    val flow: kotlinx.coroutines.flow.Flow<UiMessage> = channel.receiveAsFlow()
    fun show(text: String, actionLabel: String? = null, action: (() -> Unit)? = null) { channel.trySend(UiMessage(text, actionLabel, action)) }
}

/** Editing operations on the current profile. Drags update the working copy live and become one undo step. */
class Editor(val library: Library, val devices: DeviceManager, val messages: Messages) {
    private val _selected = MutableStateFlow<Long?>(null)
    /** Id of the selected band (highlighted on the graph, expanded in the list). */
    val selected: StateFlow<Long?> = _selected.asStateFlow()

    private var dragStart: Eq? = null

    private val id: String? get() = library.currentId.value

    fun select(bandId: Long?) { _selected.value = bandId }

    fun edit(f: (Eq) -> Eq) { id?.let { library.update(it, 150) { p -> p.edit(f(p.working)) } } }

    fun beginDrag() { dragStart = library.current()?.working }

    fun dragTo(f: (Eq) -> Eq) { id?.let { library.update(it, 600) { p -> p.replaceWorking(f(p.working)) } } }

    fun endDrag() {
        val start = dragStart ?: return
        dragStart = null
        id?.let { library.update(it, 0) { p ->
            if (start == p.working) p else p.copy(undo = (p.undo + start).takeLast(Profile.HISTORY), redo = emptyList())
        } }
    }

    fun updateBand(bandId: Long, f: (Band) -> Band) = edit { eq -> eq.copy(bands = eq.bands.map { if (it.id == bandId) f(it) else it }) }

    fun dragBand(bandId: Long, f: (Band) -> Band) = dragTo { eq -> eq.copy(bands = eq.bands.map { if (it.id == bandId) f(it) else it }) }

    fun addBand(): Boolean {
        val p = library.current() ?: return false
        if (p.working.bands.size >= MAX_BANDS) { messages.show("The Protocol Max has $MAX_BANDS bands."); return false }
        val b = Band(FilterType.PEAK, freeFrequency(p.working.bands), 0.0, 1.0)
        edit { it.copy(bands = it.bands + b) }
        _selected.value = b.id
        return true
    }

    fun removeBand(bandId: Long) {
        val p = library.current() ?: return
        val idx = p.working.bands.indexOfFirst { it.id == bandId }
        if (idx < 0) return
        edit { it.copy(bands = it.bands.filterNot { b -> b.id == bandId }) }
        if (_selected.value == bandId) _selected.value = null
        messages.show("Band ${idx + 1} deleted", "Undo") { undo() }
    }

    fun undo() { id?.let { library.update(it, 0) { p -> p.undo() } } }
    fun redo() { id?.let { library.update(it, 0) { p -> p.redo() } } }
    fun save() { id?.let { library.update(it, 0) { p -> p.save() } } }
    fun revert() { id?.let { library.update(it, 0) { p -> p.revert() } } }

    /** Writes the current working copy to the dongle (write -> verify -> commit). */
    suspend fun send(): WriteOutcome? {
        val p = library.current() ?: return null
        val r = Planner.plan(p.working)
        val plan = r.plan ?: run { messages.show(r.issues.firstOrNull() ?: "Can't send this EQ."); return null }
        val out = devices.write(plan, devices.lastState.value?.slot, p.name)
        if (out.ok) {
            library.markOnDevice(p.id, p.working)
            messages.show("“${p.name}” is on the dongle")
        } else messages.show("Send failed: ${out.lines.firstOrNull() ?: ""}")
        return out
    }

    companion object {
        const val MAX_BANDS = 10

        /** Centre of the widest log-frequency gap between existing bands (or 1 kHz). */
        fun freeFrequency(bands: List<Band>): Double {
            val fs = (listOf(20.0, 20000.0) + bands.map { it.freqHz }).map { ln(it.coerceIn(20.0, 20000.0)) }.sorted()
            var best = 0; var gap = -1.0
            for (i in 0 until fs.size - 1) if (fs[i + 1] - fs[i] > gap) { gap = fs[i + 1] - fs[i]; best = i }
            if (bands.isEmpty()) return 1000.0
            val f = kotlin.math.exp((fs[best] + fs[best + 1]) / 2)
            return (sqrt(f) * sqrt(f)).let { Math.round(it).toDouble() }
        }
    }
}
