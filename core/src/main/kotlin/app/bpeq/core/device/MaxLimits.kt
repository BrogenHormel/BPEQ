package app.bpeq.core.device

import app.bpeq.core.device.MaxProtocol.Registers
import app.bpeq.core.model.Band
import app.bpeq.core.model.FilterType
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.roundToInt

/**
 * Protocol Max limits and the one place where user values become device registers.
 * Rule: clamp into range, and where rounding changes loudness, round toward quieter.
 */
object MaxLimits {
    const val BANDS = 10
    const val GAIN_MIN = -10.0
    const val GAIN_MAX = 10.0
    const val Q_MIN = 0.1
    const val Q_MAX = 10.0
    const val FREQ_MIN = 20
    const val FREQ_MAX = 20_000
    const val PREAMP_MIN = -30
    const val PREAMP_MAX = 0

    val Q256_MIN = ceil(Q_MIN * 256).toInt()   // 26
    val Q256_MAX = (Q_MAX * 256).toInt()       // 2560
    val GAIN256_MIN = (GAIN_MIN * 256).toInt() // -2560
    val GAIN256_MAX = (GAIN_MAX * 256).toInt() // 2560

    /** Spare slots: the device's factory flat (PK 0 dB, Q 0.75 at these frequencies; devicePEQ capture). */
    val FACTORY_FREQS = intArrayOf(31, 62, 125, 250, 500, 1000, 2000, 4000, 8000, 16000)
    const val FACTORY_Q256 = 192

    fun flatRegisters(index: Int) = Registers(FACTORY_FREQS[index], FACTORY_Q256, 0, MaxProtocol.TYPE_PK)

    /** Throws unless [r] is inside every device limit. Last line of defence before anything is sent. */
    fun requireValid(r: Registers) {
        require(r.freq in FREQ_MIN..FREQ_MAX) { "freq register ${r.freq} out of range" }
        require(r.q256 in Q256_MIN..Q256_MAX) { "Q register ${r.q256} out of range" }
        require(r.gain256 in GAIN256_MIN..GAIN256_MAX) { "gain register ${r.gain256} out of range" }
        require(r.typeCode in MaxProtocol.TYPE_LS..MaxProtocol.TYPE_HS) { "type ${r.typeCode} not supported" }
    }

    fun isValid(r: Registers) = runCatching { requireValid(r) }.isSuccess

    fun clampFreq(f: Double): Double = if (f.isNaN()) 1000.0 else f.coerceIn(FREQ_MIN.toDouble(), FREQ_MAX.toDouble())
    fun clampGain(g: Double): Double = if (g.isNaN()) 0.0 else g.coerceIn(GAIN_MIN, GAIN_MAX)
    fun clampQ(q: Double): Double = if (q.isNaN()) 1.0 else q.coerceIn(Q_MIN, Q_MAX)

    /**
     * User band -> registers. Frequency to nearest whole Hz, Q to nearest 1/256, gain to 1/256 dB rounded DOWN
     * (toward quieter); everything clamped into range first.
     */
    fun quantize(b: Band): Registers {
        val f = clampFreq(b.freqHz).roundToInt().coerceIn(FREQ_MIN, FREQ_MAX)
        val q = (clampQ(b.q) * 256).roundToInt().coerceIn(Q256_MIN, Q256_MAX)
        val g = floor(clampGain(b.gainDb) * 256 + 1e-9).toInt().coerceIn(GAIN256_MIN, GAIN256_MAX)
        return Registers(f, q, g, MaxProtocol.typeCode(b.type))
    }

    /** Registers -> the band they represent. */
    fun toBand(r: Registers, enabled: Boolean = true): Band =
        Band(r.type ?: FilterType.PEAK, r.freqHz, r.gainDb, r.q, enabled)

    /** Preamp to a whole dB, rounded DOWN (quieter), clamped to -30..0. */
    fun quantizePreamp(db: Double): Int {
        if (db.isNaN()) return PREAMP_MIN
        return floor(db + 1e-6).coerceIn(PREAMP_MIN.toDouble(), PREAMP_MAX.toDouble()).toInt()
    }
}
