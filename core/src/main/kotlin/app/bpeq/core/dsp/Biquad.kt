package app.bpeq.core.dsp

import app.bpeq.core.model.Band
import app.bpeq.core.model.FilterType
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * RBJ Audio-EQ-Cookbook biquads, evaluated at the WalkPlay firmware's design rate (96 kHz).
 *
 * Peaks use Q; shelves use the slope form with S = band Q (the convention of the WalkPlay firmware
 * and devicePEQ's computeIIRFilter, see [FilterType]).
 * Formulas follow devicePEQ (0BSD) / Contour (Apache-2.0) so the curve we draw is the curve the device runs.
 */
object Biquad {
    const val FS: Double = 96_000.0

    /** Normalised coefficients (a0 = 1). */
    data class Coeffs(val b0: Double, val b1: Double, val b2: Double, val a1: Double, val a2: Double) {
        val isFinite: Boolean get() = b0.isFinite() && b1.isFinite() && b2.isFinite() && a1.isFinite() && a2.isFinite()
    }

    /** True when the shelf radicand is real (otherwise the RBJ slope form has no real response). */
    fun shelfValid(gainDb: Double, s: Double): Boolean {
        val a = 10.0.pow(gainDb / 40.0)
        return (a + 1 / a) * (1 / s - 1) + 2 >= 0
    }

    /**
     * Un-normalised coefficients in devicePEQ's exact operation order (b0, b1, b2, a0, a1, a2).
     * Kept bit-compatible with computeIIRFilter so Q30 encoding matches the reference byte for byte.
     */
    fun raw(type: FilterType, freqHz: Double, gainDb: Double, q: Double, fs: Double = FS): DoubleArray {
        val a = sqrt(10.0.pow(gainDb / 20)) // == 10^(g/40)
        val w0 = freqHz * 6.283185307179586 / fs
        val s = sin(w0)
        val c = cos(w0)
        return when (type) {
            FilterType.PEAK -> {
                val alpha = s / (2 * q)
                doubleArrayOf(1 + alpha * a, -2 * c, 1 - alpha * a, 1 + alpha / a, -2 * c, 1 - alpha / a)
            }
            FilterType.LOW_SHELF, FilterType.HIGH_SHELF -> {
                val radicand = ((a + 1 / a) * (1 / q - 1) + 2).coerceAtLeast(0.0)
                val sa = (s / 2) * sqrt(radicand)
                val k = 2 * sqrt(a) * sa
                if (type == FilterType.LOW_SHELF) doubleArrayOf(
                    a * ((a + 1) - (a - 1) * c + k), 2 * a * ((a - 1) - (a + 1) * c), a * ((a + 1) - (a - 1) * c - k),
                    (a + 1) + (a - 1) * c + k, -2 * ((a - 1) + (a + 1) * c), (a + 1) + (a - 1) * c - k,
                ) else doubleArrayOf(
                    a * ((a + 1) + (a - 1) * c + k), -2 * a * ((a - 1) + (a + 1) * c), a * ((a + 1) + (a - 1) * c - k),
                    (a + 1) - (a - 1) * c + k, 2 * ((a - 1) - (a + 1) * c), (a + 1) - (a - 1) * c - k,
                )
            }
        }
    }

    fun design(type: FilterType, freqHz: Double, gainDb: Double, q: Double, fs: Double = FS): Coeffs {
        val r = raw(type, freqHz, gainDb, q, fs)
        return Coeffs(r[0] / r[3], r[1] / r[3], r[2] / r[3], r[4] / r[3], r[5] / r[3])
    }

    fun design(band: Band, fs: Double = FS): Coeffs = design(band.type, band.freqHz, band.gainDb, band.q, fs)
}

/**
 * Frequency grid with precomputed cos(w), cos(2w) so evaluating a band is a few
 * multiply-adds per point (cheap enough for every drag frame, allocation-free).
 */
class FreqGrid(val freqs: DoubleArray, fs: Double = Biquad.FS) {
    val size: Int get() = freqs.size
    private val cosW = DoubleArray(freqs.size) { cos(2 * Math.PI * freqs[it] / fs) }
    private val cos2W = DoubleArray(freqs.size) { cos(4 * Math.PI * freqs[it] / fs) }

    /** Adds the magnitude response (dB) of [c] to [out]. */
    fun addDb(c: Biquad.Coeffs, out: DoubleArray) {
        val nk = c.b0 * c.b0 + c.b1 * c.b1 + c.b2 * c.b2
        val n1 = 2 * (c.b0 * c.b1 + c.b1 * c.b2)
        val n2 = 2 * c.b0 * c.b2
        val dk = 1 + c.a1 * c.a1 + c.a2 * c.a2
        val d1 = 2 * (c.a1 + c.a1 * c.a2)
        val d2 = 2 * c.a2
        for (i in out.indices) {
            val num = nk + n1 * cosW[i] + n2 * cos2W[i]
            val den = dk + d1 * cosW[i] + d2 * cos2W[i]
            out[i] += 10 * log10(num / den)
        }
    }

    companion object {
        const val F_MIN = 20.0
        const val F_MAX = 20_000.0

        fun logFreqs(n: Int, fMin: Double = F_MIN, fMax: Double = F_MAX): DoubleArray {
            require(n >= 2)
            val span = ln(fMax / fMin)
            return DoubleArray(n) { fMin * exp(span * it / (n - 1)) }
        }

        fun log(n: Int, fMin: Double = F_MIN, fMax: Double = F_MAX) = FreqGrid(logFreqs(n, fMin, fMax))

        /** The graph grid: ~200 log-spaced points. */
        val DISPLAY: FreqGrid by lazy { log(200) }
    }
}

object Response {
    /** Sum of the enabled bands' responses in dB over [grid] (ideal, unquantised). */
    fun ideal(bands: List<Band>, grid: FreqGrid, out: DoubleArray = DoubleArray(grid.size)): DoubleArray {
        out.fill(0.0)
        for (b in bands) if (b.enabled) grid.addDb(Biquad.design(b), out)
        return out
    }

    fun band(band: Band, grid: FreqGrid, out: DoubleArray = DoubleArray(grid.size)): DoubleArray {
        out.fill(0.0)
        grid.addDb(Biquad.design(band), out)
        return out
    }

    fun coeffs(list: List<Biquad.Coeffs>, grid: FreqGrid, out: DoubleArray = DoubleArray(grid.size)): DoubleArray {
        out.fill(0.0)
        for (c in list) grid.addDb(c, out)
        return out
    }
}
