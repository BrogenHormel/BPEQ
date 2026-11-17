package app.bpeq.core.device

import app.bpeq.core.model.Band
import app.bpeq.core.model.FilterType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

class SessionSimulatorTest {
    private val log = MemoryLog()
    private fun session(sim: SimulatedDongle) = MaxSession(sim, log, sleep = {}, replyTimeoutMs = 20)

    private fun plan(preamp: Int = -4): WritePlan {
        val bands = listOf(
            Band(FilterType.LOW_SHELF, 105.0, 4.5, 0.7),
            Band(FilterType.PEAK, 2800.0, -3.2, 2.0),
            Band(FilterType.HIGH_SHELF, 9000.0, 2.0, 0.7),
        )
        return WritePlan(List(MaxLimits.BANDS) { i -> bands.getOrNull(i)?.let(MaxLimits::quantize) ?: MaxLimits.flatRegisters(i) }, preamp)
    }

    @Test
    fun `reads factory state despite the summary-packet quirk`() {
        val sim = SimulatedDongle(version = "0.7", initialSlot = 0xFF).apply { summaryQuirk = true }
        val s = session(sim).readState()
        assertEquals("0.7", s.version)
        assertEquals(10, s.bands.size)
        assertEquals(0xFF, s.slot)
        assertEquals(MaxLimits.flatRegisters(4), s.registers[4])
        assertEquals(0, s.preampDb)
    }

    @Test
    fun `self-test passes and never writes`() {
        val sim = SimulatedDongle()
        val report = SelfTest.run(session(sim), log)
        assertEquals(SelfTest.Status.PASS, report.status, report.checks.joinToString("\n"))
        assertTrue(sim.sent.none { (it[1].toInt() and 0xFF) == MaxProtocol.WRITE }, "self-test sent a write")
    }

    @Test
    fun `write verifies then commits to flash`() {
        val sim = SimulatedDongle()
        val sess = session(sim)
        val slot = sess.readState().slot
        val r = sess.write(plan(), slot, commit = true)
        assertIs<WriteResult.Committed>(r)
        assertEquals(1, sim.flashWrites)
        assertEquals(plan().registers, sim.flash.registers())
        assertEquals(-4, sim.flash.preamp)
        sim.replug()
        assertEquals(plan().registers, session(sim).readState().registers)
        assertTrue(sim.outOfLimitWrites.isEmpty())
    }

    @Test
    fun `commit happens only after verification`() {
        val sim = SimulatedDongle()
        session(sim).write(plan(), 0x01, commit = true)
        val cmds = sim.sent.map { (it[1].toInt() and 0xFF) to (it[2].toInt() and 0xFF) }
        val firstFlash = cmds.indexOf(0x01 to 0x01)
        val lastBandRead = cmds.subList(0, firstFlash).lastIndexOf(0x80 to 0x09)
        val lastBandWrite = cmds.lastIndexOf(0x01 to 0x09)
        assertTrue(lastBandWrite < lastBandRead && lastBandRead < firstFlash)
    }

    @Test
    fun `mismatch on read-back blocks the flash commit`() {
        val sim = SimulatedDongle().apply { corruptWriteOfBand = 1 }
        val r = session(sim).write(plan(), 0x01, commit = true)
        assertIs<WriteResult.Mismatch>(r)
        assertTrue(r.differences.any { "band 2" in it })
        assertEquals(0, sim.flashWrites)
        assertTrue(sim.sent.none { (it[1].toInt() and 0xFF) == 0x01 && (it[2].toInt() and 0xFF) == 0x01 })
        // Nothing applied to the DSP either.
        assertEquals(SimulatedDongle.factory(0x01, 0).registers(), sim.active.registers())
    }

    @Test
    fun `temporary write applies without touching flash`() {
        val sim = SimulatedDongle()
        val r = session(sim).write(plan(), 0x01, commit = false)
        assertIs<WriteResult.Applied>(r)
        assertEquals(0, sim.flashWrites)
        assertEquals(plan().registers, sim.active.registers())
        sim.replug()
        assertEquals(SimulatedDongle.factory(0x01, 0).registers(), sim.active.registers())
    }

    @Test
    fun `unplug mid-write surfaces as DeviceIoException and never commits`() {
        val sim = SimulatedDongle().apply { unplugAfterSends = 5 }
        assertFailsWith<DeviceIoException> { session(sim).write(plan(), 0x01, commit = true) }
        assertEquals(0, sim.flashWrites)
    }

    @Test
    fun `unplug mid-read surfaces as DeviceIoException`() {
        val sim = SimulatedDongle().apply { unplugAfterSends = 3 }
        assertFailsWith<DeviceIoException> { session(sim).readState() }
    }

    @Test
    fun `dropped replies and volume key noise are retried and ignored`() {
        val sim = SimulatedDongle().apply { dropReplyAt += setOf(3, 7); volumeKeyNoiseEvery = 2 }
        val s = session(sim).readState()
        assertEquals(10, s.bands.size)
    }

    @Test
    fun `a dongle that never answers times out`() {
        val sim = SimulatedDongle().apply { dropReplyAt += (1..100) }
        assertFailsWith<DeviceIoException> { session(sim).readVersion() }
    }

    @Test
    fun `write plan rejects unstorable or out-of-range content`() {
        assertFailsWith<MaxProtocol.Q30Overflow> {
            WritePlan(List(10) { if (it == 0) MaxLimits.quantize(Band(FilterType.HIGH_SHELF, 60.0, 10.0, 0.7)) else MaxLimits.flatRegisters(it) }, -10)
        }
        assertFailsWith<IllegalArgumentException> { WritePlan(List(10) { MaxLimits.flatRegisters(it) }, 3) }
        assertFailsWith<IllegalArgumentException> { WritePlan(List(9) { MaxLimits.flatRegisters(it) }, 0) }
    }
}

/** A transport that strips the report ID from input reports (some USB stacks do). */
class StrippedIdTest {
    @Test
    fun `replies without report ID still parse`() {
        val sim = SimulatedDongle()
        val stripped = object : HidChannel by sim {
            override fun receive(timeoutMs: Int): ByteArray? = sim.receive(timeoutMs)?.let { if (it[0].toInt() == 0x4B) it.copyOfRange(1, it.size) else it }
        }
        val s = MaxSession(stripped, DeviceLog.NONE, sleep = {}, replyTimeoutMs = 20).readState()
        assertEquals(10, s.bands.size)
    }
}
