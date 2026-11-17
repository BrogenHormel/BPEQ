package app.bpeq.core.device

import app.bpeq.core.dsp.Biquad
import app.bpeq.core.dsp.FreqGrid
import app.bpeq.core.dsp.Response
import app.bpeq.core.model.Band
import app.bpeq.core.model.FilterType
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LimitsAndDspTest {
    @Test
    fun `quantize clamps every field into device range`() {
        val r = MaxLimits.quantize(Band(FilterType.PEAK, 5.0, 25.0, 50.0))
        assertEquals(20, r.freq); assertEquals(2560, r.gain256); assertEquals(2560, r.q256)
        val r2 = MaxLimits.quantize(Band(FilterType.PEAK, 99_999.0, -40.0, 0.0))
        assertEquals(20_000, r2.freq); assertEquals(-2560, r2.gain256); assertEquals(26, r2.q256)
        val r3 = MaxLimits.quantize(Band(FilterType.PEAK, Double.NaN, Double.NaN, Double.NaN))
        assertTrue(MaxLimits.isValid(r3))
    }

    @Test
    fun `gain rounds toward quieter`() {
        assertEquals(25, MaxLimits.quantize(Band(gainDb = 0.1)).gain256)    // 25.6 -> 25
        assertEquals(-26, MaxLimits.quantize(Band(gainDb = -0.1)).gain256)  // -25.6 -> -26
        assertEquals(2560, MaxLimits.quantize(Band(gainDb = 10.0)).gain256)
    }

    @Test
    fun `preamp is whole dB rounded down and clamped`() {
        assertEquals(-4, MaxLimits.quantizePreamp(-3.2))
        assertEquals(-3, MaxLimits.quantizePreamp(-3.0))
        assertEquals(0, MaxLimits.quantizePreamp(2.5))
        assertEquals(-30, MaxLimits.quantizePreamp(-45.0))
        assertEquals(-30, MaxLimits.quantizePreamp(Double.NaN))
    }

    @Test
    fun `preamp write refuses out-of-range values`() {
        assertFailsWith<IllegalArgumentException> { MaxProtocol.preampWrite(1) }
        assertFailsWith<IllegalArgumentException> { MaxProtocol.preampWrite(-31) }
        assertEquals(0xF6.toByte(), MaxProtocol.preampWrite(-10)[5])
    }

    @Test
    fun `band write refuses out-of-range registers`() {
        assertFailsWith<IllegalArgumentException> { MaxProtocol.bandWrite(0, MaxProtocol.Registers(10, 192, 0, 2), 0) }
        assertFailsWith<IllegalArgumentException> { MaxProtocol.bandWrite(0, MaxProtocol.Registers(1000, 192, 2561, 2), 0) }
        assertFailsWith<IllegalArgumentException> { MaxProtocol.bandWrite(10, MaxLimits.flatRegisters(0), 0) }
    }

    @Test
    fun `large low frequency high shelf boost overflows Q30`() {
        val hs = MaxLimits.quantize(Band(FilterType.HIGH_SHELF, 100.0, 10.0, 0.7))
        assertFalse(MaxProtocol.storable(hs))
        assertFailsWith<MaxProtocol.Q30Overflow> { MaxProtocol.bandWrite(0, hs, 0) }
        // The equivalent low shelf cut is fine.
        assertTrue(MaxProtocol.storable(MaxLimits.quantize(Band(FilterType.LOW_SHELF, 100.0, -10.0, 0.7))))
        assertTrue(MaxProtocol.storable(MaxLimits.quantize(Band(FilterType.HIGH_SHELF, 8000.0, 4.0, 0.7))))
    }

    @Test
    fun `peak response equals gain at centre and zero far away`() {
        val grid = FreqGrid(doubleArrayOf(1000.0, 20.0, 20000.0))
        val out = Response.ideal(listOf(Band(FilterType.PEAK, 1000.0, 6.0, 2.0)), grid)
        assertEquals(6.0, out[0], 1e-9)
        assertTrue(abs(out[1]) < 0.05 && abs(out[2]) < 0.1)
    }

    @Test
    fun `shelves reach their gain at the far end`() {
        val grid = FreqGrid(doubleArrayOf(20.0, 20000.0))
        val ls = Response.ideal(listOf(Band(FilterType.LOW_SHELF, 200.0, 5.0, 0.7)), grid)
        assertEquals(5.0, ls[0], 0.1); assertEquals(0.0, ls[1], 0.05)
        val hs = Response.ideal(listOf(Band(FilterType.HIGH_SHELF, 2000.0, -4.0, 0.7)), grid)
        assertEquals(0.0, hs[0], 0.05); assertEquals(-4.0, hs[1], 0.3)
    }

    @Test
    fun `disabled bands do not contribute and responses add`() {
        val grid = FreqGrid.DISPLAY
        val a = Band(FilterType.PEAK, 300.0, 3.0, 1.0)
        val b = Band(FilterType.LOW_SHELF, 100.0, -2.0, 0.7)
        val sum = Response.ideal(listOf(a, b, b.copy(enabled = false, gainDb = 9.0)), grid)
        val ra = Response.band(a, grid); val rb = Response.band(b, grid)
        for (i in sum.indices) assertEquals(ra[i] + rb[i], sum[i], 1e-9)
    }

    @Test
    fun `device Q30 curve matches ideal curve closely`() {
        val band = Band(FilterType.PEAK, 50.0, -7.5, 4.0)
        val regs = MaxLimits.quantize(band)
        val grid = FreqGrid.DISPLAY
        val ideal = Response.ideal(listOf(MaxLimits.toBand(regs)), grid)
        val dev = Response.coeffs(listOf(MaxProtocol.deviceCoeffs(regs)), grid)
        for (i in ideal.indices) assertEquals(ideal[i], dev[i], 0.01)
    }

    @Test
    fun `HS gain g equals LS gain minus g plus g dB`() {
        val grid = FreqGrid.DISPLAY
        val hs = Response.ideal(listOf(Band(FilterType.HIGH_SHELF, 150.0, 8.0, 0.8)), grid)
        val ls = Response.ideal(listOf(Band(FilterType.LOW_SHELF, 150.0, -8.0, 0.8)), grid)
        for (i in hs.indices) assertEquals(hs[i], ls[i] + 8.0, 1e-6)
        assertTrue(Biquad.shelfValid(8.0, 0.8))
    }
}
