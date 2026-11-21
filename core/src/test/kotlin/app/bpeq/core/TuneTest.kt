package app.bpeq.core

import app.bpeq.core.device.Planner
import app.bpeq.core.dsp.FreqGrid
import app.bpeq.core.dsp.Response
import app.bpeq.core.io.ImportException
import app.bpeq.core.model.Band
import app.bpeq.core.model.Eq
import app.bpeq.core.model.FilterType
import app.bpeq.core.model.PreampMode
import app.bpeq.core.tune.AutoTune
import app.bpeq.core.tune.Curve
import app.bpeq.core.tune.Rebuild
import app.bpeq.core.tune.Targets
import app.bpeq.core.tune.TuneSettings
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class CurveTest {
    @Test
    fun `parses squig, crinacle and REW style files`() {
        val csv = "frequency,raw\n" + (0..60).joinToString("\n") { val f = 20 * Math.pow(1000.0, it / 60.0); "%.2f,%.2f".format(java.util.Locale.US, f, 80 + it * 0.1) }
        val c = Curve.parse(csv, "x")
        assertEquals(61, c.freqs.size)
        val rew = "* REW export\nFreq(Hz) SPL(dB) Phase(degrees)\n" + (0..60).joinToString("\n") { val f = 20 * Math.pow(1000.0, it / 60.0); "$f\t${70.5}\t-12.0" }
        assertEquals(70.5, Curve.parse(rew, "r").at(1000.0), 1e-9)
        val semi = (0..60).joinToString("\n") { val f = 20 * Math.pow(1000.0, it / 60.0); "${"%.1f".format(java.util.Locale.US, f).replace('.', ',')};3,5" }
        assertEquals(3.5, Curve.parse(semi, "s").at(500.0), 1e-9)
        assertFailsWith<ImportException> { Curve.parse("hello\n1,2\n", "bad") }
    }

    @Test
    fun `interpolation is log-linear and normalisation centres the midrange`() {
        val c = Curve("t", doubleArrayOf(100.0, 1000.0, 10000.0), doubleArrayOf(0.0, 10.0, 0.0))
        assertEquals(5.0, c.at(Math.sqrt(100.0 * 1000.0)), 1e-9)
        assertEquals(0.0, c.at(5.0)); assertEquals(0.0, c.at(30000.0))
        assertEquals(0.0, Curve("f", doubleArrayOf(20.0, 20000.0), doubleArrayOf(5.0, 5.0)).normalized().at(1000.0), 1e-9)
    }

    @Test
    fun `bundled targets load`() {
        Targets.ALL.forEach { val t = Targets.load(it.id); assertTrue(t.freqs.size > 500, it.id) }
    }
}

class AutoTuneTest {
    /** A synthetic IEM: target minus a known EQ (so a perfect answer exists) plus small ripple. */
    private fun syntheticIem(target: Curve, hidden: List<Band>): Curve {
        val g = FreqGrid.log(400)
        val eq = Response.ideal(hidden, g)
        return Curve("iem", g.freqs, DoubleArray(g.size) { target.at(g.freqs[it]) - eq[it] + 0.3 * Math.sin(it * 0.7) + 7 })
    }

    @Test
    fun `fits a known correction quickly and accurately within device limits`() {
        val target = Targets.load("harman_ie_2019")
        val hidden = listOf(
            Band(FilterType.LOW_SHELF, 120.0, -4.0, 0.7),
            Band(FilterType.PEAK, 2500.0, 5.0, 1.5),
            Band(FilterType.PEAK, 6000.0, -6.0, 3.0),
            Band(FilterType.PEAK, 300.0, 2.0, 1.0),
        )
        val r = AutoTune.run(syntheticIem(target, hidden), target)
        println("rms ${"%.2f".format(r.rmsBefore)} -> ${"%.2f".format(r.rmsAfter)} dB in ${r.millis} ms with ${r.eq.bands.size} filters")
        assertTrue(r.rmsBefore > 2.0)
        assertTrue(r.rmsAfter < 0.6, "rms after ${r.rmsAfter}")
        assertTrue(r.millis < 4000, "took ${r.millis} ms")
        assertTrue(r.eq.bands.size <= 10)
        assertTrue(r.eq.bands.all { it.gainDb <= 6.0 + 1e-9 && it.gainDb >= -10.0 && it.q in 0.1..10.0 && it.freqHz in 20.0..16000.0 })
        assertTrue(Planner.plan(r.eq).ok, "result must be sendable")
    }

    @Test
    fun `respects filter count and boost settings`() {
        val target = Targets.load("harman_ie_2019")
        val iem = syntheticIem(target, listOf(Band(FilterType.PEAK, 1000.0, -8.0, 1.0), Band(FilterType.PEAK, 5000.0, -8.0, 2.0)))
        val r = AutoTune.run(iem, target, TuneSettings(maxFilters = 3, maxBoostDb = 3.0))
        assertTrue(r.eq.bands.size <= 3)
        assertTrue(r.eq.bands.all { it.gainDb <= 3.0 + 1e-9 })
        assertTrue(r.rmsAfter < r.rmsBefore)
    }
}

class RebuildTest {
    @Test
    fun `storable eq is unchanged`() {
        val eq = Eq(listOf(Band(FilterType.PEAK, 100.0, 3.0, 1.0)))
        val r = Rebuild.rebuild(eq)
        assertEquals(eq, r.eq); assertTrue(r.changes.isEmpty())
    }

    @Test
    fun `wide treble peak boost that overflows is rebuilt sendable`() {
        // Broad +10 dB peak high up: b0 approaches A^2 > 2 in Q30.
        val eq = Eq(listOf(Band(FilterType.PEAK, 15000.0, 10.0, 0.2)))
        if (Planner.plan(eq).ok) return // storable on this grid; nothing to test
        val r = Rebuild.rebuild(eq)
        assertTrue(r.ok, r.changes.toString())
        assertTrue(r.maxDiffDb < 2.0, "diff ${r.maxDiffDb}")
        assertTrue(abs(r.eq.bands.sumOf { it.gainDb }) > 5)
    }
}

class AutoTuneLevelTest {
    @Test
    fun `eq stays near 0 dB in the midrange so headroom is not wasted`() {
        val target = Targets.load("harman_ie_2019")
        val g = FreqGrid.log(400)
        val iem = Curve("iem", g.freqs, DoubleArray(g.size) { target.at(g.freqs[it]) + 70 + if (g.freqs[it] > 4000) -5.0 else 0.0 })
        val r = AutoTune.run(iem, target)
        val mid = Response.ideal(r.eq.bands, FreqGrid(doubleArrayOf(500.0, 1000.0, 2000.0)))
        assertTrue(mid.all { abs(it) < 1.0 }, mid.toList().toString())
        assertTrue(r.rmsAfter < 1.0, "rms ${r.rmsAfter}")
    }
}

class TuningPersistenceTest {
    @Test
    fun `profile with tuning curves survives json`() {
        val t = Targets.load("autoeq_ie").normalized().resampled(AutoTune.GRID.freqs)
        val p = app.bpeq.core.model.Profile(name = "x", tuning = app.bpeq.core.model.Tuning(t, t))
        val back = app.bpeq.core.model.Profile.fromJson(p.toJson())
        assertEquals(t.freqs.toList(), back.tuning!!.measurement.freqs.toList())
        assertEquals(t.db.toList(), back.tuning!!.target.db.toList())
        assertEquals(t.name, back.tuning!!.target.name)
    }
}

class ShelfBoundsTest {
    @Test
    fun `shelves stay in bass and treble`() {
        val target = Targets.load("harman_ie_2019")
        val g = FreqGrid.log(400)
        val iem = Curve("iem", g.freqs, DoubleArray(g.size) { val f = g.freqs[it]; target.at(f) + 3 * Math.exp(-Math.pow(Math.log(f / 3000) / 0.5, 2.0)) - 4 * Math.exp(-Math.pow(Math.log(f / 150) / 0.6, 2.0)) })
        val r = AutoTune.run(iem, target)
        assertTrue(r.eq.bands.filter { it.type == FilterType.LOW_SHELF }.all { it.freqHz <= 500 }, r.eq.bands.toString())
        assertTrue(r.eq.bands.filter { it.type == FilterType.HIGH_SHELF }.all { it.freqHz >= 1000 }, r.eq.bands.toString())
    }
}
