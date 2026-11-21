package app.bpeq.core.tune

import app.bpeq.core.device.MaxLimits
import app.bpeq.core.device.MaxProtocol
import app.bpeq.core.device.Planner
import app.bpeq.core.dsp.FreqGrid
import app.bpeq.core.dsp.Response
import app.bpeq.core.model.Band
import app.bpeq.core.model.Eq
import app.bpeq.core.model.Tone
import kotlin.math.abs

/**
 * Replaces filters the Protocol Max can't store (coefficient overflow) with an allowed set that matches the same
 * curve, instead of refusing to send. Unstorable shelves never get here: [Planner] sends them as the opposite shelf
 * automatically. What's left (very wide, large treble peak boosts):
 *  1. Split into two half-gain filters when a band is free, else reduce the gain until it fits.
 *  2. Refit all bands (storable-only steps) to the original curve, shape only.
 */
object Rebuild {
    data class Result(
        val eq: Eq,
        val changes: List<String>,
        /** Largest shape difference to the original curve, level-aligned, 20 Hz-20 kHz. */
        val maxDiffDb: Double,
        val ok: Boolean,
    )

    private val GRID = FreqGrid.log(200)

    /** Level-aligned max |a - b| (a constant offset is just preamp). */
    fun shapeDiff(a: DoubleArray, b: DoubleArray): Double {
        val mean = a.indices.sumOf { a[it] - b[it] } / a.size
        return a.indices.maxOf { abs(a[it] - b[it] - mean) }
    }

    private fun storable(b: Band) = MaxProtocol.storable(MaxLimits.quantize(b))

    fun rebuild(eq: Eq): Result {
        if (Planner.plan(eq).ok) return Result(eq, emptyList(), 0.0, true)
        // Rebuild works on the user's own bands. The planner's indices cover the tone bands blended in after
        // them (and a refit when tone overflows 10 bands), so plan the bands alone to find the ones to fix.
        val plan = Planner.plan(eq.copy(tone = Tone()))
        val original = Response.ideal(eq.bands, GRID)
        val changes = ArrayList<String>()
        val bands = eq.bands.toMutableList()
        val bad = plan.unstorable.map { it.bandIndex }.filter { it in eq.bands.indices }.toSet()

        for (i in bad.sortedDescending()) {
            val b = bands[i]
            if (bands.count { it.enabled } < MaxLimits.BANDS) {
                val half = b.copy(gainDb = b.gainDb / 2)
                if (storable(half)) {
                    bands[i] = half
                    bands.add(i + 1, half.copy(id = Band.nextId()))
                    changes += "Band ${i + 1}: split into two ${fmt(b.gainDb / 2)} dB ${b.type.label.lowercase()}s"
                    continue
                }
            }
            // Last resort: the largest storable gain of the same filter; the refit below recovers what it can.
            var g = b.gainDb
            while (abs(g) > 0.1 && !storable(b.copy(gainDb = g))) g -= if (g > 0) 0.25 else -0.25
            bands[i] = b.copy(gainDb = g)
            changes += "Band ${i + 1}: gain reduced to ${fmt(g)} dB to fit the device"
        }

        // Refit everything (shape only) to the original curve, never leaving the storable set.
        val weights = DoubleArray(GRID.size) { if (GRID.freqs[it] > 16_000) 0.25 else 1.0 }
        val fitter = Fitter(GRID, original, weights, Bounds(fMin = 20.0, fMax = 20_000.0, qMin = 0.1, qMax = 10.0, shelfQMin = 0.1, shelfQMax = 2.0, lowShelfMaxHz = 20_000.0, highShelfMinHz = 20.0))
        val enabled = bands.withIndex().filter { it.value.enabled }
        val start = Response.ideal(bands, GRID)
        val before = shapeDiff(original, start)
        var out = bands.toList()
        if (before > 0.05) {
            val fit = fitter.fit(enabled.map { it.value }, 60)
            if (shapeDiff(original, Response.ideal(fit.bands, GRID)) < before) {
                val refit = bands.toMutableList()
                enabled.forEachIndexed { k, iv -> refit[iv.index] = fit.bands[k] }
                out = refit
                changes += "Refit the bands to match the original curve"
            }
        }
        out = out.map { it.copy(freqHz = Math.round(it.freqHz).toDouble(), gainDb = Math.round(it.gainDb * 100) / 100.0, q = Math.round(it.q * 1000) / 1000.0) }
        val newEq = eq.copy(bands = out)
        val diff = shapeDiff(original, Response.ideal(newEq.bands, GRID))
        return Result(newEq, changes, diff, Planner.plan(newEq).ok)
    }

    private fun fmt(x: Double) = (if (x > 0) "+" else "") + String.format(java.util.Locale.US, "%.1f", x)
}
