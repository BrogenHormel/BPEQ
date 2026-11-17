package app.bpeq.core.device

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Replays the owner's real Protocol Max (firmware 1.4) read-out through the real session code. */
class OwnerDeviceReplayTest {
    private val replies: Map<String, ByteArray> = javaClass.getResource("/captures/owner-protocol-max-fw1.4-2026-11-16.txt")!!
        .readText().lines().filter { it.isNotBlank() && !it.startsWith("#") }.associate { line ->
            val parts = line.trim().split(" ")
            parts[0] to ByteArray(64).also { b -> parts.drop(1).forEachIndexed { i, h -> b[i] = h.toInt(16).toByte() } }
        }

    private inner class Replay : HidChannel {
        val queue = ArrayDeque<ByteArray>()
        val sent = mutableListOf<ByteArray>()
        override val description = "replay"
        override fun send(report: ByteArray) {
            sent += report
            val d = report.map { it.toInt() and 0xFF }
            val key = when {
                d[1] == 0x80 && d[2] == 0x0C -> "version"
                d[1] == 0x80 && d[2] == 0x09 -> "band${d[5]}"
                d[1] == 0x80 && d[2] == 0x03 -> "preamp"
                else -> null
            }
            key?.let { queue.addLast(replies.getValue(it)) }
        }
        override fun receive(timeoutMs: Int) = queue.removeFirstOrNull()
        override fun close() {}
    }

    @Test
    fun `owner device reads, decodes and self-tests without failures`() {
        val ch = Replay()
        val log = MemoryLog()
        val report = SelfTest.run(MaxSession(ch, log, sleep = {}, replyTimeoutMs = 20), log)
        assertEquals(SelfTest.Status.WARN, report.status, report.checks.joinToString("\n"))
        val s = report.state!!
        assertEquals("1.4", s.version)
        assertEquals(0x01, s.slot)
        assertEquals(0, s.preampDb)
        // Bands 2..10 are the firmware's factory flat, bit-exact with our encoder.
        for (i in 1 until 10) assertEquals(MaxLimits.flatRegisters(i), s.registers[i])
        assertEquals(MaxProtocol.Registers(1000, 182, 0, 2), s.registers[0])
        assertTrue(report.checks.single { it.status == SelfTest.Status.WARN }.text.startsWith("band 1:"))
        assertTrue(ch.sent.none { (it[1].toInt() and 0xFF) == MaxProtocol.WRITE })
    }
}
