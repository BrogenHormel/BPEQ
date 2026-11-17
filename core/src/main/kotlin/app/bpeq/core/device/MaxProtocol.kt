package app.bpeq.core.device

import app.bpeq.core.dsp.Biquad
import app.bpeq.core.model.FilterType
import kotlin.math.floor

/**
 * WalkPlay HID PEQ protocol as spoken by the CrinEar Protocol Max (3302:43CC, SchemeNo16).
 *
 * Adapted from Contour (https://github.com/Iwakura9/contour, Apache-2.0, `WalkPlay.kt`), itself a port of
 * devicePEQ's walkplayHidHandler.js (https://github.com/jeromeof/devicePEQ, 0BSD, commit 0617f382).
 * Golden vectors in the tests come from devicePEQ's Protocol Max USB captures (firmware 0.7).
 *
 * Every buffer here is a full 64-byte report with the report ID at [0]. Payload offsets in comments are
 * WebHID-style `d[n] == buf[n + 1]`.
 *
 *  - read: VERSION `80 0C 00`, bulk `80 09 00`, band `80 09 00 00 i 00`, preamp `80 03 00`
 *  - band packet: d[4] index, d[7..26] biquad (5 x int32 LE, Q30: b0 b1 b2 -a1 -a2, RBJ @ 96 kHz),
 *    d[27..28] freq u16, d[29..30] Q*256 u16, d[31..32] gain*256 s16, d[33] type (1 LS, 2 PK, 3 HS), d[35] slot
 *  - write band `01 09 18 00 i 00 00 ...`, preamp `01 03 02 00 g`, commit `01 05`, `01 17`,
 *    TEMP_WRITE `01 0A 04 00 00 FF FF`, FLASH `01 01 01`.
 */
object MaxProtocol {
    const val VENDOR_ID = 0x3302
    const val PRODUCT_ID = 0x43CC
    const val REPORT_ID = 0x4B
    const val REPORT_SIZE = 64
    const val READ = 0x80
    const val WRITE = 0x01
    const val END = 0x00

    const val CMD_GLOBAL_GAIN = 0x03
    const val CMD_PEQ = 0x09
    const val CMD_TEMP_WRITE = 0x0A
    const val CMD_VERSION = 0x0C
    const val CMD_FLASH_EQ = 0x01
    const val CMD_COMMIT_A = 0x05
    const val CMD_COMMIT_B = 0x17
    const val BAND_LEN = 0x18

    const val TYPE_LS = 1
    const val TYPE_PK = 2
    const val TYPE_HS = 3

    const val Q30 = 1073741824.0

    fun typeCode(t: FilterType) = when (t) {
        FilterType.LOW_SHELF -> TYPE_LS
        FilterType.PEAK -> TYPE_PK
        FilterType.HIGH_SHELF -> TYPE_HS
    }

    fun filterType(code: Int): FilterType? = when (code) {
        TYPE_LS -> FilterType.LOW_SHELF
        TYPE_PK -> FilterType.PEAK
        TYPE_HS -> FilterType.HIGH_SHELF
        else -> null
    }

    /** JS Math.round. */
    fun jsRound(x: Double): Double = floor(x + 0.5)

    // ---- report building -----------------------------------------------------------------------

    fun report(vararg payload: Int): ByteArray {
        require(payload.size <= REPORT_SIZE - 1)
        val buf = ByteArray(REPORT_SIZE)
        buf[0] = REPORT_ID.toByte()
        payload.forEachIndexed { i, v -> buf[i + 1] = v.toByte() }
        return buf
    }

    fun versionRequest() = report(READ, CMD_VERSION, END)
    fun bulkRequest() = report(READ, CMD_PEQ, END)
    fun bandRequest(index: Int) = report(READ, CMD_PEQ, 0x00, 0x00, index, END)
    fun preampRequest() = report(READ, CMD_GLOBAL_GAIN, END)

    fun preampWrite(db: Int): ByteArray {
        require(db in MaxLimits.PREAMP_MIN..MaxLimits.PREAMP_MAX) { "preamp $db dB outside device limits" }
        return report(WRITE, CMD_GLOBAL_GAIN, 0x02, 0x00, db and 0xFF)
    }

    fun commitA() = report(WRITE, CMD_COMMIT_A, END)
    fun commitB() = report(WRITE, CMD_COMMIT_B, END)
    fun tempWrite() = report(WRITE, CMD_TEMP_WRITE, 0x04, 0x00, 0x00, 0xFF, 0xFF, END)
    fun flashCommit() = report(WRITE, CMD_FLASH_EQ, 0x01, END)

    // ---- registers -----------------------------------------------------------------------------

    /** One band exactly as the device stores it. [gain256] is signed. */
    @kotlinx.serialization.Serializable
    data class Registers(val freq: Int, val q256: Int, val gain256: Int, val typeCode: Int) {
        val freqHz: Double get() = freq.toDouble()
        val q: Double get() = q256 / 256.0
        val gainDb: Double get() = gain256 / 256.0
        val type: FilterType? get() = filterType(typeCode)
        val isZeroGainPeak: Boolean get() = gain256 == 0 && typeCode == TYPE_PK
        override fun toString() = "${type?.short ?: "type$typeCode"} ${freq}Hz ${"%.2f".format(gainDb)}dB Q${"%.3f".format(q)}"
    }

    /** Q30 overflow: the coefficients don't fit the device's signed 2.30 format (|c| < 2). */
    class Q30Overflow(msg: String) : IllegalArgumentException(msg)

    private val COEF_NAMES = arrayOf("b0", "b1", "b2", "-a1", "-a2")

    /** The five integers devicePEQ's quantizer produces, or null with the reason if one overflows. */
    fun q30(regs: Registers): LongArray {
        val type = filterType(regs.typeCode) ?: throw IllegalArgumentException("unknown type ${regs.typeCode}")
        val r = Biquad.raw(type, regs.freqHz, regs.gainDb, regs.q)
        val a0 = r[3]
        val scaled = doubleArrayOf(r[0] / a0 * Q30, r[1] / a0 * Q30, r[2] / a0 * Q30, r[4] / a0 * Q30, r[5] / a0 * Q30)
        if (scaled.any { !it.isFinite() }) throw Q30Overflow("non-finite coefficients")
        val out = LongArray(5) { if (it < 3) jsRound(scaled[it]).toLong() else -jsRound(scaled[it]).toLong() }
        val bad = (0 until 5).firstOrNull { out[it] < Int.MIN_VALUE || out[it] > Int.MAX_VALUE }
        if (bad != null) {
            throw Q30Overflow("coefficient ${COEF_NAMES[bad]} = ${"%.3f".format(out[bad] / Q30)} does not fit the device's ±2.0 range")
        }
        return out
    }

    /** True if [regs] can be stored without coefficient overflow. */
    fun storable(regs: Registers): Boolean = runCatching { q30(regs) }.isSuccess

    fun biquadBytes(regs: Registers): ByteArray {
        val v = q30(regs)
        val out = ByteArray(20)
        for (i in 0 until 5) {
            val n = v[i].toInt()
            out[i * 4] = n.toByte(); out[i * 4 + 1] = (n shr 8).toByte()
            out[i * 4 + 2] = (n shr 16).toByte(); out[i * 4 + 3] = (n shr 24).toByte()
        }
        return out
    }

    /** Normalised coefficients the device runs for these registers (from the Q30 integers). */
    fun deviceCoeffs(regs: Registers): Biquad.Coeffs {
        val v = q30(regs)
        return Biquad.Coeffs(v[0] / Q30, v[1] / Q30, v[2] / Q30, -v[3] / Q30, -v[4] / Q30)
    }

    /** Decodes the 20 stored biquad bytes into coefficients. */
    fun coeffsFromBytes(b: ByteArray): Biquad.Coeffs {
        require(b.size == 20)
        fun i32(o: Int) = (b[o].toInt() and 0xFF) or ((b[o + 1].toInt() and 0xFF) shl 8) or
            ((b[o + 2].toInt() and 0xFF) shl 16) or (b[o + 3].toInt() shl 24)
        return Biquad.Coeffs(i32(0) / Q30, i32(4) / Q30, i32(8) / Q30, -i32(12) / Q30, -i32(16) / Q30)
    }

    /** Band write `01 09 18 00 i 00 00` + biquad + freq + q + gain + type + 00 + slot + 00. */
    fun bandWrite(index: Int, regs: Registers, slot: Int): ByteArray {
        require(index in 0 until MaxLimits.BANDS) { "band index $index" }
        MaxLimits.requireValid(regs)
        val biquad = biquadBytes(regs)
        val p = IntArray(37)
        intArrayOf(WRITE, CMD_PEQ, BAND_LEN, 0x00, index, 0x00, 0x00).copyInto(p)
        biquad.forEachIndexed { i, b -> p[7 + i] = b.toInt() and 0xFF }
        val g = regs.gain256 and 0xFFFF
        intArrayOf(
            regs.freq and 0xFF, regs.freq shr 8, regs.q256 and 0xFF, regs.q256 shr 8, g and 0xFF, g shr 8,
            regs.typeCode, 0x00, slot and 0xFF, END,
        ).copyInto(p, 27)
        return report(*p)
    }

    // ---- reply parsing -------------------------------------------------------------------------

    private fun u8(b: ByteArray, i: Int) = b[i].toInt() and 0xFF
    private fun u16(b: ByteArray, i: Int) = u8(b, i) or (u8(b, i + 1) shl 8)

    /** A full 64-byte input report 0x4B in read direction answering [cmd]. */
    fun isReply(b: ByteArray, cmd: Int) =
        b.size >= REPORT_SIZE && u8(b, 0) == REPORT_ID && u8(b, 1) == READ && u8(b, 2) == cmd

    fun isBandReply(b: ByteArray, index: Int) =
        isReply(b, CMD_PEQ) && u8(b, 3) == 0 && u8(b, 4) == 0 && u8(b, 5) == index

    fun parseVersion(b: ByteArray): String {
        require(isReply(b, CMD_VERSION)) { "not a VERSION reply" }
        val s = String(b.copyOfRange(4, 7), Charsets.US_ASCII).trimEnd('\u0000')
        require(s.isNotEmpty() && s.all { it.code in 0x20..0x7E }) { "invalid firmware version bytes" }
        return s
    }

    fun parsePreamp(b: ByteArray): Int {
        require(isReply(b, CMD_GLOBAL_GAIN)) { "not a preamp reply" }
        require(u8(b, 3) == 2 && u8(b, 4) == 0) { "unexpected preamp reply header" }
        return b[5].toInt() // int8
    }

    /** A decoded band reply. */
    data class BandReply(val index: Int, val registers: Registers, val biquad: ByteArray, val slot: Int, val raw: ByteArray) {
        /** devicePEQ: a slot is disabled/erased if freq is 0 or 0xFFFF, or Q is 0. */
        val disabled: Boolean get() = registers.freq == 0 || registers.freq == 0xFFFF || registers.q256 == 0
    }

    fun parseBand(b: ByteArray, index: Int): BandReply {
        require(isBandReply(b, index)) { "not a reply for band $index" }
        var g = u16(b, 32)
        if (g > 32767) g -= 65536
        val regs = Registers(u16(b, 28), u16(b, 30), g, u8(b, 34))
        return BandReply(index, regs, b.copyOfRange(8, 28), u8(b, 36), b.copyOf())
    }
}
