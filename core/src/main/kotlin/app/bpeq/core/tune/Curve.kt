package app.bpeq.core.tune

import app.bpeq.core.io.ImportException
import kotlinx.serialization.Serializable
import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.pow

/** A frequency response (dB) sampled at increasing frequencies. */
@Serializable
class Curve(val name: String, val freqs: DoubleArray, val db: DoubleArray) {
    init {
        require(freqs.size == db.size && freqs.size >= 2) { "curve needs at least 2 points" }
    }

    /** Value at [f] by linear interpolation on log frequency; flat extension outside the measured range. */
    fun at(f: Double): Double {
        if (f <= freqs.first()) return db.first()
        if (f >= freqs.last()) return db.last()
        var lo = 0; var hi = freqs.size - 1
        while (hi - lo > 1) { val m = (lo + hi) ushr 1; if (freqs[m] <= f) lo = m else hi = m }
        val t = ln(f / freqs[lo]) / ln(freqs[hi] / freqs[lo])
        return db[lo] + t * (db[hi] - db[lo])
    }

    fun resample(grid: DoubleArray): DoubleArray = DoubleArray(grid.size) { at(grid[it]) }

    fun resampled(grid: DoubleArray, newName: String = name) = Curve(newName, grid.copyOf(), resample(grid))

    /** Shifted so the average between [lo] and [hi] Hz is 0 dB. */
    fun normalized(lo: Double = 300.0, hi: Double = 3000.0): Curve {
        val pts = freqs.indices.filter { freqs[it] in lo..hi }
        val mean = if (pts.isEmpty()) at(1000.0) else pts.sumOf { db[it] } / pts.size
        return Curve(name, freqs, DoubleArray(db.size) { db[it] - mean })
    }

    /** Fractional-octave smoothing (e.g. 1/12 octave), evaluated at the curve's own points. */
    fun smoothed(fraction: Double = 1.0 / 12): Curve {
        val k = 2.0.pow(fraction / 2)
        val out = DoubleArray(db.size) { i ->
            val lo = freqs[i] / k; val hi = freqs[i] * k
            var s = 0.0; var n = 0
            for (j in freqs.indices) if (freqs[j] in lo..hi) { s += db[j]; n++ }
            if (n == 0) db[i] else s / n
        }
        return Curve(name, freqs, out)
    }

    operator fun minus(o: Curve): Curve = Curve(name, freqs, DoubleArray(freqs.size) { db[it] - o.at(freqs[it]) })

    companion object {
        private val NUM = Regex("""[-+]?(?:\d+(?:[.]\d*)?|[.]\d+)(?:[eE][-+]?\d+)?""")

        /**
         * Parses a measurement or target: CSV/TXT exports from squig.link, Crinacle, AutoEQ, REW
         * (`freq,dB`, `freq<TAB>dB`, `freq dB phase`); header and comment lines are skipped.
         */
        fun parse(text: String, name: String): Curve {
            val pts = ArrayList<Pair<Double, Double>>()
            for (raw in text.lineSequence()) {
                val line = raw.trim()
                if (line.isEmpty() || line[0] in "*#;/\"'" || line[0].isLetter()) continue
                // A decimal comma only when the separator is clearly not a comma (e.g. "20,5;3,2" or tabs).
                val norm = if (';' in line || '\t' in line) line.replace(',', '.') else line
                val nums = NUM.findAll(norm).map { it.value.toDouble() }.toList()
                if (nums.size < 2) continue
                val f = nums[0]; val d = nums[1]
                if (f > 0 && f.isFinite() && d.isFinite() && abs(d) < 300) pts += f to d
            }
            val sorted = pts.sortedBy { it.first }.distinctBy { it.first }
            if (sorted.size < 10) throw ImportException("Not a frequency response: expected lines like \"20, 85.2\" (found ${sorted.size} points)")
            if (sorted.last().first < 5000 || sorted.first().first > 200) throw ImportException("The curve must cover at least 200 Hz to 5 kHz")
            return Curve(name, sorted.map { it.first }.toDoubleArray(), sorted.map { it.second }.toDoubleArray())
        }
    }
}

/** Target curves bundled with the app (AutoEQ project, MIT licence; see resources/targets/LICENSE-AutoEq.txt). */
object Targets {
    data class Info(val id: String, val name: String, val description: String)

    val ALL = listOf(
        Info("harman_ie_2019", "Harman in-ear 2019", "Research-based in-ear preference target, with bass shelf"),
        Info("harman_ie_2019_nobass", "Harman in-ear 2019 (no bass)", "Harman 2019 without the bass shelf"),
        Info("autoeq_ie", "AutoEq in-ear", "AutoEQ's in-ear target: Harman-like with a milder bass"),
        Info("diffuse_field_iso11904", "Diffuse field (ISO 11904)", "Neutral diffuse-field reference"),
    )

    fun load(id: String): Curve {
        val info = ALL.first { it.id == id }
        val text = Targets::class.java.getResourceAsStream("/targets/$id.csv")!!.bufferedReader().readText()
        return Curve.parse(text, info.name)
    }
}
