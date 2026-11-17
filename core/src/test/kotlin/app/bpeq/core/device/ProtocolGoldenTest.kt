package app.bpeq.core.device

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Golden tests against real Protocol Max USB traffic (firmware 0.7) captured by devicePEQ
 * (0BSD, see resources/captures/LICENSE-devicePEQ.txt): one capture from the devicePEQ plugin, one from
 * the official WalkPlay web tool.
 */
class ProtocolGoldenTest {
    private data class Exchange(val send: ByteArray, val responses: List<ByteArray>)

    private fun load(name: String): List<Exchange> {
        val text = javaClass.getResource("/captures/$name")!!.readText()
        val root = Json.parseToJsonElement(text).jsonObject
        return root["exchanges"]!!.jsonArray.map { e ->
            val o = e.jsonObject
            fun rep(r: JsonObject): ByteArray {
                val data = r["data"]!!.jsonArray.map { it.jsonPrimitive.int }
                return ByteArray(64).also { buf ->
                    buf[0] = r["reportId"]!!.jsonPrimitive.int.toByte()
                    data.forEachIndexed { i, v -> if (i < 63) buf[i + 1] = v.toByte() }
                }
            }
            Exchange(rep(o["send"]!!.jsonObject), o["responses"]?.jsonArray?.map { rep(it.jsonObject) } ?: emptyList())
        }
    }

    private fun bandWrites(ex: List<Exchange>) = ex.map { it.send }.filter {
        (it[1].toInt() and 0xFF) == 0x01 && (it[2].toInt() and 0xFF) == 0x09
    }

    /** Decode the registers from a captured write packet (same layout as a reply). */
    private fun regsOf(p: ByteArray): MaxProtocol.Registers {
        fun u(i: Int) = p[i].toInt() and 0xFF
        var g = u(32) or (u(33) shl 8); if (g > 32767) g -= 65536
        return MaxProtocol.Registers(u(28) or (u(29) shl 8), u(30) or (u(31) shl 8), g, u(34))
    }

    @Test
    fun `official WalkPlay tool band writes are reproduced byte for byte`() {
        val writes = bandWrites(load("walkplay_schemeno16_protocol_max_walkplay_site.json"))
        assertEquals(10, writes.size)
        writes.forEachIndexed { i, captured ->
            if (i == 3) return@forEachIndexed // 38-byte packet, checked below
            val ours = MaxProtocol.bandWrite(i, regsOf(captured), captured[36].toInt() and 0xFF)
            assertContentEquals(captured, ours, "band $i (${regsOf(captured)})")
        }
        // Band 4 was recorded as a 38-byte packet with two extra zero bytes before the coefficients.
        // Its content is PK 250 Hz +10 dB Q 0.75 - a real +10 dB boost; the 20 coefficient bytes must match ours.
        val odd = writes[3]
        fun u(i: Int) = odd[i].toInt() and 0xFF
        val regs = MaxProtocol.Registers(u(30) or (u(31) shl 8), u(32) or (u(33) shl 8), u(34) or (u(35) shl 8), u(36))
        assertEquals(MaxProtocol.Registers(250, 192, 2560, 2), regs)
        assertContentEquals(odd.copyOfRange(10, 30), MaxProtocol.biquadBytes(regs))
    }

    @Test
    fun `devicePEQ band writes are reproduced byte for byte`() {
        val writes = bandWrites(load("walkplay_schemeno16_protocol_max.json"))
        assertEquals(10, writes.size)
        writes.forEachIndexed { i, captured ->
            assertContentEquals(captured, MaxProtocol.bandWrite(i, regsOf(captured), 0x65), "band $i")
        }
    }

    @Test
    fun `stored biquads in device replies match our encoder`() {
        val ex = load("walkplay_schemeno16_protocol_max.json")
        var checked = 0
        ex.forEach { e ->
            val req = e.send
            if ((req[1].toInt() and 0xFF) != 0x80 || (req[2].toInt() and 0xFF) != 0x09) return@forEach
            val reply = e.responses.firstOrNull() ?: return@forEach
            val idx = req[5].toInt()
            if (!MaxProtocol.isBandReply(reply, idx)) return@forEach
            val b = MaxProtocol.parseBand(reply, idx)
            assertContentEquals(b.biquad, MaxProtocol.biquadBytes(b.registers), "band $idx ${b.registers}")
            assertEquals(0xFF, b.slot)
            checked++
        }
        assertTrue(checked >= 10, "checked $checked band replies")
    }

    @Test
    fun `preamp, version and commit reports match the capture`() {
        val ex = load("walkplay_schemeno16_protocol_max.json")
        val sends = ex.map { it.send }
        val versionReply = ex.first { (it.send[2].toInt() and 0xFF) == 0x0C }.responses.first()
        assertEquals("0.7", MaxProtocol.parseVersion(versionReply))
        val preampReply = ex.first { (it.send[2].toInt() and 0xFF) == 0x03 }.responses.first()
        assertEquals(6, MaxProtocol.parsePreamp(preampReply))
        val tail = sends.takeLast(4)
        assertContentEquals(MaxProtocol.commitA(), tail[0])
        assertContentEquals(MaxProtocol.commitB(), tail[1])
        assertContentEquals(MaxProtocol.tempWrite(), tail[2])
        // The May-2026 capture sends FLASH_EQ as `01 01 00`; current devicePEQ and Contour (tested on a Max)
        // send `01 01 01` ("persist + PEQ on"). We follow the current, hardware-tested form.
        assertEquals(MaxProtocol.CMD_FLASH_EQ, tail[3][2].toInt())
        assertEquals(1, MaxProtocol.flashCommit()[3].toInt())
        assertContentEquals(MaxProtocol.versionRequest(), sends[0])
        assertContentEquals(MaxProtocol.bandRequest(3), sends[5])
    }

    @Test
    fun `summary packet after VERSION is not mistaken for band 0`() {
        val ex = load("walkplay_schemeno16_protocol_max.json")
        val summary = ex[1].responses.first()
        assertTrue(!MaxProtocol.isBandReply(summary, 0))
    }
}
