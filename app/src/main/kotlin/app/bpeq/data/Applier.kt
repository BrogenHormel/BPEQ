package app.bpeq.data

import android.os.Handler
import android.os.Looper
import android.widget.Toast
import app.bpeq.BpeqApp
import app.bpeq.core.device.MaxLimits
import app.bpeq.core.device.Planner
import app.bpeq.core.device.WritePlan
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import app.bpeq.device.DeviceStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Applies a saved profile to the dongle from anywhere (plug-in prompt, Quick Settings tile, widget). Always the full
 * safe path: plan -> write -> verify -> commit -> verify. Also runs the A/B switch (see [toggleAb]).
 */
object Applier {
    private val app get() = BpeqApp.instance
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    fun toast(text: String) = Handler(Looper.getMainLooper()).post { Toast.makeText(app, text, Toast.LENGTH_SHORT).show() }

    /** Why a background apply can't run right now, or null if it can. */
    fun blocker(): String? {
        val st = app.devices.status.value
        return when {
            st == DeviceStatus.NoDevice -> "No Protocol Max connected"
            st == DeviceStatus.NeedsPermission || st == DeviceStatus.PermissionDenied -> "Open BPEQ to allow USB access"
            !app.devices.firstWriteDone.value && st != DeviceStatus.Simulated -> "Send once from the BPEQ app first (the first write is confirmed byte by byte)"
            st is DeviceStatus.Busy -> "The dongle is busy"
            else -> null
        }
    }

    /** Applies the saved version of profile [id] (plus its tone). */
    suspend fun apply(id: String, notifyUi: (String) -> Unit = { app.messages.show(it) }): Boolean {
        val notify: (String) -> Unit = { app.log.info("apply: $it"); notifyUi(it) }
        app.devices.refreshStatus()
        app.log.info("apply request for profile $id (status ${app.devices.status.value})")
        blocker()?.let { app.log.warn("apply blocked: $it"); notify(it); return false }
        val p = app.library.get(id) ?: run { notify("Profile not found"); return false }
        val eq = p.saved
        val plan = Planner.plan(eq).plan ?: run { notify("“${p.name}” can't be sent as is; open it and use Rebuild"); return false }
        val out = app.devices.write(plan, app.devices.lastState.value?.slot, p.name)
        if (out.ok) { app.library.markOnDevice(p.id, eq); endAbState(); notify("“${p.name}” is on the dongle") }
        else notify("Couldn't apply “${p.name}”: ${out.lines.firstOrNull() ?: ""}")
        return out.ok
    }

    fun applyInBackground(id: String) = scope.launch { apply(id) { toast(it) } }

    // ---- A/B --------------------------------------------------------------------------------------------------
    // On the Protocol Max (firmware 1.4) every band write is stored permanently (docs/HARDWARE.md), so each switch
    // wears the dongle's flash a little. Only the bands that change are written, without the save command; switches
    // and register writes are counted, and past [DAILY_ALLOWANCE] switches a day the user is asked before each block.

    private val prefs get() = app.getSharedPreferences("settings", android.content.Context.MODE_PRIVATE)

    /** Profile id while the dongle plays B (flat, same preamp), or null. Persisted: B survives an app restart. */
    private val _abFlatFor = MutableStateFlow<String?>(null)
    val abFlatFor: StateFlow<String?> = _abFlatFor.asStateFlow()

    data class AbStats(val today: Int, val total: Int, val writes: Int, val allowance: Int)
    private val _abStats = MutableStateFlow(AbStats(0, 0, 0, DAILY_ALLOWANCE))
    val abStats: StateFlow<AbStats> = _abStats.asStateFlow()

    private fun today() = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US).format(java.util.Date())

    /** Loads the persisted A/B state; call once at app start. */
    fun loadAb() {
        _abFlatFor.value = prefs.getString(KEY_AB_FLAT, null)
        refreshStats()
    }

    private fun refreshStats() {
        val p = prefs
        val sameDay = p.getString(KEY_AB_DAY, null) == today()
        _abStats.value = AbStats(
            today = if (sameDay) p.getInt(KEY_AB_TODAY, 0) else 0,
            total = p.getInt(KEY_AB_TOTAL, 0),
            writes = p.getInt(KEY_AB_WRITES, 0),
            allowance = if (sameDay) p.getInt(KEY_AB_ALLOWANCE, DAILY_ALLOWANCE) else DAILY_ALLOWANCE,
        )
    }

    /** True when the next switch should be confirmed first (today's allowance used up). */
    fun abNeedsConfirm(): Boolean { refreshStats(); return _abStats.value.today >= _abStats.value.allowance }

    /** The user agreed to [DAILY_ALLOWANCE] more switches today. */
    fun abConfirmMore() {
        refreshStats()
        val s = _abStats.value
        prefs.edit().putString(KEY_AB_DAY, today()).putInt(KEY_AB_TODAY, s.today).putInt(KEY_AB_ALLOWANCE, s.today + DAILY_ALLOWANCE).apply()
        refreshStats()
    }

    private fun count(writes: Int) {
        refreshStats()
        val s = _abStats.value
        prefs.edit().putString(KEY_AB_DAY, today()).putInt(KEY_AB_TODAY, s.today + 1).putInt(KEY_AB_ALLOWANCE, s.allowance)
            .putInt(KEY_AB_TOTAL, s.total + 1).putInt(KEY_AB_WRITES, s.writes + writes).apply()
        refreshStats()
    }

    private fun setFlat(id: String?) {
        _abFlatFor.value = id
        prefs.edit().apply { if (id == null) remove(KEY_AB_FLAT) else putString(KEY_AB_FLAT, id) }.apply()
    }

    /** A full profile write replaced whatever A/B left on the dongle. */
    fun endAbState() = setFlat(null)

    private val _offTrim = MutableStateFlow<Map<String, Int>>(emptyMap())
    /** Per-profile EQ Off level adjustment (dB) on top of the loudness match, set by ear. */
    val offTrim: StateFlow<Map<String, Int>> = _offTrim.asStateFlow()

    fun trimFor(id: String): Int = _offTrim.value[id] ?: prefs.getInt(KEY_AB_TRIM + id, 0).also { v ->
        if (v != 0) _offTrim.value = _offTrim.value + (id to v)
    }

    /** EQ Off preamp for [a]: loudness-matched (Planner.loudnessMatchedFlatPreamp) plus the profile's trim. */
    fun offPreamp(a: WritePlan, id: String): Int =
        (Planner.loudnessMatchedFlatPreamp(a) + trimFor(id)).coerceIn(MaxLimits.PREAMP_MIN, MaxLimits.PREAMP_MAX)

    /** EQ Off = every band flat, preamp set to sound as loud as the EQ does. */
    private fun flatPlan(a: WritePlan, id: String) = WritePlan(List(MaxLimits.BANDS) { MaxLimits.flatRegisters(it) }, offPreamp(a, id))

    /**
     * Changes the EQ Off level of [profileId] by [deltaDb]. If the dongle is on EQ Off for it, the new preamp is written
     * right away (a single preamp write; bands are unchanged). Returns a message, or null if nothing changed.
     */
    suspend fun adjustOffLevel(profileId: String, deltaDb: Int): String? {
        val (id, eq) = app.library.onDevice.value ?: return null
        if (id != profileId) return null
        val a = Planner.plan(eq).plan ?: return null
        val before = offPreamp(a, id)
        val trim = (trimFor(id) + deltaDb).coerceIn(-TRIM_MAX, TRIM_MAX)
        _offTrim.value = _offTrim.value + (id to trim)
        prefs.edit().putInt(KEY_AB_TRIM + id, trim).apply()
        val after = offPreamp(a, id)
        if (after == before) return if (after == MaxLimits.PREAMP_MAX) "EQ Off is already at the loudest preamp (0 dB)" else null
        if (_abFlatFor.value != id) return null
        val (ok, writes) = app.devices.writeAb(flatPlan(a, id), "EQ Off level")
        if (writes > 0) { refreshStats(); val s = _abStats.value; prefs.edit().putInt(KEY_AB_WRITES, s.writes + writes).apply(); refreshStats() }
        return if (ok) null else "Changing the EQ Off level failed; see the Log"
    }

    /**
     * Switches between A (the profile on the dongle) and B (EQ off, same preamp). Only for the profile the library
     * records as on the dongle. Returns a message for the user.
     */
    suspend fun toggleAb(profileId: String): String {
        val (id, eq) = app.library.onDevice.value ?: return "Send the profile to the dongle first"
        if (id != profileId) return "Send this profile to the dongle first"
        val a = Planner.plan(eq).plan ?: return "This profile can't be sent as is"
        val toFlat = _abFlatFor.value != id
        app.log.info("compare: EQ On preamp ${a.preampDb} dB; EQ Off preamp ${offPreamp(a, id)} dB (loudness match " +
            "${Planner.loudnessMatchedFlatPreamp(a)} dB, EQ loudness ${"%.2f".format(Planner.loudnessDb(a.registers))} dB, trim ${trimFor(id)} dB)")
        val (ok, writes) = app.devices.writeAb(if (toFlat) flatPlan(a, id) else a, if (toFlat) "EQ Off" else "EQ On")
        if (writes > 0) count(writes)
        if (!ok) return "Switching the EQ ${if (toFlat) "off" else "on"} failed; see the Log"
        setFlat(if (toFlat) id else null)
        val s = _abStats.value
        return (if (toFlat) "EQ Off" else "EQ On") + " · ${s.today} switch(es) today"
    }

    private val _abBusy = MutableStateFlow(false)
    val abBusy: StateFlow<Boolean> = _abBusy.asStateFlow()

    /**
     * Runs [toggleAb] in the app scope: the UI that started it may leave the composition while the dongle is busy,
     * and cancelling mid-write would lose track of what the dongle now plays.
     */
    fun toggleAbInBackground(profileId: String) {
        if (_abBusy.value) return
        _abBusy.value = true
        scope.launch {
            try { app.messages.show(toggleAb(profileId)) } finally { _abBusy.value = false }
        }
    }

    private const val DAILY_ALLOWANCE = 20
    private const val KEY_AB_FLAT = "ab_flat_for"
    private const val KEY_AB_DAY = "ab_day"
    private const val KEY_AB_TODAY = "ab_today"
    private const val KEY_AB_ALLOWANCE = "ab_allowance"
    private const val KEY_AB_TOTAL = "ab_total"
    private const val KEY_AB_WRITES = "ab_writes"
    private const val KEY_AB_TRIM = "ab_trim_"
    private const val TRIM_MAX = 6
}
