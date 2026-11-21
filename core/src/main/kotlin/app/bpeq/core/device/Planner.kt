package app.bpeq.core.device

import app.bpeq.core.device.MaxProtocol.Registers
import app.bpeq.core.dsp.FreqGrid
import app.bpeq.core.dsp.Response
import app.bpeq.core.model.Band
import app.bpeq.core.model.Eq
import app.bpeq.core.model.FilterType
import app.bpeq.core.tune.ToneBlend
import app.bpeq.core.model.PreampMode
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.max

/**
 * Turns an [Eq] (what the user intends) into exactly what the Protocol Max will store: clamped and quantised
 * registers for the enabled bands, factory-flat spare slots, and a whole-dB preamp.
 */
object Planner {
    /** A band the device cannot store (coefficient overflow), by its index in [Eq.bands]; [label] names it for the user. */
    data class Unstorable(val bandIndex: Int, val reason: String, val label: String = "Band ${bandIndex + 1}")

    data class Result(
        val registers: List<Registers>,
        val preampDb: Int,
        /** Enabled bands mapped to device slots: bandIndex -> slot. */
        val slotOf: Map<Int, Int>,
        val unstorable: List<Unstorable>,
        val tooManyBands: Boolean,
        /** Informational: values adjusted to fit the device (clamped/rounded, shelves flipped, tone blended). */
        val clampNotes: List<String>,
        /** Preamp AUTO wanted more attenuation than -30 dB. */
        val preampFloorHit: Boolean,
        /** Sendable, but worth attention (e.g. a manual preamp that lets the output clip). */
        val warnings: List<String> = emptyList(),
        /**
         * How far the sent curve sits below the intended one (dB). Flipped shelves are sent g dB quieter; AUTO preamp
         * absorbs it, MANUAL preamp is raised by it. Add it to [deviceCurve] - preamp to compare shapes.
         */
        val levelDb: Double = 0.0,
    ) {
        val ok: Boolean get() = unstorable.isEmpty() && !tooManyBands
        val plan: WritePlan? get() = if (ok) WritePlan(registers, preampDb) else null
        val issues: List<String> get() = buildList {
            if (tooManyBands) add("More than ${MaxLimits.BANDS} enabled bands; the Protocol Max has ${MaxLimits.BANDS}.")
            unstorable.forEach { add("${it.label} can't be stored by the device (${it.reason}).") }
        }
    }

    /**
     * The same curve with a storable shelf, or null. H_HS(g) = g dB * H_LS(-g) (and vice versa), so a shelf the
     * device can't hold is sent as the opposite shelf with the opposite gain, g dB quieter overall.
     */
    fun flipShelf(b: Band): Band? {
        val other = when (b.type) {
            FilterType.HIGH_SHELF -> FilterType.LOW_SHELF
            FilterType.LOW_SHELF -> FilterType.HIGH_SHELF
            FilterType.PEAK -> return null
        }
        val f = b.copy(type = other, gainDb = -b.gainDb)
        return if (MaxProtocol.storable(MaxLimits.quantize(f))) f else null
    }

    /** User-facing name of band [i] of the blended EQ: tone bands by slider, user bands by their number. */
    private fun label(input: Eq, b: Band, i: Int): String = when (b.id) {
        ToneBlend.BASS_ID -> "Bass tone"
        ToneBlend.TREBLE_ID -> "Treble tone"
        ToneBlend.EAR_ID -> "Ear gain tone"
        else -> input.bands.indexOfFirst { it.id == b.id }.let { k -> "Band ${(if (k >= 0) k else i) + 1}" }
    }

    private val SCAN = FreqGrid.log(512)

    /** Peak of the device-domain response of [regs] in dB (from the exact Q30 coefficients). */
    fun peakDb(regs: List<Registers>): Double {
        val coeffs = regs.mapNotNull { r -> runCatching { MaxProtocol.deviceCoeffs(r) }.getOrNull() }
        val centres = FreqGrid(regs.map { it.freqHz.coerceIn(20.0, 20000.0) }.toDoubleArray())
        return max(Response.coeffs(coeffs, SCAN).max(), Response.coeffs(coeffs, centres).max())
    }

    private val LOUDNESS_GRID = FreqGrid.log(240)

    /**
     * K-weighting power (ITU-R BS.1770: ~+4 dB high shelf above ~1.7 kHz and a high-pass below ~60 Hz), the weighting
     * loudness meters use for how loud something sounds.
     */
    private val K_WEIGHT: DoubleArray = DoubleArray(LOUDNESS_GRID.size) { i ->
        val f = LOUDNESS_GRID.freqs[i]
        val shelfDb = 4.0 / (1 + (1681.0 / f).let { it * it })           // smooth +4 dB above ~1.7 kHz
        val hp = (f / 38.0).let { r -> r * r * r * r / (1 + r * r * r * r) } // 2nd-order high-pass at ~38 Hz (power)
        Math.pow(10.0, shelfDb / 10) * hp
    }

    /**
     * How much louder (dB, negative = quieter) the EQ in [regs] makes pink noise sound, K-weighted, preamp excluded.
     * Used to set the "EQ Off" preamp so switching changes the tone, not the loudness.
     */
    fun loudnessDb(regs: List<Registers>): Double {
        val coeffs = regs.mapNotNull { r -> runCatching { MaxProtocol.deviceCoeffs(r) }.getOrNull() }
        val g = Response.coeffs(coeffs, LOUDNESS_GRID)
        var num = 0.0; var den = 0.0
        for (i in g.indices) { num += K_WEIGHT[i] * Math.pow(10.0, g[i] / 10); den += K_WEIGHT[i] }
        return 10 * Math.log10(num / den)
    }

    /**
     * Preamp for "EQ Off" (all bands flat) that sounds as loud as [plan] does with its EQ: its preamp plus the EQ's
     * loudness change, in whole dB like every device preamp, within the device range.
     */
    fun loudnessMatchedFlatPreamp(plan: WritePlan): Int =
        Math.round(plan.preampDb + loudnessDb(plan.registers)).toInt().coerceIn(MaxLimits.PREAMP_MIN, MaxLimits.PREAMP_MAX)

    /** Auto preamp: enough whole-dB attenuation that the peak stays at or below 0 dB. Never positive. */
    fun autoPreamp(regs: List<Registers>): Int {
        val peak = peakDb(regs)
        return if (peak <= 1e-6) 0 else -ceil(peak - 1e-6).toInt()
    }

    fun plan(input: Eq): Result {
        val blend = ToneBlend.blend(input)
        val eq = blend.eq
        val enabled = eq.bands.withIndex().filter { it.value.enabled }
        val tooMany = enabled.size > MaxLimits.BANDS
        val used = enabled.take(MaxLimits.BANDS)
        val notes = ArrayList<String>()
        val warnings = ArrayList<String>()
        val unstorable = ArrayList<Unstorable>()
        val slotOf = HashMap<Int, Int>()
        var flipLevel = 0.0
        val regs = List(MaxLimits.BANDS) { slot ->
            val iv = used.getOrNull(slot) ?: return@List MaxLimits.flatRegisters(slot)
            slotOf[iv.index] = slot
            val b = iv.value
            val name = label(input, b, iv.index)
            var r = MaxLimits.quantize(b)
            if (b.gainDb !in MaxLimits.GAIN_MIN..MaxLimits.GAIN_MAX) notes += "$name: gain clamped to ${r.gainDb} dB"
            if (b.q !in MaxLimits.Q_MIN..MaxLimits.Q_MAX) notes += "$name: Q clamped to ${"%.2f".format(r.q)}"
            if (b.freqHz !in MaxLimits.FREQ_MIN.toDouble()..MaxLimits.FREQ_MAX.toDouble()) notes += "$name: frequency clamped to ${r.freq} Hz"
            try {
                MaxProtocol.q30(r)
            } catch (e: MaxProtocol.Q30Overflow) {
                val flipped = flipShelf(b.copy(gainDb = MaxLimits.clampGain(b.gainDb)))
                if (flipped != null) {
                    r = MaxLimits.quantize(flipped)
                    flipLevel += -flipped.gainDb
                    // Tone shelves are flipped (and explained) by ToneBlend; only user bands get here.
                    notes += "$name: sent as a ${flipped.type.label.lowercase()} ${fmt(flipped.gainDb)} dB, the same shape " +
                        "${quieter(flipped.gainDb)} dB quieter (the device can't store this ${b.type.label.lowercase()}); the preamp makes up the level"
                } else {
                    unstorable += Unstorable(iv.index, e.message ?: "overflow", name)
                }
            }
            r
        }
        if (blend.levelDb != 0.0) notes += "Treble tone: sent as a low shelf cut, the same shape ${quieter(blend.levelDb)} dB quieter; the preamp makes up the level"
        blend.mergedDiffDb?.let { notes += "Tone blended into your bands to fit ${MaxLimits.BANDS} (largest difference ${"%.1f".format(it)} dB)" }
        val storableRegs = regs.filter { MaxProtocol.storable(it) }
        val auto = autoPreamp(storableRegs)
        val wanted = when (eq.preampMode) {
            PreampMode.AUTO -> auto.toDouble()
            PreampMode.MANUAL -> eq.manualPreampDb + flipLevel
        }
        val preamp = MaxLimits.quantizePreamp(wanted)
        val floorHit = eq.preampMode == PreampMode.AUTO && auto < MaxLimits.PREAMP_MIN
        if (eq.preampMode == PreampMode.MANUAL && preamp.toDouble() != wanted) notes += "Preamp sent as $preamp dB (whole dB, rounded down)"
        if (floorHit) warnings += "Auto preamp needs $auto dB; the device stops at ${MaxLimits.PREAMP_MIN} dB, so it may clip"
        if (eq.preampMode == PreampMode.MANUAL) {
            val loudest = peakDb(storableRegs) + preamp
            // Both preamps in the user's terms (before the flipped-shelf level), as the editor shows them.
            val level = blend.levelDb + flipLevel
            if (loudest > 0.05) warnings += "Manual preamp ${num(input.manualPreampDb)} dB lets the loudest point reach ${fmt(loudest)} dB, " +
                "so loud tracks may clip. Auto would use ${num(auto.coerceAtLeast(MaxLimits.PREAMP_MIN) - level)} dB."
        }
        return Result(regs, preamp, slotOf, unstorable, tooMany, notes, floorHit, warnings, blend.levelDb + flipLevel)
    }

    private fun fmt(x: Double) = (if (x > 0) "+" else "") + String.format(java.util.Locale.US, "%.1f", x)
    private fun num(x: Double) = if (x == Math.rint(x)) x.toInt().toString() else String.format(java.util.Locale.US, "%.1f", x)
    private fun quieter(x: Double) = String.format(java.util.Locale.US, "%.1f", abs(x))

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
