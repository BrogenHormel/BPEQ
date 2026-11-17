package app.bpeq.core.device

import app.bpeq.core.device.MaxProtocol.Registers

/**
 * A software Protocol Max. Speaks the same HID reports, keeps RAM registers, the DSP's active state and
 * flash separately (TEMP_WRITE applies RAM to the DSP, FLASH_EQ persists), and can inject faults.
 * Modelled on the owner's unit (firmware 1.4, slot byte 0x01) and devicePEQ's captures (firmware 0.7).
 */
class SimulatedDongle(
    val version: String = "1.4",
    initialSlot: Int = 0x01,
    initialPreamp: Int = 0,
) : HidChannel {

    /** One stored band: registers, the exact biquad bytes and the slot byte, as the device keeps them. */
    data class Stored(val registers: Registers, val biquad: ByteArray, val slot: Int)

    class Bank(val bands: Array<Stored>, var preamp: Int) {
        fun copy() = Bank(bands.copyOf(), preamp)
        fun registers() = bands.map { it.registers }
    }

    // ---- fault injection (public for tests and the in-app simulator settings) ---------------------
    /** Requests (counted from 1) whose reply is dropped. */
    val dropReplyAt = mutableSetOf<Int>()
    /** After this many sent reports the dongle "unplugs": every call throws. */
    var unplugAfterSends: Int? = null
    /** Store this band's gain off by one LSB when written (simulates a write that did not take). */
    var corruptWriteOfBand: Int? = null
    /** Band index -> how many of its next writes are silently ignored (the real dongle sometimes drops a report). */
    val dropWritesOfBand = mutableMapOf<Int, Int>()
    /**
     * Firmware 1.4 behaviour (owner's unit, 2026-11-17): band/preamp writes survive a power cycle even without
     * TEMP_WRITE or the flash command. False models the Protocol Micro, where only the flash command persists.
     */
    var writesPersist: Boolean = true
    /** Queue a volume-key report (ID 0x03) before every Nth reply. */
    var volumeKeyNoiseEvery: Int = 0
    /** Emulate the capture quirk: the first PEQ read after VERSION answers with a summary packet. */
    var summaryQuirk: Boolean = false

    // ---- observable state -----------------------------------------------------------------------
    var flash: Bank = factory(initialSlot, initialPreamp); private set
    var ram: Bank = flash.copy(); private set
    var active: Bank = flash.copy(); private set
    var flashWrites = 0; private set
    var sendCount = 0; private set
    var requestCount = 0; private set
    /** Every report received that was out of the device limits (should stay empty). */
    val outOfLimitWrites = mutableListOf<String>()
    val sent = mutableListOf<ByteArray>()
    var unplugged = false; private set

    private val replies = ArrayDeque<ByteArray>()
    private var summaryPending = false

    override val description: String get() = "Simulated Protocol Max (firmware $version)"

    /** Power cycle: RAM and the DSP reload from flash. */
    fun replug() {
        if (writesPersist) flash = ram.copy()
        ram = flash.copy(); active = flash.copy(); replies.clear(); unplugged = false; unplugAfterSends = null
    }

    fun unplug() { unplugged = true }

    private fun gone(): Nothing = throw DeviceIoException("USB device detached")

    override fun send(report: ByteArray) {
        if (unplugged) gone()
        unplugAfterSends?.let { if (sendCount >= it) { unplugged = true; gone() } }
        sendCount++
        sent += report.copyOf()
        require(report.size == MaxProtocol.REPORT_SIZE && (report[0].toInt() and 0xFF) == MaxProtocol.REPORT_ID) {
            "malformed output report"
        }
        val d = IntArray(63) { report[it + 1].toInt() and 0xFF }
        when {
            d[0] == MaxProtocol.READ -> handleRead(d)
            d[0] == MaxProtocol.WRITE -> handleWrite(d, report)
        }
    }

    private fun handleRead(d: IntArray) {
        requestCount++
        val reply: ByteArray = when (d[1]) {
            MaxProtocol.CMD_VERSION -> {
                summaryPending = summaryQuirk
                MaxProtocol.report(0x80, 0x0C, 0x00, *version.map { it.code }.toIntArray())
            }
            MaxProtocol.CMD_PEQ -> {
                if (summaryPending) {
                    summaryPending = false
                    // Summary packet as captured: version + Q regs, not a band reply.
                    MaxProtocol.report(0x80, 0x09, 0x00, *version.map { it.code }.toIntArray(), 0x00,
                        *IntArray(24) { if (it % 2 == 0) 0xB6 else 0 }, 0, 0, 2, 2, 0, 0, ram.bands[0].slot)
                } else {
                    val i = d[4]
                    if (i !in 0 until MaxLimits.BANDS) return
                    bandReply(i, ram.bands[i])
                }
            }
            MaxProtocol.CMD_GLOBAL_GAIN -> MaxProtocol.report(0x80, 0x03, 0x02, 0x00, ram.preamp and 0xFF)
            else -> return
        }
        if (requestCount in dropReplyAt) return
        if (volumeKeyNoiseEvery > 0 && requestCount % volumeKeyNoiseEvery == 0) {
            replies.addLast(ByteArray(2).also { it[0] = 0x03; it[1] = 0xE9.toByte() })
        }
        replies.addLast(reply)
    }

    private fun bandReply(i: Int, s: Stored): ByteArray {
        val p = IntArray(37)
        intArrayOf(0x80, 0x09, 0x00, 0x00, i, 0x00, 0x00).copyInto(p)
        s.biquad.forEachIndexed { k, b -> p[7 + k] = b.toInt() and 0xFF }
        val r = s.registers
        val g = r.gain256 and 0xFFFF
        intArrayOf(r.freq and 0xFF, r.freq shr 8, r.q256 and 0xFF, r.q256 shr 8, g and 0xFF, g shr 8, r.typeCode, 0, s.slot)
            .copyInto(p, 27)
        return MaxProtocol.report(*p)
    }

    private fun handleWrite(d: IntArray, raw: ByteArray) {
        when (d[1]) {
            MaxProtocol.CMD_PEQ -> {
                val i = d[4]
                if (i !in 0 until MaxLimits.BANDS) { outOfLimitWrites += "band index $i"; return }
                var g = d[31] or (d[32] shl 8); if (g > 32767) g -= 65536
                var regs = Registers(d[27] or (d[28] shl 8), d[29] or (d[30] shl 8), g, d[33])
                if (!MaxLimits.isValid(regs)) outOfLimitWrites += "band ${i + 1}: $regs"
                dropWritesOfBand[i]?.let { n -> if (n > 0) { dropWritesOfBand[i] = n - 1; return } }
                if (corruptWriteOfBand == i) regs = regs.copy(gain256 = regs.gain256 - 1)
                ram.bands[i] = Stored(regs, raw.copyOfRange(8, 28), d[35])
            }
            MaxProtocol.CMD_GLOBAL_GAIN -> {
                val p = d[4].toByte().toInt()
                if (p !in MaxLimits.PREAMP_MIN..MaxLimits.PREAMP_MAX) outOfLimitWrites += "preamp $p"
                ram.preamp = p
            }
            MaxProtocol.CMD_TEMP_WRITE -> active = ram.copy()
            MaxProtocol.CMD_FLASH_EQ -> if (d[2] == 0x01) { flash = ram.copy(); active = ram.copy(); flashWrites++ }
            else -> {}
        }
    }

    override fun receive(timeoutMs: Int): ByteArray? {
        if (unplugged) gone()
        return replies.removeFirstOrNull()
    }

    override fun close() {}

    companion object {
        fun factory(slot: Int, preamp: Int): Bank = Bank(Array(MaxLimits.BANDS) {
            val r = MaxLimits.flatRegisters(it)
            Stored(r, MaxProtocol.biquadBytes(r), slot)
        }, preamp)
    }
}
