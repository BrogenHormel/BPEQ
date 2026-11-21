package app.bpeq.core.tune

import app.bpeq.core.device.MaxLimits
import app.bpeq.core.device.MaxProtocol
import app.bpeq.core.dsp.FreqGrid
import app.bpeq.core.dsp.Response
import app.bpeq.core.model.Band
import app.bpeq.core.model.Eq
import app.bpeq.core.model.FilterType
import app.bpeq.core.model.PreampMode
import app.bpeq.core.model.Tone
import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.sqrt

/**
 * Blends the tone sliders into a profile while staying within the Protocol Max's 10 bands.
 * The tone filters are appended; if that exceeds 10 enabled bands, the closest same-type pairs are merged and
 * everything is refit (storable-only) to the exact combined curve.
 */
object ToneBlend {
    const val BASS_HZ = 105.0
    const val TREBLE_HZ = 2500.0
    const val EAR_HZ = 3000.0
    const val BASS_ID = -1L
    const val TREBLE_ID = -2L
    const val EAR_ID = -3L

    fun toneBands(t: Tone): List<Band> = buildList {
        if (t.bassDb != 0.0) add(Band(FilterType.LOW_SHELF, BASS_HZ, t.bassDb, 0.7, id = BASS_ID))
        if (t.trebleDb != 0.0) add(Band(FilterType.HIGH_SHELF, TREBLE_HZ, t.trebleDb, 0.4, id = TREBLE_ID))
        if (t.earGainDb != 0.0) add(Band(FilterType.PEAK, EAR_HZ, t.earGainDb, 1.2, id = EAR_ID))
    }

    /**
     * The tone bands as they are sent. A treble boost of about +2 dB or more at 2.5 kHz overflows the Protocol Max's
     * coefficient range as a high shelf, so it is sent as the equivalent low shelf cut (H_HS(g) = g dB * H_LS(-g)):
     * same shape, [levelDb] quieter, which the AUTO preamp absorbs (a MANUAL preamp is raised by [levelDb]).
     */
    private fun sendBands(t: Tone): List<Band> = toneBands(t).map { b ->
        if (b.type == FilterType.HIGH_SHELF && !storable(b)) b.copy(type = FilterType.LOW_SHELF, gainDb = -b.gainDb) else b
    }

    /** Level the sent tone bands are below the intended curve (0 unless the treble shelf had to be flipped). */
    fun levelDb(t: Tone): Double = toneBands(t).zip(sendBands(t)).sumOf { (a, b) -> if (a.type != b.type) a.gainDb else 0.0 }

    /** The intended combined curve (bands + tone), level included, as drawn on the graph. */
    fun curve(eq: Eq, grid: FreqGrid): DoubleArray {
        val c = Response.ideal(apply(eq).bands, grid)
        val lv = levelDb(eq.tone)
        if (lv != 0.0) for (i in c.indices) c[i] += lv
        return c
    }

    private fun storable(b: Band) = MaxProtocol.storable(MaxLimits.quantize(b))

    private val GRID = FreqGrid.log(160)
    /**
     * [eq]: what is sent (bands + tone, at most [MaxLimits.BANDS] enabled). [levelDb]: see [levelDb].
     * [mergedDiffDb]: set when the tone didn't fit in the free bands and everything was merged and refit; the largest
     * difference to the exact combined curve.
     */
    data class Blend(val eq: Eq, val levelDb: Double = 0.0, val mergedDiffDb: Double? = null)

    private val cache = object : LinkedHashMap<Eq, Blend>(16, 0.75f, true) {
        override fun removeEldestEntry(e: MutableMap.MutableEntry<Eq, Blend>?) = size > 32
    }

    /** The EQ that is actually sent: bands + tone, at most [MaxLimits.BANDS] enabled bands. */
    fun apply(eq: Eq): Eq = blend(eq).eq

    fun blend(eq: Eq): Blend {
        if (eq.tone.isZero) return Blend(eq)
        synchronized(cache) { cache[eq]?.let { return it } }
        var mergedDiff: Double? = null
        val combined = eq.bands + sendBands(eq.tone)
        val result = if (combined.count { it.enabled } <= MaxLimits.BANDS) {
            eq.copy(bands = combined)
        } else {
            val enabled = combined.filter { it.enabled }.toMutableList()
            val target = Response.ideal(enabled, GRID)
            while (enabled.size > MaxLimits.BANDS) {
                var best = -1 to -1; var dist = Double.MAX_VALUE
                for (i in enabled.indices) for (j in i + 1 until enabled.size) {
                    if (enabled[i].type != enabled[j].type) continue
                    val d = abs(ln(enabled[i].freqHz / enabled[j].freqHz))
                    if (d < dist) { dist = d; best = i to j }
                }
                if (best.first < 0) { best = 0 to 1 } // no same-type pair: merge the first two (refit fixes the shape)
                val a = enabled[best.first]; val b = enabled[best.second]
                enabled[best.first] = a.copy(freqHz = sqrt(a.freqHz * b.freqHz), gainDb = (a.gainDb + b.gainDb).coerceIn(-10.0, 10.0))
                enabled.removeAt(best.second)
            }
            val fit = Fitter(GRID, target, DoubleArray(GRID.size) { if (GRID.freqs[it] > 16_000) 0.25 else 1.0 },
                Bounds(fMin = 20.0, fMax = 20_000.0, qMin = 0.1, qMax = 10.0, shelfQMin = 0.1, shelfQMax = 2.0, lowShelfMaxHz = 20_000.0, highShelfMinHz = 20.0),
                freeOffset = false).fit(enabled, 40)
            val rounded = fit.bands.map { it.copy(freqHz = Math.round(it.freqHz).toDouble(), gainDb = Math.round(it.gainDb * 100) / 100.0, q = Math.round(it.q * 1000) / 1000.0) }
            val got = Response.ideal(rounded, GRID)
            mergedDiff = GRID.freqs.indices.filter { GRID.freqs[it] <= 16_000 }.maxOf { abs(got[it] - target[it]) }
            eq.copy(bands = rounded)
        }
        val lv = levelDb(eq.tone)
        val out = Blend(result.copy(tone = Tone(), manualPreampDb = if (eq.preampMode == PreampMode.MANUAL) eq.manualPreampDb + lv else eq.manualPreampDb), lv, mergedDiff)
        synchronized(cache) { cache[eq] = out }
        return out
    }
}
