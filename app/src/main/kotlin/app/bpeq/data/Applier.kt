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

    /** B = every band flat with A's preamp, so loudness stays comparable. */
    private fun flatPlan(a: WritePlan) = WritePlan(List(MaxLimits.BANDS) { MaxLimits.flatRegisters(it) }, a.preampDb)

    /**
     * Switches between A (the profile on the dongle) and B (EQ off, same preamp). Only for the profile the library
     * records as on the dongle. Returns a message for the user.
     */
    suspend fun toggleAb(profileId: String): String {
        val (id, eq) = app.library.onDevice.value ?: return "Send the profile to the dongle first"
        if (id != profileId) return "Send this profile to the dongle first"
        val a = Planner.plan(eq).plan ?: return "This profile can't be sent as is"
        val toFlat = _abFlatFor.value != id
        val (ok, writes) = app.devices.writeAb(if (toFlat) flatPlan(a) else a, if (toFlat) "B: EQ off" else "A: profile")
        if (writes > 0) count(writes)
        if (!ok) return "A/B switch failed; see the Log"
        setFlat(if (toFlat) id else null)
        val s = _abStats.value
        return (if (toFlat) "B: EQ off" else "A: EQ on") + " · $writes band write(s) · ${s.today} switch(es) today"
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
}
