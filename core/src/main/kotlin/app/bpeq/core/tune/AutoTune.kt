package app.bpeq.core.tune

import app.bpeq.core.device.MaxLimits
import app.bpeq.core.device.MaxProtocol
import app.bpeq.core.dsp.Biquad
import app.bpeq.core.dsp.FreqGrid
import app.bpeq.core.model.Band
import app.bpeq.core.model.Eq
import app.bpeq.core.model.FilterType
import app.bpeq.core.model.PreampMode
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/** Per-type parameter bounds for fitting. */
data class Bounds(
    val fMin: Double = 20.0,
    val fMax: Double = 16_000.0,
    val gainMin: Double = MaxLimits.GAIN_MIN,
    val gainMax: Double = MaxLimits.GAIN_MAX,
    val qMin: Double = 0.3,
    val qMax: Double = 6.0,
    val shelfQMin: Double = 0.4,
    val shelfQMax: Double = 1.0,
    /** Keep shelves doing shelf jobs (bass/treble), not wandering into the mids. */
    val lowShelfMaxHz: Double = 500.0,
    val highShelfMinHz: Double = 1000.0,
)

/**
 * Bounded Levenberg-Marquardt on the weighted dB error. Parameters per band: ln f, gain, ln Q (type fixed),
 * plus an optional free level offset. Every accepted step keeps every band inside [Bounds] and storable by the
 * Protocol Max (no coefficient overflow after quantisation), so the result can always be sent.
 */
class Fitter(
    private val grid: FreqGrid,
    private val desired: DoubleArray,
    private val weights: DoubleArray,
    private val bounds: Bounds,
    private val freeOffset: Boolean = true,
    private val isCancelled: () -> Boolean = { false },
    /** Used when [freeOffset] is false: the fixed level of the model. */
    private val fixedOffset: Double = 0.0,
) {
    private val n = grid.size

    fun bandDb(b: Band, out: DoubleArray = DoubleArray(n)): DoubleArray {
        out.fill(0.0); grid.addDb(Biquad.design(b), out); return out
    }

    fun clamp(b: Band): Band {
        val shelf = b.type.isShelf
        val (fLo, fHi) = when (b.type) {
            FilterType.LOW_SHELF -> bounds.fMin to min(bounds.lowShelfMaxHz, bounds.fMax)
            FilterType.HIGH_SHELF -> max(bounds.highShelfMinHz, bounds.fMin) to bounds.fMax
            FilterType.PEAK -> bounds.fMin to bounds.fMax
        }
        return b.copy(
            freqHz = b.freqHz.coerceIn(fLo, fHi),
            gainDb = b.gainDb.coerceIn(bounds.gainMin, bounds.gainMax),
            q = b.q.coerceIn(if (shelf) bounds.shelfQMin else bounds.qMin, if (shelf) bounds.shelfQMax else bounds.qMax),
        )
    }

    fun storable(b: Band) = MaxProtocol.storable(MaxLimits.quantize(b))

    /** Weighted sum of squared errors of model = sum(bands) + offset. */
    fun cost(resp: Array<DoubleArray>, offset: Double): Double {
        var s = 0.0
        for (i in 0 until n) {
            var m = offset
            for (r in resp) m += r[i]
            val e = desired[i] - m
            s += weights[i] * e * e
        }
        return s
    }

    /** Best offset for fixed bands (weighted mean of the residual). */
    fun bestOffset(resp: Array<DoubleArray>): Double {
        if (!freeOffset) return fixedOffset
        var s = 0.0; var w = 0.0
        for (i in 0 until n) { var m = 0.0; for (r in resp) m += r[i]; s += weights[i] * (desired[i] - m); w += weights[i] }
        return if (w > 0) s / w else 0.0
    }

    fun rms(bands: List<Band>, offset: Double): Double {
        val resp = Array(bands.size) { bandDb(bands[it]) }
        val wsum = weights.sum().coerceAtLeast(1e-12)
        return sqrt(cost(resp, offset) / wsum)
    }

    data class Fit(val bands: List<Band>, val offset: Double, val cost: Double)

    fun fit(initial: List<Band>, iterations: Int = 40, fixed: Set<Int> = emptySet()): Fit {
        var bands = initial.map(::clamp).toMutableList()
        var resp = Array(bands.size) { bandDb(bands[it]) }
        var offset = bestOffset(resp)
        var cost = cost(resp, offset)
        var lambda = 1e-2
        val free = bands.indices.filter { it !in fixed }
        val np = free.size * 3 + if (freeOffset) 1 else 0
        if (np == 0) return Fit(bands, offset, cost)
        val sw = DoubleArray(n) { sqrt(weights[it]) }
        val jac = Array(np) { DoubleArray(n) }
        val r = DoubleArray(n)
        val tmp = DoubleArray(n)

        repeat(iterations) {
            if (isCancelled()) return Fit(bands, offset, cost)
            // residual
            for (i in 0 until n) { var m = offset; for (x in resp) m += x[i]; r[i] = sw[i] * (desired[i] - m) }
            // jacobian of the model (forward differences on one band at a time)
            free.forEachIndexed { k, bi ->
                val b = bands[bi]
                val variants = listOf(
                    b.copy(freqHz = b.freqHz * exp(1e-3)) to 1e-3,
                    b.copy(gainDb = b.gainDb + 1e-3) to 1e-3,
                    b.copy(q = b.q * exp(1e-3)) to 1e-3,
                )
                variants.forEachIndexed { p, (v, h) ->
                    bandDb(v, tmp)
                    val col = jac[k * 3 + p]
                    for (i in 0 until n) col[i] = sw[i] * (tmp[i] - resp[bi][i]) / h
                }
            }
            if (freeOffset) for (i in 0 until n) jac[np - 1][i] = sw[i]

            val a = Array(np) { DoubleArray(np) }
            val g = DoubleArray(np)
            for (p in 0 until np) {
                for (q in p until np) { var s = 0.0; for (i in 0 until n) s += jac[p][i] * jac[q][i]; a[p][q] = s; a[q][p] = s }
                var s = 0.0; for (i in 0 until n) s += jac[p][i] * r[i]; g[p] = s
            }
            var accepted = false
            var tries = 0
            while (!accepted && tries < 6) {
                tries++
                val m = Array(np) { p -> DoubleArray(np) { q -> a[p][q] + if (p == q) lambda * (a[p][p] + 1e-9) else 0.0 } }
                val d = solve(m, g.copyOf()) ?: run { lambda *= 10; null } ?: continue
                val cand = bands.toMutableList()
                free.forEachIndexed { k, bi ->
                    val b = bands[bi]
                    val nb = clamp(b.copy(
                        freqHz = b.freqHz * exp(d[k * 3].coerceIn(-0.7, 0.7)),
                        gainDb = b.gainDb + d[k * 3 + 1].coerceIn(-6.0, 6.0),
                        q = b.q * exp(d[k * 3 + 2].coerceIn(-0.7, 0.7)),
                    ))
                    cand[bi] = if (storable(nb)) nb else b
                }
                val cresp = Array(cand.size) { if (cand[it] === bands[it]) resp[it] else bandDb(cand[it]) }
                val coff = if (freeOffset) offset + d[np - 1] else fixedOffset
                val ccost = cost(cresp, coff)
                if (ccost < cost) {
                    bands = cand; resp = cresp; offset = coff; cost = ccost
                    lambda = max(lambda / 3, 1e-7); accepted = true
                } else lambda = min(lambda * 4, 1e7)
            }
            if (!accepted) return Fit(bands, offset, cost)
        }
        return Fit(bands, offset, cost)
    }

    companion object {
        /** Gaussian elimination with partial pivoting; null if singular. */
        fun solve(a: Array<DoubleArray>, b: DoubleArray): DoubleArray? {
            val n = b.size
            for (c in 0 until n) {
                var piv = c
                for (r in c + 1 until n) if (abs(a[r][c]) > abs(a[piv][c])) piv = r
                if (abs(a[piv][c]) < 1e-14) return null
                if (piv != c) { val t = a[c]; a[c] = a[piv]; a[piv] = t; val tb = b[c]; b[c] = b[piv]; b[piv] = tb }
                for (r in c + 1 until n) {
                    val f = a[r][c] / a[c][c]
                    if (f == 0.0) continue
                    for (k in c until n) a[r][k] -= f * a[c][k]
                    b[r] -= f * b[c]
                }
            }
            val x = DoubleArray(n)
            for (r in n - 1 downTo 0) {
                var s = b[r]
                for (k in r + 1 until n) s -= a[r][k] * x[k]
                x[r] = s / a[r][r]
            }
            return x
        }
    }
}

data class TuneSettings(
    val maxFilters: Int = MaxLimits.BANDS,
    /** Largest boost a single filter may apply. */
    val maxBoostDb: Double = 6.0,
    val maxCutDb: Double = 10.0,
    /** Fit up to this frequency; above [softAboveHz] errors count less. */
    val fitMaxHz: Double = 16_000.0,
    val softAboveHz: Double = 10_000.0,
    val softWeight: Double = 0.25,
    val useShelves: Boolean = true,
)

data class TuneResult(
    val eq: Eq,
    /** RMS error (dB) of measurement vs target, before and after EQ, weighted, 20 Hz-fitMax. */
    val rmsBefore: Double,
    val rmsAfter: Double,
    val millis: Long,
)

/** Fits up to 10 Protocol Max filters so that measurement + EQ matches the target. */
object AutoTune {
    val GRID: FreqGrid by lazy { FreqGrid.log(160, 20.0, 20_000.0) }

    fun weights(grid: FreqGrid, s: TuneSettings) = DoubleArray(grid.size) { i ->
        val f = grid.freqs[i]
        when {
            f > s.fitMaxHz -> 0.0
            f > s.softAboveHz -> s.softWeight
            else -> 1.0
        }
    }

    fun run(measurement: Curve, target: Curve, s: TuneSettings = TuneSettings(), isCancelled: () -> Boolean = { false }): TuneResult {
        val t0 = System.currentTimeMillis()
        val grid = GRID
        val desired = DoubleArray(grid.size) { target.at(grid.freqs[it]) - measurement.at(grid.freqs[it]) }
        val w = weights(grid, s)
        val bounds = Bounds(fMax = min(s.fitMaxHz, 16_000.0), gainMin = -s.maxCutDb, gainMax = s.maxBoostDb)
        // Anchor the level at the midrange (300 Hz-3 kHz) so the EQ stays near 0 dB there: a free level would let
        // the fit lift every filter together and waste headroom (a bigger preamp cut for the same sound).
        val mid = grid.freqs.indices.filter { grid.freqs[it] in 300.0..3000.0 }
        val level = mid.sumOf { desired[it] } / mid.size
        val fitter = Fitter(grid, desired, w, bounds, freeOffset = false, isCancelled = isCancelled, fixedOffset = level)
        val rmsBefore = Fitter(grid, desired, w, bounds).let { it.rms(emptyList(), it.bestOffset(emptyArray())) }

        var bands = ArrayList<Band>()
        if (s.useShelves && s.maxFilters >= 3) {
            bands += Band(FilterType.LOW_SHELF, 105.0, 0.0, 0.7)
            bands += Band(FilterType.HIGH_SHELF, 10_000.0, 0.0, 0.7)
        }
        var fit = fitter.fit(bands, 30)
        bands = ArrayList(fit.bands)
        val resp = DoubleArray(grid.size)
        while (bands.size < s.maxFilters && !isCancelled()) {
            // residual, lightly smoothed, to find the worst remaining deviation
            val model = DoubleArray(grid.size) { fit.offset }
            bands.forEach { fitter.bandDb(it, resp); for (i in model.indices) model[i] += resp[i] }
            val res = DoubleArray(grid.size) { desired[it] - model[it] }
            val sm = DoubleArray(grid.size) { i -> ((res.getOrElse(i - 1) { res[i] }) + 2 * res[i] + (res.getOrElse(i + 1) { res[i] })) / 4 }
            var best = -1; var bestScore = 0.0
            for (i in sm.indices) {
                val f = grid.freqs[i]
                if (f < 20.0 || f > bounds.fMax) continue
                val score = w[i] * abs(sm[i])
                if (score > bestScore) { bestScore = score; best = i }
            }
            if (best < 0 || bestScore < 0.25) break
            val cand = Band(FilterType.PEAK, grid.freqs[best], sm[best].coerceIn(bounds.gainMin, bounds.gainMax), 1.4)
            val trial = fitter.fit(bands + cand, 25)
            if (trial.cost > fit.cost * 0.995) break // no meaningful gain from another filter
            fit = trial; bands = ArrayList(fit.bands)
        }
        fit = fitter.fit(bands, 60)
        // Drop filters that ended up doing (almost) nothing, then round to what the editor shows.
        val final = fit.bands.filter { abs(it.gainDb) >= 0.15 }.map {
            it.copy(freqHz = Math.round(it.freqHz).toDouble(), gainDb = Math.round(it.gainDb * 10) / 10.0, q = Math.round(it.q * 100) / 100.0)
        }.filter { fitter.storable(it) }.sortedBy { it.freqHz }
        val rmsAfter = Fitter(grid, desired, w, bounds).let { f -> f.rms(final, f.bestOffset(Array(final.size) { f.bandDb(final[it]) })) }
        return TuneResult(Eq(final, PreampMode.AUTO), rmsBefore, rmsAfter, System.currentTimeMillis() - t0)
    }

    /** Predicted result: measurement + EQ (ideal), on [grid]. */
    fun predicted(measurement: Curve, eq: Eq, grid: FreqGrid): DoubleArray {
        val out = app.bpeq.core.dsp.Response.ideal(eq.bands, grid)
        for (i in out.indices) out[i] += measurement.at(grid.freqs[i])
        return out
    }
}
