package app.bpeq.core

import app.bpeq.core.device.MaxLimits
import app.bpeq.core.device.Planner
import app.bpeq.core.io.ApoText
import app.bpeq.core.io.EqByEarJson
import app.bpeq.core.io.EqImport
import app.bpeq.core.io.ImportException
import app.bpeq.core.model.Band
import app.bpeq.core.model.Eq
import app.bpeq.core.model.FilterType
import app.bpeq.core.model.PreampMode
import app.bpeq.core.model.Profile
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ImportersTest {
    @Test
    fun `AutoEQ ParametricEQ txt`() {
        val r = EqImport.parse("""
            Preamp: -6.4 dB
            Filter 1: ON LSC Fc 105 Hz Gain 6.6 dB Q 0.70
            Filter 2: ON PK Fc 2400 Hz Gain -3,5 dB Q 1.41
            Filter 3: ON HSC Fc 10000 Hz Gain -2.0 dB Q 0.70
            Filter 4: ON LP Fc 18000 Hz Q 0.7
        """.trimIndent())
        val e = r.eq
        assertEquals(PreampMode.MANUAL, e.preampMode); assertEquals(-6.4, e.manualPreampDb)
        assertEquals(3, e.bands.size)
        assertEquals(Band(FilterType.LOW_SHELF, 105.0, 6.6, 0.70, id = 0).copy(id = e.bands[0].id), e.bands[0])
        assertEquals(-3.5, e.bands[1].gainDb)
        assertEquals(FilterType.HIGH_SHELF, e.bands[2].type)
        assertTrue(r.notes.any { "unsupported" in it })
    }

    @Test
    fun `squig link export with channel blocks and zero pads`() {
        val r = ApoText.parse("""
            Channel: L
            Preamp: -3 dB
            Filter 1: ON PK Fc 60 Hz Gain 3 dB Q 0.8
            Filter 2: ON PK Fc 1000 Hz Gain 0 dB Q 1
            Channel: R
            Preamp: -4 dB
            Filter 1: ON PK Fc 70 Hz Gain 3 dB Q 0.8
        """.trimIndent())
        assertEquals(1, r.eq.bands.size); assertEquals(60.0, r.eq.bands[0].freqHz); assertEquals(-3.0, r.eq.manualPreampDb)
        assertTrue(r.notes.any { "Left" in it }); assertTrue(r.notes.any { "0 dB" in it })
    }

    @Test
    fun `OFF filters become bypassed bands, no preamp means auto`() {
        val e = ApoText.parse("Filter: OFF PK Fc 300 Hz Gain -2 dB Q 2\nFilter: ON PEQ Fc 3k Hz Gain 1 dB").eq
        assertFalse(e.bands[0].enabled)
        assertEquals(PreampMode.AUTO, e.preampMode)
    }

    @Test
    fun `EQ by Ear json`() {
        val r = EqImport.parse("""{"bands":[{"type":"LSC","fc":90,"gain":4,"q":0.7},{"type":"PK","fc":3000,"gain":-2,"q":2,"enabled":false},{"type":"BP","fc":1}],"preampAuto":true}""")
        assertEquals(2, r.eq.bands.size); assertEquals(PreampMode.AUTO, r.eq.preampMode); assertFalse(r.eq.bands[1].enabled)
        assertEquals(EqByEarJson.parse("""{"bands":[{"type":"PK","fc":1,"gain":1}],"preampDb":-2}""").eq.manualPreampDb, -2.0)
    }

    @Test
    fun `garbage is rejected with a clear message`() {
        assertFailsWith<ImportException> { EqImport.parse("hello world") }
        assertFailsWith<ImportException> { EqImport.parse("Preamp: -3 dB\n") }
    }

    @Test
    fun `export round trips`() {
        val eq = Eq(listOf(Band(FilterType.LOW_SHELF, 105.0, 4.5, 0.71), Band(FilterType.PEAK, 2500.0, -3.25, 1.414, enabled = false)), PreampMode.MANUAL, -5.0)
        val text = ApoText.format(eq.bands, eq.manualPreampDb)
        assertTrue(text.startsWith("Preamp: -5.0 dB\r\nFilter 1: ON LSC Fc 105 Hz Gain 4.5 dB Q 0.71"))
        assertTrue(eq.sameSettings(ApoText.parse(text).eq))
    }

    @Test
    fun `names from files`() {
        assertEquals("Moondrop Blessing 3", EqImport.nameFromFile("Moondrop Blessing 3 Filters.txt"))
        assertEquals("Truthear Zero", EqImport.nameFromFile("/x/Truthear Zero ParametricEQ.txt"))
        assertNull(EqImport.nameFromFile(null))
    }
}

class PlannerTest {
    @Test
    fun `auto preamp covers the peak in whole dB`() {
        val r = Planner.plan(Eq(listOf(Band(FilterType.PEAK, 100.0, 4.2, 1.0))))
        assertEquals(-5, r.preampDb)
        assertTrue(r.ok)
        assertEquals(0, Planner.plan(Eq(listOf(Band(FilterType.PEAK, 100.0, -4.0, 1.0)))).preampDb)
    }

    @Test
    fun `manual preamp is rounded down and clamped`() {
        assertEquals(-4, Planner.plan(Eq(emptyList(), PreampMode.MANUAL, -3.4)).preampDb)
        assertEquals(0, Planner.plan(Eq(emptyList(), PreampMode.MANUAL, 3.0)).preampDb)
    }

    @Test
    fun `disabled bands are skipped and spare slots are factory flat`() {
        val r = Planner.plan(Eq(listOf(Band(gainDb = 3.0, enabled = false), Band(FilterType.PEAK, 500.0, -2.0, 2.0))))
        assertEquals(500, r.registers[0].freq)
        assertEquals(mapOf(1 to 0), r.slotOf)
        for (i in 1 until 10) assertEquals(MaxLimits.flatRegisters(i), r.registers[i])
    }

    @Test
    fun `too many bands and unstorable shelves are reported`() {
        val many = Planner.plan(Eq(List(11) { Band(FilterType.PEAK, 100.0 * (it + 1), -1.0, 1.0) }))
        assertTrue(many.tooManyBands); assertFalse(many.ok)
        val hs = Planner.plan(Eq(listOf(Band(FilterType.PEAK, 1000.0, -1.0, 1.0), Band(FilterType.HIGH_SHELF, 60.0, 10.0, 0.7))))
        assertEquals(1, hs.unstorable.single().bandIndex)
        assertNull(hs.plan)
    }

    @Test
    fun `device curve includes preamp`() {
        val r = Planner.plan(Eq(listOf(Band(FilterType.PEAK, 1000.0, 6.0, 1.0))))
        val c = Planner.deviceCurve(r, app.bpeq.core.dsp.FreqGrid(doubleArrayOf(1000.0)))
        assertEquals(6.0 + r.preampDb, c[0], 0.02)
    }
}

class ProfileHistoryTest {
    @Test
    fun `undo redo and the 100 step cap`() {
        var p = Profile(name = "x")
        repeat(120) { p = p.edit(Eq(listOf(Band(gainDb = it.toDouble())))) }
        assertEquals(100, p.undo.size)
        assertEquals(119.0, p.working.bands[0].gainDb)
        p = p.undo().undo()
        assertEquals(117.0, p.working.bands[0].gainDb)
        p = p.redo()
        assertEquals(118.0, p.working.bands[0].gainDb)
        p = p.edit(Eq.FLAT)
        assertTrue(p.redo.isEmpty())
    }

    @Test
    fun `saved version is separate from the working copy and survives json`() {
        var p = Profile(name = "A").edit(Eq(listOf(Band(gainDb = 2.0))))
        assertTrue(p.dirty); assertEquals(Eq.FLAT, p.saved)
        p = p.save(); assertFalse(p.dirty); assertEquals(2.0, p.saved.bands[0].gainDb)
        p = p.edit(Eq.FLAT).revert()
        assertEquals(2.0, p.working.bands[0].gainDb)
        val back = Profile.fromJson(p.toJson())
        assertEquals(p, back)
    }
}

class FromDeviceTest {
    @Test
    fun `device state becomes an editable eq that plans back to the same registers`() {
        val sim = app.bpeq.core.device.SimulatedDongle()
        val sess = app.bpeq.core.device.MaxSession(sim, app.bpeq.core.device.DeviceLog.NONE, sleep = {}, replyTimeoutMs = 20)
        sess.write(app.bpeq.core.device.TestPlans.audibleCut(), 0x01, commit = true)
        val state = sess.readState()
        val eq = Planner.eqFromDevice(state)
        assertEquals(3, eq.bands.size)
        assertEquals(-1.0, eq.manualPreampDb)
        assertTrue(Planner.matchesDevice(eq, state))
        assertTrue(Planner.eqFromDevice(sess.also { sess.write(app.bpeq.core.device.TestPlans.flat(), 0x01, true) }.readState()).bands.isEmpty())
    }
}
