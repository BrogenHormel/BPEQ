package app.bpeq.core

import app.bpeq.core.device.MaxLimits
import app.bpeq.core.device.Planner
import app.bpeq.core.dsp.FreqGrid
import app.bpeq.core.dsp.Response
import app.bpeq.core.model.Band
import app.bpeq.core.model.Eq
import app.bpeq.core.model.FilterType
import app.bpeq.core.model.PreampMode
import app.bpeq.core.model.Tone
import app.bpeq.core.tune.Rebuild
import kotlin.math.abs
import kotlin.math.pow
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class StorableTest {
    private val grid = FreqGrid.log(200)

    /** Device shape (registers, level restored) vs the intended curve. */
    private fun shapeErr(eq: Eq): Double {
        val p = Planner.plan(eq)
        val dev = Planner.deviceCurve(p, grid)
        val want = Response.ideal(eq.bands, grid)
        return grid.freqs.indices.maxOf { abs(dev[it] - p.preampDb + p.levelDb - want[it]) }
    }

    @Test
    fun `unstorable high shelf is sent as a low shelf with the same shape`() {
        val eq = Eq(listOf(Band(FilterType.PEAK, 3000.0, -2.0, 2.0), Band(FilterType.HIGH_SHELF, 80.0, 9.0, 0.7)))
        val p = Planner.plan(eq)
        assertTrue(p.ok, p.issues.toString())
        assertEquals(FilterType.LOW_SHELF, p.registers[1].type)
        assertEquals(9.0, p.levelDb, 0.01)
        assertTrue(p.clampNotes.any { it.startsWith("Band 2: sent as a low shelf") }, p.clampNotes.toString())
        assertTrue(shapeErr(eq) < 0.05, "err ${shapeErr(eq)}")
    }

    @Test
    fun `manual preamp is raised by the flipped shelf's level`() {
        val p = Planner.plan(Eq(listOf(Band(FilterType.HIGH_SHELF, 60.0, 8.0, 0.7)), PreampMode.MANUAL, -10.0))
        assertEquals(-2, p.preampDb)
    }

    /**
     * Every band the editor allows, on its own: the planner sends it (shelves flipped as needed), or, for the few
     * very wide big treble peaks it can't, Rebuild makes it sendable.
     */
    @Test
    fun `every single band is sendable directly or after rebuild`() {
        val freqs = (0..40).map { 20.0 * 1000.0.pow(it / 40.0) }
        val gains = (-20..20).map { it * 0.5 }
        val qs = listOf(0.1, 0.2, 0.4, 0.7, 1.0, 2.0, 4.0, 10.0)
        var rebuilt = 0
        for (t in FilterType.entries) for (f in freqs) for (g in gains) for (q in qs) {
            val eq = Eq(listOf(Band(t, f, g, q)))
            val p = Planner.plan(eq)
            if (p.ok) continue
            assertEquals(FilterType.PEAK, t, "$t ${f.toInt()} Hz $g dB Q$q: ${p.issues}")
            val r = Rebuild.rebuild(eq)
            assertTrue(r.ok, "rebuild failed for PK ${f.toInt()} Hz $g dB Q$q: ${r.changes}")
            rebuilt++
        }
        println("peaks needing rebuild: $rebuilt")
        assertTrue(rebuilt < 100, "$rebuilt")
    }

    @Test
    fun `tone bands are named in messages and a merge is reported`() {
        val bands = List(9) { Band(FilterType.PEAK, 60.0 * 1.9.pow(it), if (it % 2 == 0) 3.0 else -2.0, 2.0) }
        val p = Planner.plan(Eq(bands, tone = Tone(bassDb = 4.0, trebleDb = 3.0, earGainDb = 1.5)))
        assertTrue(p.ok)
        assertTrue(p.clampNotes.any { it.startsWith("Tone blended into your bands") }, p.clampNotes.toString())
        val q = Planner.plan(Eq(bands.take(2), tone = Tone(trebleDb = 3.0)))
        assertTrue(q.clampNotes.any { it.startsWith("Treble tone: sent as a low shelf") }, q.clampNotes.toString())
        assertTrue(q.clampNotes.none { it.startsWith("Tone blended") })
    }

    @Test
    fun `manual preamp that lets the output clip is warned about`() {
        val boost = listOf(Band(FilterType.PEAK, 1000.0, 6.0, 1.0))
        val loud = Planner.plan(Eq(boost, PreampMode.MANUAL, -2.0))
        assertTrue(loud.ok)
        assertTrue(loud.warnings.single().startsWith("Manual preamp -2 dB lets the loudest point reach +4.0 dB"), loud.warnings.toString())
        assertTrue(Planner.plan(Eq(boost, PreampMode.MANUAL, -6.0)).warnings.isEmpty())
        assertTrue(Planner.plan(Eq(boost)).warnings.isEmpty())
    }

    @Test
    fun `gain beyond the device range is flipped after clamping`() {
        val p = Planner.plan(Eq(listOf(Band(FilterType.HIGH_SHELF, 60.0, 14.0, 0.7))))
        assertTrue(p.ok); assertEquals(MaxLimits.GAIN_MAX, p.levelDb, 0.01)
    }

    @Test
    fun `switching to manual at the shown auto value keeps the device preamp and does not clip`() {
        for (eq in listOf(
            Eq(listOf(Band(FilterType.PEAK, 148.0, 3.3, 1.06), Band(FilterType.HIGH_SHELF, 80.0, 6.0, 0.7)), tone = Tone(trebleDb = 6.0)),
            Eq(listOf(Band(FilterType.PEAK, 9772.0, 6.0, 2.6)), tone = Tone(trebleDb = 2.5)),
        )) {
            val auto = Planner.plan(eq)
            val manual = Planner.plan(eq.copy(preampMode = PreampMode.MANUAL, manualPreampDb = auto.preampDb - auto.levelDb))
            assertEquals(auto.preampDb, manual.preampDb)
            assertTrue(manual.warnings.isEmpty(), manual.warnings.toString())
        }
    }

    @Test
    fun `EQ Off preamp matches loudness`() {
        fun plan(vararg b: Band, preamp: Double? = null) = Planner.plan(if (preamp == null) Eq(b.toList()) else Eq(b.toList(), PreampMode.MANUAL, preamp)).plan!!
        val flat = plan()
        assertEquals(0.0, Planner.loudnessDb(flat.registers), 1e-6)
        // A broad -6 dB cut over the midrange: EQ On is quieter, so EQ Off goes down too.
        val cut = plan(Band(FilterType.PEAK, 1000.0, -6.0, 0.3), preamp = 0.0)
        val lc = Planner.loudnessDb(cut.registers)
        println("broad cut loudness $lc dB, flat preamp ${Planner.loudnessMatchedFlatPreamp(cut)}")
        assertTrue(lc < -1.0 && lc > -6.0, "$lc")
        assertEquals(Math.round(lc).toInt(), Planner.loudnessMatchedFlatPreamp(cut))
        // A +6 dB bass shelf with auto preamp -6: louder than flat at -6, but much less than 6 dB louder.
        val bass = plan(Band(FilterType.LOW_SHELF, 105.0, 6.0, 0.7))
        val lb = Planner.loudnessDb(bass.registers)
        println("bass shelf loudness $lb dB, preamp ${bass.preampDb}, flat preamp ${Planner.loudnessMatchedFlatPreamp(bass)}")
        assertTrue(lb in 0.3..3.0, "$lb")
        assertTrue(Planner.loudnessMatchedFlatPreamp(bass) > bass.preampDb)
    }
}
