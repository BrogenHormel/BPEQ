package app.bpeq.core.device

/**
 * Read-only device self-test. Never sends a write report: only VERSION, band and preamp reads.
 * Verifies our decoder/encoder against what the device actually stores.
 */
object SelfTest {
    enum class Status { PASS, WARN, FAIL }
    data class Check(val status: Status, val text: String)
    data class Report(val checks: List<Check>, val state: DeviceState?) {
        val status: Status get() = checks.maxOfOrNull { it.status } ?: Status.FAIL
    }

    fun run(session: MaxSession, log: DeviceLog): Report {
        val checks = ArrayList<Check>()
        fun add(s: Status, t: String) {
            checks += Check(s, t)
            when (s) { Status.PASS -> log.info("self-test PASS: $t"); Status.WARN -> log.warn("self-test WARN: $t"); Status.FAIL -> log.error("self-test FAIL: $t") }
        }
        log.info("=== self-test start (read-only) ===")
        val state = try {
            session.readState()
        } catch (e: Exception) {
            add(Status.FAIL, "read failed: ${e.message}")
            log.error("self-test read failed", e)
            return Report(checks, null)
        }
        add(Status.PASS, "firmware version \"${state.version}\"")
        add(Status.PASS, "read all ${state.bands.size} bands and preamp (${state.preampDb} dB)")

        val slots = state.bands.map { it.slot }.toSet()
        if (slots.size == 1) add(Status.PASS, "slot byte 0x%02x on every band".format(state.slot))
        else add(Status.WARN, "slot bytes differ between bands: ${slots.joinToString { "0x%02x".format(it) }}")

        if (state.preampDb in MaxLimits.PREAMP_MIN..MaxLimits.PREAMP_MAX) add(Status.PASS, "preamp ${state.preampDb} dB within -30..0")
        else add(Status.WARN, "preamp ${state.preampDb} dB is outside -30..0 (written by another tool?)")

        var exact = 0
        state.bands.forEach { b ->
            val n = b.index + 1
            val r = b.registers
            log.info("band $n: $r  slot=0x%02x  biquad=${DeviceLog.hex(b.biquad, false)}".format(b.slot))
            if (b.disabled) { add(Status.WARN, "band $n is disabled/erased (freq ${r.freq}, Q reg ${r.q256})"); return@forEach }
            if (r.type == null) { add(Status.FAIL, "band $n has unknown type code ${r.typeCode}"); return@forEach }
            if (!MaxLimits.isValid(r)) add(Status.WARN, "band $n ($r) is outside our send limits")
            val ours = runCatching { MaxProtocol.biquadBytes(r) }.getOrNull()
            if (ours == null) { add(Status.WARN, "band $n: our encoder would refuse these values (Q30 overflow)"); return@forEach }
            val d = MaxSession.biquadDistance(ours, b.biquad)
            if (d == 0L) exact++
            else add(if (d <= MaxSession.BIQUAD_TOLERANCE_LSB) Status.WARN else Status.FAIL,
                "band $n: stored coefficients differ from ours by $d LSB (ours ${DeviceLog.hex(ours, false)})")
            // Whole packet round-trip: our write packet must reproduce the stored fields d[7..35].
            if (MaxLimits.isValid(r)) {
                val packet = MaxProtocol.bandWrite(b.index, r, b.slot)
                val mismatch = (8..36).filter { packet[it] != b.raw[it] }
                if (mismatch.isNotEmpty() && d == 0L)
                    add(Status.FAIL, "band $n: re-encoded packet differs at byte(s) ${mismatch.joinToString()}")
            }
        }
        if (exact > 0) add(Status.PASS, "$exact band(s): our biquad encoding matches the device bit-for-bit")

        // Second read: values must be stable.
        try {
            val again = session.readState()
            if (again.registers == state.registers && again.preampDb == state.preampDb) add(Status.PASS, "second read identical")
            else add(Status.FAIL, "second read differs from the first")
        } catch (e: Exception) {
            add(Status.FAIL, "second read failed: ${e.message}")
        }
        log.info("=== self-test done: ${checks.maxOf { it.status }} ===")
        return Report(checks, state)
    }
}
