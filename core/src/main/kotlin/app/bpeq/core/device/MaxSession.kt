package app.bpeq.core.device

import app.bpeq.core.device.MaxProtocol.BandReply
import app.bpeq.core.device.MaxProtocol.Registers
import java.io.IOException
import kotlin.math.abs

/** Everything read from the device in one pass. */
data class DeviceState(val version: String, val bands: List<BandReply>, val preampDb: Int) {
    /** The slot byte the device reports (echoed unchanged on writes). */
    val slot: Int get() = bands.first().slot
    val registers: List<Registers> get() = bands.map { it.registers }
}

/** What to write: exactly [MaxLimits.BANDS] registers plus a whole-dB preamp. */
data class WritePlan(val registers: List<Registers>, val preampDb: Int) {
    init {
        require(registers.size == MaxLimits.BANDS) { "a write plan needs ${MaxLimits.BANDS} bands" }
        registers.forEach { MaxLimits.requireValid(it); MaxProtocol.q30(it) }
        require(preampDb in MaxLimits.PREAMP_MIN..MaxLimits.PREAMP_MAX) { "preamp $preampDb out of range" }
    }
}

sealed interface WriteResult {
    /** Written, verified, committed to flash, and verified again. */
    data class Committed(val state: DeviceState) : WriteResult
    /** Written and verified but intentionally not committed (temporary / A/B). */
    data class Applied(val state: DeviceState) : WriteResult
    /** Read-back did not match; nothing was committed. */
    data class Mismatch(val differences: List<String>, val state: DeviceState) : WriteResult
}

/**
 * One open conversation with a Protocol Max over a [HidChannel]. Blocking; call from one worker thread.
 * Every request has a timeout and bounded retries; a missing reply becomes an [IOException].
 */
class MaxSession(
    private val ch: HidChannel,
    private val log: DeviceLog,
    private val sleep: (Long) -> Unit = { Thread.sleep(it) },
    private val replyTimeoutMs: Int = 250,
    private val attempts: Int = 3,
) {
    private fun send(r: ByteArray) {
        log.tx(r)
        ch.send(r)
    }

    /** Drops queued input (volume-key reports, stale replies). */
    fun drain() {
        var n = 0
        while (n < 32) {
            val r = ch.receive(5) ?: break
            log.rx(r); n++
        }
        if (n > 0) log.info("drained $n stale input report(s)")
    }

    private fun request(report: ByteArray, what: String, matches: (ByteArray) -> Boolean): ByteArray {
        for (attempt in 1..attempts) {
            send(report)
            val deadline = System.nanoTime() + replyTimeoutMs * 1_000_000L
            while (true) {
                val left = ((deadline - System.nanoTime()) / 1_000_000L).toInt()
                if (left <= 0) break
                val r = ch.receive(left) ?: break
                val norm = normalize(r)
                log.rx(norm)
                if (matches(norm)) return norm
                log.info("ignored unrelated report while waiting for $what")
            }
            log.warn("no reply to $what (attempt $attempt/$attempts)")
            if (attempt < attempts) drain()
        }
        throw DeviceIoException("The dongle did not answer $what")
    }

    /** Some stacks strip the report ID; put it back so parsing is uniform. */
    private fun normalize(r: ByteArray): ByteArray {
        if (r.isEmpty() || (r[0].toInt() and 0xFF) != MaxProtocol.READ) return r
        val fixed = ByteArray(MaxProtocol.REPORT_SIZE)
        fixed[0] = MaxProtocol.REPORT_ID.toByte()
        r.copyInto(fixed, 1, 0, minOf(r.size, MaxProtocol.REPORT_SIZE - 1))
        return fixed
    }

    fun readVersion(): String =
        MaxProtocol.parseVersion(request(MaxProtocol.versionRequest(), "VERSION") { MaxProtocol.isReply(it, MaxProtocol.CMD_VERSION) })

    fun readBand(i: Int): BandReply =
        MaxProtocol.parseBand(request(MaxProtocol.bandRequest(i), "band ${i + 1}") { MaxProtocol.isBandReply(it, i) }, i)

    fun readPreamp(): Int =
        MaxProtocol.parsePreamp(request(MaxProtocol.preampRequest(), "preamp") { MaxProtocol.isReply(it, MaxProtocol.CMD_GLOBAL_GAIN) })

    fun readState(): DeviceState {
        drain()
        val v = readVersion()
        val bands = (0 until MaxLimits.BANDS).map { readBand(it) }
        val p = readPreamp()
        return DeviceState(v, bands, p)
    }

    // ---- write path ------------------------------------------------------------------------------

    /**
     * Writes bands + preamp, reads everything back and compares. Only when every register, the slot byte,
     * the preamp and the biquads match does it [commit] (flash) and verify again. With commit = false it sends
     * TEMP_WRITE instead (applies from RAM; used for A/B once proven RAM-only).
     */
    fun write(plan: WritePlan, slot: Int, commit: Boolean): WriteResult {
        drain()
        log.info("write: ${plan.registers.size} bands, preamp ${plan.preampDb} dB, slot 0x%02x, commit=$commit".format(slot))
        plan.registers.forEachIndexed { i, r ->
            send(MaxProtocol.bandWrite(i, r, slot)); sleep(20)
        }
        sleep(100)
        send(MaxProtocol.preampWrite(plan.preampDb)); sleep(50)

        val readBack = readAfterWrite()
        val diffs = compare(plan, slot, readBack)
        if (diffs.isNotEmpty()) {
            diffs.forEach { log.error("verify: $it") }
            log.error("read-back mismatch: NOT committing")
            return WriteResult.Mismatch(diffs, readBack)
        }
        log.info("verify: all ${MaxLimits.BANDS} bands, slot and preamp match")

        if (!commit) {
            send(MaxProtocol.tempWrite()); sleep(50)
            return WriteResult.Applied(readBack)
        }
        send(MaxProtocol.commitA()); sleep(20)
        send(MaxProtocol.commitB()); sleep(20)
        send(MaxProtocol.tempWrite()); sleep(50)
        send(MaxProtocol.flashCommit()); sleep(250)
        val after = readAfterWrite()
        val diffs2 = compare(plan, slot, after)
        if (diffs2.isNotEmpty()) {
            diffs2.forEach { log.error("post-commit verify: $it") }
            return WriteResult.Mismatch(diffs2, after)
        }
        log.info("committed to flash and verified")
        return WriteResult.Committed(after)
    }

    private fun readAfterWrite(): DeviceState {
        drain()
        val bands = (0 until MaxLimits.BANDS).map { readBand(it) }
        return DeviceState("", bands, readPreamp())
    }

    companion object {
        /** Biquad bytes may differ by rounding if the firmware recomputes; allow a tiny tolerance (in Q30 LSB). */
        const val BIQUAD_TOLERANCE_LSB = 64

        fun compare(plan: WritePlan, slot: Int, s: DeviceState): List<String> {
            val out = ArrayList<String>()
            plan.registers.forEachIndexed { i, want ->
                val got = s.bands[i]
                if (got.registers != want) out += "band ${i + 1}: sent $want, device has ${got.registers}"
                if (got.slot != slot) out += "band ${i + 1}: slot byte 0x%02x, expected 0x%02x".format(got.slot, slot)
                val lsb = biquadDistance(MaxProtocol.biquadBytes(want), got.biquad)
                if (lsb > BIQUAD_TOLERANCE_LSB) out += "band ${i + 1}: stored coefficients differ by $lsb LSB"
            }
            if (s.preampDb != plan.preampDb) out += "preamp: sent ${plan.preampDb} dB, device has ${s.preampDb} dB"
            return out
        }

        fun biquadDistance(a: ByteArray, b: ByteArray): Long {
            fun i32(x: ByteArray, o: Int) = (x[o].toInt() and 0xFF) or ((x[o + 1].toInt() and 0xFF) shl 8) or
                ((x[o + 2].toInt() and 0xFF) shl 16) or (x[o + 3].toInt() shl 24)
            return (0 until 5).maxOf { abs(i32(a, it * 4).toLong() - i32(b, it * 4).toLong()) }
        }
    }
}
