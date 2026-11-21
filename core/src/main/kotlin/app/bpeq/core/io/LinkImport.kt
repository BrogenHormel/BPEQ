package app.bpeq.core.io

import app.bpeq.core.model.Band
import app.bpeq.core.model.Eq
import app.bpeq.core.model.FilterType
import app.bpeq.core.model.PreampMode
import app.bpeq.core.tune.Curve
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.net.URI
import java.net.URLDecoder
import java.util.Base64

/**
 * Imports from a web link (the only part of BPEQ that uses the network, and only when the user imports a link):
 *  - AutoEQ on GitHub: a ParametricEQ.txt (blob or raw) or a result folder -> filters.
 *  - squig.link / graph.hangout.audio (CrinGraph "graphtool" sites):
 *    `?share=IEM_Name,Target_Name_Target` -> the IEM's measurement (L/R averaged) and target, for auto-tune;
 *    `?eqModel=..&eqTarget=..&eqFilters=..` (EQ tab links) -> the filters, plus measurement/target for the overlays.
 *  - Any other https link to a filter file or a frequency-response file.
 * Network access goes through [Fetch] so this stays testable.
 */
object LinkImport {
    /** Returns the body for a 2xx response, null for 404/410 (missing file); throws on other failures. */
    fun interface Fetch { fun text(url: String): String? }

    sealed interface Result {
        /** Filters to save as a profile; [measurement]/[target] (when known) become its graph overlays. */
        data class Filters(val name: String?, val import: ImportResult, val measurement: Curve?, val target: Curve?) : Result
        /** A measurement for auto-tune; [target] null = keep the user's target choice. */
        data class Measurement(val measurement: Curve, val target: Curve?, val notes: List<String>) : Result
    }

    private val URL_RE = Regex("""https?://[^\s<>"']+""", RegexOption.IGNORE_CASE)

    /** The link in [text] if it is just a link (possibly with a page title around it, as browsers share), else null. */
    fun findLink(text: String): String? {
        val t = text.trim()
        if (t.length > 4000 || ApoText.looksLikeApo(t)) return null
        val links = URL_RE.findAll(t).map { trimTrailing(it.value) }.toList()
        return links.singleOrNull()
    }

    /** Drops sentence punctuation after a link, and a closing bracket only if it has no partner in the link. */
    private fun trimTrailing(link: String): String {
        var s = link
        while (s.isNotEmpty()) {
            val c = s.last()
            s = when {
                c == '.' || c == ',' || c == ';' || c == ':' || c == '!' || c == '?' -> s.dropLast(1)
                c == ')' && s.count { it == '(' } < s.count { it == ')' } -> s.dropLast(1)
                c == ']' && s.count { it == '[' } < s.count { it == ']' } -> s.dropLast(1)
                else -> return s
            }
        }
        return s
    }

    private fun parseLink(link: String): URI {
        val uri = runCatching { URI(link.trim().replace(" ", "%20")) }.getOrNull()
            ?: throw ImportException("Not a valid link")
        if (uri.scheme?.lowercase() != "https") throw ImportException("Only https links can be imported")
        if (uri.host == null) throw ImportException("Not a valid link")
        return uri
    }

    private fun isGraphTool(query: Map<String, String>) =
        listOf("share", "eqFilters", "eqModel", "eq_model", "eqTarget", "eq_target").any { it in query }

    /**
     * A target curve from a link: the target in a squig.link link (`share=..._Target`, or an EQ link's `eqTarget`),
     * or a link to a curve file (e.g. an AutoEQ target CSV on GitHub).
     */
    fun importTarget(link: String, fetch: Fetch): Curve {
        val uri = parseLink(link)
        val query = queryParams(uri.rawQuery)
        if (isGraphTool(query)) {
            val site = Site(dataDir(uri, fetch), fetch)
            val eqTarget = (query["eqTarget"] ?: query["eq_target"])?.let(::shareName)
            val name = eqTarget?.let { if (it.endsWith(" Target")) it else "$it Target" }
                ?: query["share"]?.split(',')?.map(::shareName)?.firstOrNull { it.endsWith(" Target") }
                ?: throw ImportException("That link has no target in it. Select a target on the site, then share the link again.")
            return site.target(name) ?: eqTarget?.let(site::target)
                ?: throw ImportException("Couldn't find the target \"$name\" on ${uri.host}")
        }
        return when (val r = import(link, fetch)) {
            is Result.Measurement -> r.measurement
            is Result.Filters -> throw ImportException("That link has EQ filters, not a target curve")
        }
    }

    fun import(link: String, fetch: Fetch): Result {
        val uri = parseLink(link)
        val host = uri.host.lowercase()
        val query = queryParams(uri.rawQuery)
        return when {
            host == "autoeq.app" || host.endsWith(".autoeq.app") -> throw ImportException(
                "autoeq.app computes the EQ in your browser, so there's no file to fetch. On autoeq.app choose " +
                    "\"Equalizer APO ParametricEQ\", copy the filters and use Paste from clipboard. " +
                    "Links to AutoEQ results on GitHub work directly.")
            host == "github.com" -> gitHub(uri, fetch)
            isGraphTool(query) -> graphTool(uri, query, fetch)
            else -> plainFile(uri, fetch)
        }
    }

    // ---------------------------------------------------------------- AutoEQ / GitHub

    private fun gitHub(uri: URI, fetch: Fetch): Result {
        // /{owner}/{repo}/blob|tree/{ref}/{path...}
        val seg = uri.rawPath.split('/').filter { it.isNotEmpty() } // still percent-encoded, as the raw URL needs
        if (seg.size < 5 || seg[2] !in setOf("blob", "tree", "raw")) {
            throw ImportException("Link to a file or a result folder on GitHub (e.g. an AutoEQ ParametricEQ.txt)")
        }
        val (owner, repo, kind) = seg
        val rest = seg.drop(3) // ref + path (ref without slashes, as GitHub links to branches/commits normally are)
        val raw = "https://raw.githubusercontent.com/$owner/$repo/" + rest.joinToString("/")
        if (kind == "tree") {
            // An AutoEQ result folder: .../results/<source>/<rig>/<Name>  ->  <Name> ParametricEQ.txt in it.
            val name = decode(rest.last())
            val file = "$raw/" + encodeSegment("$name ParametricEQ.txt")
            val text = fetch.text(file) ?: throw ImportException("No \"$name ParametricEQ.txt\" in that folder")
            return Result.Filters(name, EqImport.parse(text), null, null)
        }
        return fromText(fetch.text(raw) ?: throw ImportException("File not found on GitHub"), decode(rest.last()))
    }

    // ---------------------------------------------------------------- plain file links

    private fun plainFile(uri: URI, fetch: Fetch): Result {
        val text = fetch.text(uri.toASCIIString()) ?: throw ImportException("Nothing found at that link")
        if (text.trimStart().startsWith("<")) throw ImportException("That link is a web page, not an EQ or measurement file")
        return fromText(text, uri.path.substringAfterLast('/'))
    }

    /** A fetched file: filters if it parses as EQ text, else a frequency response. */
    private fun fromText(text: String, fileName: String): Result {
        val name = EqImport.nameFromFile(fileName)
        runCatching { EqImport.parse(text) }.onSuccess { return Result.Filters(name, it, null, null) }
        val curve = runCatching { Curve.parse(text, name?.removeSuffix(" L")?.removeSuffix(" R") ?: "Measurement") }
            .getOrElse { throw ImportException("The file is neither EQ filters nor a frequency response") }
        return Result.Measurement(curve, null, emptyList())
    }

    // ---------------------------------------------------------------- squig.link and other graphtool sites

    private class Site(val dataBase: URI, val fetch: Fetch) {
        private var phoneBook: JsonArray? = null

        fun file(stem: String): String = dataBase.resolve(encodeSegment(stem)).toASCIIString()

        /** Measurement of [stem]: L and R averaged (whichever exist), or the stem itself. */
        fun measurement(stem: String, display: String): Curve? {
            val curves = listOf(" L", " R").mapNotNull { ch -> fetch.text(file("$stem$ch.txt"))?.let { parse(it, display) } }
                .ifEmpty { listOf(" L1", " R1", "").mapNotNull { ch -> fetch.text(file("$stem$ch.txt"))?.let { parse(it, display) } } }
            if (curves.isEmpty()) return null
            if (curves.size == 1) return curves[0]
            val a = curves[0]; val b = curves[1]
            return Curve(display, a.freqs, DoubleArray(a.freqs.size) { (a.db[it] + b.at(a.freqs[it])) / 2 })
        }

        fun target(stem: String): Curve? = fetch.text(file("$stem.txt"))?.let { parse(it, stem) }

        private fun parse(text: String, name: String) = runCatching { Curve.parse(text, name) }.getOrNull()

        /** "Brand Model" (as EQ-tab links name an IEM) -> file stem, from phone_book.json. */
        fun stemForFullName(fullName: String): String? {
            val book = phoneBook ?: runCatching {
                Json.parseToJsonElement(fetch.text(file("phone_book.json")) ?: return null).jsonArray
            }.getOrNull()?.also { phoneBook = it } ?: return null
            val want = norm(fullName)
            for (brand in book) {
                val b = brand as? JsonObject ?: continue
                val brandName = b["name"]?.jsonPrimitive?.contentOrNull ?: ""
                for (p in b["phones"]?.jsonArray ?: continue) {
                    val (name, files) = when (p) {
                        is JsonPrimitive -> p.content to listOf(p.content)
                        is JsonObject -> {
                            val n = p["name"]?.jsonPrimitive?.contentOrNull ?: continue
                            n to when (val f = p["file"]) {
                                is JsonArray -> f.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
                                is JsonPrimitive -> listOf(f.content)
                                else -> listOf(n)
                            }
                        }
                        else -> continue
                    }
                    if (norm("$brandName $name") == want || norm(name) == want || files.any { norm(it) == want }) return files.firstOrNull()
                }
            }
            return null
        }
    }

    private fun graphTool(uri: URI, query: Map<String, String>, fetch: Fetch): Result {
        val site = Site(dataDir(uri, fetch), fetch)
        val notes = ArrayList<String>()

        val filtersParam = query["eqFilters"] ?: query["eq_filters"]
        if (filtersParam != null) {
            val bands = decodeEqFilters(filtersParam)
            if (bands.isEmpty()) throw ImportException("The link's EQ has no filters")
            val model = (query["eqModel"] ?: query["eq_model"])?.let(::shareName)
            val targetName = (query["eqTarget"] ?: query["eq_target"])?.let(::shareName)
            val meas = model?.let { m -> (site.stemForFullName(m) ?: m).let { site.measurement(it, m) } }
            val target = targetName?.let { t -> site.target(if (t.endsWith(" Target")) t else "$t Target") ?: site.target(t) }
            val name = listOfNotNull(model, targetName?.removeSuffix(" Target")).joinToString(" → ").ifEmpty { null }
            val skipped = bands.size - bands.count { it != null }
            if (skipped > 0) notes += "$skipped filter(s) of unsupported type skipped"
            if (model != null && meas == null) notes += "IEM measurement not found, so no graph overlays"
            return Result.Filters(name, ImportResult(Eq(bands.filterNotNull(), PreampMode.AUTO), "squig.link EQ link", notes), meas, target)
        }

        val names = (query["share"] ?: query["eqModel"] ?: query["eq_model"] ?: "").split(',').map(::shareName).filter { it.isNotEmpty() }
        val (targets, phones) = names.partition { it.endsWith(" Target") }
        if (phones.isEmpty()) throw ImportException("That link only has a target in it. To use it, tap \"Import target from a link\" in Tune.")
        if (phones.size > 1) notes += "The link has ${phones.size} IEMs; loaded the first, ${phones[0]}"
        val stem = phones[0]
        val meas = site.measurement(stem, stem)
            ?: site.stemForFullName(stem)?.let { site.measurement(it, stem) }
            ?: throw ImportException("Couldn't find the measurement for \"$stem\" on ${uri.host}")
        val target = targets.firstOrNull()?.let { t -> site.target(t).also { if (it == null) notes += "Target \"$t\" not found; keeping your target" } }
        return Result.Measurement(meas, target, notes)
    }

    /** The data folder: config.js's `DIR = "..."` (default "data/"), relative to the page. */
    private fun dataDir(page: URI, fetch: Fetch): URI {
        val pageDir = URI(page.scheme, page.authority, page.path.substringBeforeLast('/') + "/", null, null)
        val config = runCatching { fetch.text(pageDir.resolve("config.js").toASCIIString()) }.getOrNull()
        val dir = config?.let { Regex("""\bDIR\s*=\s*["']([^"']*)["']""").find(it)?.groupValues?.get(1) } ?: "data/"
        return pageDir.resolve(if (dir.endsWith("/")) dir else "$dir/")
    }

    /** squig.link encodes spaces as "_" in share names. */
    private fun shareName(s: String) = s.trim().replace('_', ' ').replace(Regex("""\s+"""), " ")

    private fun norm(s: String) = s.lowercase().replace('_', ' ').replace(Regex("""\s+"""), " ").trim()

    /**
     * `eqFilters`: base64url of "v2;dis,type,freq,q,gain;..." (type 0 PK, 1 LSQ, 2 HSQ), or a legacy JSON array of
     * rows / {d,t,f,q,g}. Unknown types come back as null.
     */
    fun decodeEqFilters(param: String): List<Band?> {
        val b64 = param.trim().replace('-', '+').replace('_', '/').let { it + "=".repeat((4 - it.length % 4) % 4) }
        val text = runCatching { String(Base64.getDecoder().decode(b64), Charsets.UTF_8) }
            .getOrElse { throw ImportException("The link's EQ filters can't be read") }
        fun band(disabled: Boolean, type: FilterType?, f: Double, q: Double, g: Double) =
            type?.let { Band(it, f, g, if (q > 0) q else ApoText.DEFAULT_Q, !disabled) }
        fun typeIx(ix: Int) = when (ix) { 1 -> FilterType.LOW_SHELF; 2 -> FilterType.HIGH_SHELF; else -> FilterType.PEAK }
        if (text.startsWith("v2;")) {
            return text.removePrefix("v2;").split(';').filter { it.isNotBlank() }.map { row ->
                val p = row.split(',')
                band(p.getOrNull(0)?.toDoubleOrNull()?.let { it != 0.0 } ?: false, typeIx(p.getOrNull(1)?.toDoubleOrNull()?.toInt() ?: 0),
                    p.getOrNull(2)?.toDoubleOrNull() ?: 0.0, p.getOrNull(3)?.toDoubleOrNull() ?: 0.0, p.getOrNull(4)?.toDoubleOrNull() ?: 0.0)
            }.filter { it == null || it.freqHz > 0 }
        }
        val arr = runCatching { Json.parseToJsonElement(text).jsonArray }.getOrElse { throw ImportException("The link's EQ filters can't be read") }
        fun num(e: kotlinx.serialization.json.JsonElement?) = (e as? JsonPrimitive)?.contentOrNull?.toDoubleOrNull() ?: 0.0
        fun flag(e: kotlinx.serialization.json.JsonElement?) = (e as? JsonPrimitive)?.contentOrNull.let { it == "true" || (it?.toDoubleOrNull() ?: 0.0) != 0.0 }
        return arr.map { x ->
            when (x) {
                is JsonObject -> band(flag(x["d"]), ApoText.typeOf(x["t"]?.jsonPrimitive?.contentOrNull ?: "PK"), num(x["f"]), num(x["q"]), num(x["g"]))
                is JsonArray -> band(flag(x.getOrNull(0)), typeIx(num(x.getOrNull(1)).toInt()), num(x.getOrNull(2)), num(x.getOrNull(3)), num(x.getOrNull(4)))
                else -> null
            }
        }.filter { it == null || it.freqHz > 0 }
    }

    private fun queryParams(raw: String?): Map<String, String> = raw.orEmpty().split('&').filter { '=' in it }.associate {
        val k = it.substringBefore('='); val v = it.substringAfter('=')
        // Keep '+' literal (base64 / names); decode %XX, twice if the link was double-encoded.
        fun dec(s: String) = runCatching { URLDecoder.decode(s.replace("+", "%2B"), "UTF-8") }.getOrDefault(s)
        dec(k) to dec(v).let { if (Regex("%[0-9A-Fa-f]{2}").containsMatchIn(it)) dec(it) else it }
    }

    private fun decode(s: String) = runCatching { URLDecoder.decode(s.replace("+", "%2B"), "UTF-8") }.getOrDefault(s)

    /**
     * A relative path, percent-encoded (spaces, '?', '#', '%'); non-ASCII is encoded later by toASCIIString().
     * ':' is encoded too, or "Foo: Bar.txt" would be read as a URI scheme when resolved.
     */
    private fun encodeSegment(s: String): String = URI(null, null, s, null).rawPath.replace(":", "%3A")
}
