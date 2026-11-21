package app.bpeq.core

import app.bpeq.core.io.ImportException
import app.bpeq.core.io.LinkImport
import app.bpeq.core.model.FilterType
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LinkImportTest {
    private fun fr(offset: Double) = (0..60).joinToString("\n") { i -> val f = 20.0 * Math.pow(1000.0, i / 60.0); "$f\t${80 + offset + (if (f > 3000) 5 else 0)}" }

    /** A fake site; records every URL asked for. */
    private class FakeWeb(val files: Map<String, String>) : LinkImport.Fetch {
        val asked = ArrayList<String>()
        override fun text(url: String): String? { asked += url; return files[url] }
    }

    private val site = mapOf(
        "https://example.squig.link/config.js" to "const x = 1,\n      DIR = \"data/\",   // where graphs live",
        "https://example.squig.link/data/phone_book.json" to
            """[{"name":"Acme","phones":[{"name":"One Pro","file":["Acme One Pro","Acme One Pro deep"]},"Plain"]}]""",
        "https://example.squig.link/data/Acme%20One%20Pro%20L.txt" to "* REW export\n" + fr(0.0),
        "https://example.squig.link/data/Acme%20One%20Pro%20R.txt" to fr(2.0),
        "https://example.squig.link/data/Harman%20IE%202019%20Target.txt" to fr(-5.0),
    )

    @Test
    fun `squig share link gives the measurement (L and R averaged) and the target`() {
        val web = FakeWeb(site)
        val r = LinkImport.import("https://example.squig.link/?share=Acme_One_Pro,Harman_IE_2019_Target", web)
        r as LinkImport.Result.Measurement
        assertEquals("Acme One Pro", r.measurement.name)
        assertEquals(81.0, r.measurement.at(1000.0), 0.01)
        assertEquals("Harman IE 2019 Target", r.target!!.name)
        assertTrue(r.notes.isEmpty(), r.notes.toString())
    }

    @Test
    fun `share link by display name falls back to the phone book`() {
        val r = LinkImport.import("https://example.squig.link/index.html?share=Acme_One_Pro_(fit)", FakeWeb(site + mapOf(
            "https://example.squig.link/data/phone_book.json" to
                """[{"name":"Acme","phones":[{"name":"One Pro (fit)","file":"Acme One Pro"}]}]""")))
        r as LinkImport.Result.Measurement
        assertEquals("Acme One Pro (fit)", r.measurement.name)
        assertNull(r.target)
    }

    @Test
    fun `squig EQ link gives filters plus overlays`() {
        val filters = Base64.getUrlEncoder().withoutPadding().encodeToString("v2;0,0,1000,1.4,-3;1,2,8000,0.7,4;0,1,105,0.7,2".toByteArray())
        val r = LinkImport.import("https://example.squig.link/?eqModel=Acme_One_Pro&eqTarget=Harman_IE_2019&eqFilters=$filters", FakeWeb(site))
        r as LinkImport.Result.Filters
        val b = r.import.eq.bands
        assertEquals(listOf(FilterType.PEAK, FilterType.HIGH_SHELF, FilterType.LOW_SHELF), b.map { it.type })
        assertEquals(-3.0, b[0].gainDb); assertEquals(1.4, b[0].q); assertTrue(!b[1].enabled)
        assertEquals("Acme One Pro → Harman IE 2019", r.name)
        assertTrue(r.measurement != null && r.target != null)
    }

    @Test
    fun `targets from squig links and files`() {
        val web = FakeWeb(site + mapOf("https://files.example/My%20Target.csv" to "frequency,raw\n" + fr(1.0).replace('\t', ',')))
        assertEquals("Harman IE 2019 Target", LinkImport.importTarget("https://example.squig.link/?share=Harman_IE_2019_Target", web).name)
        assertEquals("Harman IE 2019 Target", LinkImport.importTarget("https://example.squig.link/?share=Acme_One_Pro,Harman_IE_2019_Target", web).name)
        assertEquals("Harman IE 2019 Target", LinkImport.importTarget("https://example.squig.link/?eqModel=Acme_One_Pro&eqTarget=Harman_IE_2019&eqFilters=x", web).name)
        assertEquals(81.0, LinkImport.importTarget("https://files.example/My%20Target.csv", web).at(1000.0), 0.01)
        val noTarget = assertFailsWith<ImportException> { LinkImport.importTarget("https://example.squig.link/?share=Acme_One_Pro", web) }
        assertTrue("no target" in noTarget.message!!)
        val onlyTarget = assertFailsWith<ImportException> { LinkImport.import("https://example.squig.link/?share=Harman_IE_2019_Target", web) }
        assertTrue("Import target from a link" in onlyTarget.message!!)
    }

    @Test
    fun `legacy JSON eqFilters are read`() {
        val bands = LinkImport.decodeEqFilters(Base64.getUrlEncoder().encodeToString("""[[0,0,250,1,-2],{"d":0,"t":"HSQ","f":9000,"q":0.7,"g":3}]""".toByteArray()))
        assertEquals(2, bands.size); assertEquals(FilterType.HIGH_SHELF, bands[1]!!.type)
    }

    @Test
    fun `AutoEQ GitHub file and folder links`() {
        val raw = "https://raw.githubusercontent.com/jaakkopasanen/AutoEq/master/results/crinacle/711/Acme%20One/Acme%20One%20ParametricEQ.txt"
        val web = FakeWeb(mapOf(raw to "Preamp: -6.2 dB\nFilter 1: ON LSC Fc 105 Hz Gain 5.5 dB Q 0.70\nFilter 2: ON PK Fc 3000 Hz Gain -2 dB Q 1.4\n"))
        val a = LinkImport.import("https://github.com/jaakkopasanen/AutoEq/blob/master/results/crinacle/711/Acme%20One/Acme%20One%20ParametricEQ.txt", web)
        a as LinkImport.Result.Filters
        assertEquals("Acme One", a.name); assertEquals(2, a.import.eq.bands.size)
        val b = LinkImport.import("https://github.com/jaakkopasanen/AutoEq/tree/master/results/crinacle/711/Acme%20One", web)
        assertEquals("Acme One", (b as LinkImport.Result.Filters).name)
    }

    @Test
    fun `plain links to a measurement and helpful errors`() {
        val web = FakeWeb(mapOf("https://files.example/iem.csv" to "frequency,raw\n" + fr(0.0).replace('\t', ',')))
        assertTrue(LinkImport.import("https://files.example/iem.csv", web) is LinkImport.Result.Measurement)
        assertFailsWith<ImportException> { LinkImport.import("https://autoeq.app/?x=1", web) }
        assertFailsWith<ImportException> { LinkImport.import("http://files.example/iem.csv", web) }
        assertFailsWith<ImportException> { LinkImport.import("https://files.example/missing.txt", web) }
        val e = assertFailsWith<ImportException> { LinkImport.import("https://example.squig.link/?share=Nope", FakeWeb(site)) }
        assertTrue("Nope" in e.message!!)
    }

    @Test
    fun `links are found in shared text but not in filter text`() {
        assertEquals("https://x.squig.link/?share=A,B", LinkImport.findLink("Super* Review https://x.squig.link/?share=A,B"))
        assertNull(LinkImport.findLink("Preamp: -3 dB\nFilter 1: ON PK Fc 100 Hz Gain 1 dB Q 1"))
        assertNull(LinkImport.findLink("just words"))
        assertEquals("https://github.com/a/b/tree/master/Moondrop%20Aria%20(2021)", LinkImport.findLink("https://github.com/a/b/tree/master/Moondrop%20Aria%20(2021)"))
        assertEquals("https://x.squig.link/?share=A", LinkImport.findLink("(see https://x.squig.link/?share=A)."))
    }

    /** Against the real squig.link; run with BPEQ_LIVE=1 (not part of the normal build). */
    @Test
    fun `live squig link`() {
        if (System.getenv("BPEQ_LIVE") != "1") return
        val fetch = app.bpeq.core.io.HttpFetch { println("GET $it") }
        val r = LinkImport.import("https://squig.link/?share=Acoustune_RS_One,Harman_IE_2019_Target", fetch)
        r as LinkImport.Result.Measurement
        println("LIVE ${r.measurement.name} ${r.measurement.freqs.size} pts, target ${r.target?.name} ${r.target?.freqs?.size} pts ${r.notes}")
        assertTrue(r.target != null)
        val aria = "https://github.com/jaakkopasanen/AutoEq/tree/master/results/crinacle/711%20in-ear/Moondrop%20Aria%20(2021)"
        val a = LinkImport.import(aria, fetch) as LinkImport.Result.Filters
        println("LIVE AutoEQ folder ${a.name}: ${a.import.eq.bands.size} bands, preamp ${a.import.eq.manualPreampDb}")
        val b = LinkImport.import("https://github.com/jaakkopasanen/AutoEq/blob/master/results/crinacle/711%20in-ear/Moondrop%20Aria%20(2021)/Moondrop%20Aria%20(2021)%20ParametricEQ.txt", fetch) as LinkImport.Result.Filters
        assertEquals(a.import.eq.bands.size, b.import.eq.bands.size)
        val autoEqTarget = LinkImport.importTarget("https://github.com/jaakkopasanen/AutoEq/blob/master/targets/Harman%20in-ear%202019.csv", fetch)
        println("LIVE AutoEQ target ${autoEqTarget.name} ${autoEqTarget.freqs.size} pts")
        val squigTarget = LinkImport.importTarget("https://squig.link/?share=Harman_IE_2019_Target", fetch)
        println("LIVE squig target ${squigTarget.name} ${squigTarget.freqs.size} pts")
        // Crinacle's site refuses downloads by other apps (403); BPEQ reports that rather than working around it.
        val e = assertFailsWith<ImportException> { LinkImport.import("https://graph.hangout.audio/iem/711/?share=SA03", fetch) }
        println("LIVE hangout: ${e.message}")
    }
}
