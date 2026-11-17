package app.bpeq.core.tune

import app.bpeq.core.device.MaxLimits
import app.bpeq.core.dsp.FreqGrid
import app.bpeq.core.dsp.Response
import app.bpeq.core.model.Band
import app.bpeq.core.model.Eq
import app.bpeq.core.model.FilterType
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

    fun toneBands(t: Tone): List<Band> = buildList {
        if (t.bassDb != 0.0) add(Band(FilterType.LOW_SHELF, BASS_HZ, t.bassDb, 0.7, id = -1))
        if (t.trebleDb != 0.0) add(Band(FilterType.HIGH_SHELF, TREBLE_HZ, t.trebleDb, 0.4, id = -2))
        if (t.earGainDb != 0.0) add(Band(FilterType.PEAK, EAR_HZ, t.earGainDb, 1.2, id = -3))
    }

    private val GRID = FreqGrid.log(160)
    private val cache = object : LinkedHashMap<Eq, Eq>(16, 0.75f, true) {
        override fun removeEldestEntry(e: MutableMap.MutableEntry<Eq, Eq>?) = size > 32
    }

    /** The EQ that is actually sent: bands + tone, at most [MaxLimits.BANDS] enabled bands. */
    fun apply(eq: Eq): Eq {
        if (eq.tone.isZero) return eq
        synchronized(cache) { cache[eq]?.let { return it } }
        val combined = eq.bands + toneBands(eq.tone)
        val result = if (combined.count { it.enabled } <= MaxLimits.BANDS) {
            eq.copy(bands = combined, tone = Tone())
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
            eq.copy(bands = fit.bands.map { it.copy(freqHz = Math.round(it.freqHz).toDouble(), gainDb = Math.round(it.gainDb * 100) / 100.0, q = Math.round(it.q * 1000) / 1000.0) }, tone = Tone())
        }
        synchronized(cache) { cache[eq] = result }
        return result
    }
}
