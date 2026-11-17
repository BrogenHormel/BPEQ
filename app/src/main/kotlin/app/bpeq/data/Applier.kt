package app.bpeq.data

import android.os.Handler
import android.os.Looper
import android.widget.Toast
import app.bpeq.BpeqApp
import app.bpeq.core.device.MaxLimits
import app.bpeq.core.device.Planner
import app.bpeq.core.device.WritePlan
import app.bpeq.core.model.Eq
import app.bpeq.device.DeviceStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Applies a saved profile to the dongle from anywhere (plug-in prompt, Quick Settings tile, widget), and runs the
 * A/B toggle. Always the full safe path: plan -> write -> verify -> commit -> verify.
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
        endAb()
        val out = app.devices.write(plan, app.devices.lastState.value?.slot, p.name)
        if (out.ok) { app.library.markOnDevice(p.id, eq); notify("“${p.name}” is on the dongle") }
        else notify("Couldn't apply “${p.name}”: ${out.lines.firstOrNull() ?: ""}")
        return out.ok
    }

    fun applyInBackground(id: String) = scope.launch { apply(id) { toast(it) } }

    // ---- A/B (only offered once the RAM-only test proved it) -------------------------------------------------

    private val _abFlat = MutableStateFlow(false)
    /** True while the dongle temporarily plays flat (B). */
    val abFlat: StateFlow<Boolean> = _abFlat.asStateFlow()

    /** B = the same preamp with every band flat, so loudness stays comparable; RAM only, never saved. */
    fun flatPlanKeepingPreamp(eq: Eq): WritePlan =
        WritePlan(List(MaxLimits.BANDS) { MaxLimits.flatRegisters(it) }, Planner.plan(eq).preampDb)

    suspend fun toggleAb(eq: Eq): Boolean {
        val plan = if (_abFlat.value) Planner.plan(eq).plan ?: return false else flatPlanKeepingPreamp(eq)
        val ok = app.devices.writeTemporary(plan)
        if (ok) _abFlat.value = !_abFlat.value
        return ok
    }

    /** Leaves B (if active) by putting the profile back, still RAM only (it is already saved on the dongle). */
    suspend fun endAb() {
        if (!_abFlat.value) return
        val id = app.library.onDevice.value?.first
        val eq = id?.let { app.library.get(it)?.saved } ?: return
        Planner.plan(eq).plan?.let { if (app.devices.writeTemporary(it)) _abFlat.value = false }
    }

    fun resetAb() { _abFlat.value = false }
}
