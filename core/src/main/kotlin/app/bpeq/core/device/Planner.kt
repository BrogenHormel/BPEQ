package app.bpeq.core.device

import app.bpeq.core.device.MaxProtocol.Registers
import app.bpeq.core.dsp.FreqGrid
import app.bpeq.core.dsp.Response
import app.bpeq.core.model.Eq
import app.bpeq.core.model.PreampMode
import kotlin.math.ceil
import kotlin.math.max

/**
 * Turns an [Eq] (what the user intends) into exactly what the Protocol Max will store: clamped and quantised
 * registers for the enabled bands, factory-flat spare slots, and a whole-dB preamp.
 */
object Planner {
    /** A band the device cannot store (coefficient overflow), by its index in [Eq.bands]. */
    data class Unstorable(val bandIndex: Int, val reason: String)

    data class Result(
        val registers: List<Registers>,
        val preampDb: Int,
        /** Enabled bands mapped to device slots: bandIndex -> slot. */
        val slotOf: Map<Int, Int>,
        val unstorable: List<Unstorable>,
        val tooManyBands: Boolean,
        /** Values the device can't hold exactly were clamped/rounded (gain/Q/freq/preamp). */
        val clampNotes: List<String>,
        /** Preamp AUTO wanted more attenuation than -30 dB. */
        val preampFloorHit: Boolean,
    ) {
        val ok: Boolean get() = unstorable.isEmpty() && !tooManyBands
        val plan: WritePlan? get() = if (ok) WritePlan(registers, preampDb) else null
        val issues: List<String> get() = buildList {
            if (tooManyBands) add("More than ${MaxLimits.BANDS} enabled bands; the Protocol Max has ${MaxLimits.BANDS}.")
            unstorable.forEach { add("Band ${it.bandIndex + 1} can't be stored by the device (${it.reason}).") }
        }
    }

    private val SCAN = FreqGrid.log(512)

    /** Peak of the device-domain response of [regs] in dB (from the exact Q30 coefficients). */
    fun peakDb(regs: List<Registers>): Double {
        val coeffs = regs.mapNotNull { r -> runCatching { MaxProtocol.deviceCoeffs(r) }.getOrNull() }
        val centres = FreqGrid(regs.map { it.freqHz.coerceIn(20.0, 20000.0) }.toDoubleArray())
        return max(Response.coeffs(coeffs, SCAN).max(), Response.coeffs(coeffs, centres).max())
    }

    /** Auto preamp: enough whole-dB attenuation that the peak stays at or below 0 dB. Never positive. */
    fun autoPreamp(regs: List<Registers>): Int {
        val peak = peakDb(regs)
        return if (peak <= 1e-6) 0 else -ceil(peak - 1e-6).toInt()
    }

    fun plan(input: Eq): Result {
        val eq = app.bpeq.core.tune.ToneBlend.apply(input)
        val enabled = eq.bands.withIndex().filter { it.value.enabled }
        val tooMany = enabled.size > MaxLimits.BANDS
        val used = enabled.take(MaxLimits.BANDS)
        val notes = ArrayList<String>()
        val unstorable = ArrayList<Unstorable>()
        val slotOf = HashMap<Int, Int>()
        val regs = List(MaxLimits.BANDS) { slot ->
            val iv = used.getOrNull(slot) ?: return@List MaxLimits.flatRegisters(slot)
            slotOf[iv.index] = slot
            val b = iv.value
            val r = MaxLimits.quantize(b)
            if (b.gainDb !in MaxLimits.GAIN_MIN..MaxLimits.GAIN_MAX) notes += "Band ${iv.index + 1}: gain clamped to ${r.gainDb} dB"
            if (b.q !in MaxLimits.Q_MIN..MaxLimits.Q_MAX) notes += "Band ${iv.index + 1}: Q clamped to ${"%.2f".format(r.q)}"
            if (b.freqHz !in MaxLimits.FREQ_MIN.toDouble()..MaxLimits.FREQ_MAX.toDouble()) notes += "Band ${iv.index + 1}: frequency clamped to ${r.freq} Hz"
            try { MaxProtocol.q30(r) } catch (e: MaxProtocol.Q30Overflow) { unstorable += Unstorable(iv.index, e.message ?: "overflow") }
            r
        }
        val storableRegs = regs.filter { MaxProtocol.storable(it) }
        val auto = autoPreamp(storableRegs)
        val wanted = when (eq.preampMode) {
            PreampMode.AUTO -> auto.toDouble()
            PreampMode.MANUAL -> eq.manualPreampDb
        }
        val preamp = MaxLimits.quantizePreamp(wanted)
        val floorHit = eq.preampMode == PreampMode.AUTO && auto < MaxLimits.PREAMP_MIN
        if (eq.preampMode == PreampMode.MANUAL && preamp.toDouble() != eq.manualPreampDb) notes += "Preamp sent as $preamp dB (whole dB, rounded down)"
        if (floorHit) notes += "Auto preamp needs $auto dB; the device stops at ${MaxLimits.PREAMP_MIN} dB, so it may clip"
        return Result(regs, preamp, slotOf, unstorable, tooMany, notes, floorHit)
    }

    /** The response the device will actually produce (Q30 coefficients + preamp), over [grid]. */
    fun deviceCurve(r: Result, grid: FreqGrid, out: DoubleArray = DoubleArray(grid.size)): DoubleArray {
        val coeffs = r.registers.mapNotNull { runCatching { MaxProtocol.deviceCoeffs(it) }.getOrNull() }
        Response.coeffs(coeffs, grid, out)
        for (i in out.indices) out[i] += r.preampDb
        return out
    }

    /**
     * What's on the device as an editable EQ: every slot that isn't a 0 dB peak becomes a band (0 dB peaks do
     * nothing, so spare/flat slots are left out), preamp as a manual value.
     */
    fun eqFromDevice(state: DeviceState): Eq {
        val bands = state.bands.filter { !it.disabled && it.registers.type != null && !it.registers.isZeroGainPeak }
            .map { MaxLimits.toBand(it.registers) }
        return Eq(bands, PreampMode.MANUAL, state.preampDb.toDouble())
    }

    /** True if the device registers/preamp in [state] are exactly what [eq] would write. */
    fun matchesDevice(eq: Eq, state: DeviceState): Boolean {
        val p = plan(eq)
        return p.ok && p.registers == state.registers && p.preampDb == state.preampDb
    }
}
