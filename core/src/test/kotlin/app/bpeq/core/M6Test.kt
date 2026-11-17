package app.bpeq.core

import app.bpeq.core.device.Planner
import app.bpeq.core.dsp.FreqGrid
import app.bpeq.core.dsp.Response
import app.bpeq.core.io.ImportException
import app.bpeq.core.io.LibraryBackup
import app.bpeq.core.model.Band
import app.bpeq.core.model.Eq
import app.bpeq.core.model.FilterType
import app.bpeq.core.model.Profile
import app.bpeq.core.model.Tone
import app.bpeq.core.tune.ToneBlend
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class ToneTest {
    private val grid = FreqGrid.log(200)
    private fun curve(eq: Eq) = Response.ideal(eq.bands, grid)
    private fun maxErr(a: DoubleArray, b: DoubleArray) = a.indices.filter { grid.freqs[it] < 16000 }.maxOf { abs(a[it] - b[it]) }

    @Test
    fun `tone is appended exactly when there is room`() {
        val eq = Eq(listOf(Band(FilterType.PEAK, 1000.0, -2.0, 1.0)), tone = Tone(bassDb = 3.0, earGainDb = -2.0))
        val e = ToneBlend.apply(eq)
        assertEquals(3, e.bands.size); assertTrue(e.tone.isZero)
        val want = curve(Eq(eq.bands + ToneBlend.toneBands(eq.tone)))
        assertEquals(0.0, maxErr(want, curve(e)), 1e-9)
    }

    @Test
    fun `tone on a full profile stays within 10 bands and close to the exact curve`() {
        val bands = List(10) { Band(FilterType.PEAK, 60.0 * Math.pow(1.9, it.toDouble()), if (it % 2 == 0) 3.0 else -2.0, 2.0) }
        val eq = Eq(bands, tone = Tone(bassDb = 4.0, trebleDb = -2.0, earGainDb = 1.5))
        val e = ToneBlend.apply(eq)
        assertTrue(e.bands.count { it.enabled } <= 10)
        val want = curve(Eq(bands + ToneBlend.toneBands(eq.tone)))
        val err = maxErr(want, curve(e))
        println("tone refit max error ${"%.2f".format(err)} dB")
        assertTrue(err < 1.5, "err $err")
        assertTrue(Planner.plan(eq).ok)
    }

    @Test
    fun `planner sends tone and zero tone changes nothing`() {
        val eq = Eq(listOf(Band(FilterType.PEAK, 1000.0, -2.0, 1.0)))
        assertEquals(Planner.plan(eq).registers, Planner.plan(eq.copy(tone = Tone())).registers)
        assertEquals(105, Planner.plan(eq.copy(tone = Tone(bassDb = 2.0))).registers[1].freq)
    }
}

class BackupFileTest {
    @Test
    fun `library backup round trip and merge never overwrites`() {
        val a = Profile(name = "A", saved = Eq(listOf(Band(gainDb = 1.0))))
        val b = Profile(name = "B")
        val json = LibraryBackup(profiles = listOf(a, b)).toJson()
        val back = LibraryBackup.fromJson(json)
        assertEquals(listOf("A", "B"), back.profiles.map { it.name })
        // identical A skipped; changed B added as a copy; new C added
        val changedB = b.copy(saved = Eq(listOf(Band(gainDb = 5.0))))
        val c = Profile(name = "C")
        val merged = LibraryBackup.merge(listOf(a, b), listOf(a, changedB, c))
        assertEquals(listOf("B (2)", "C"), merged.map { it.name })
        assertTrue(merged[0].id != b.id)
        assertFailsWith<ImportException> { LibraryBackup.fromJson("{\"format\":\"x\",\"profiles\":[]}") }
    }
}
