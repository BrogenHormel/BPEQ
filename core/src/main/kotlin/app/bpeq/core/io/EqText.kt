package app.bpeq.core.io

import app.bpeq.core.model.Band
import app.bpeq.core.model.Eq
import app.bpeq.core.model.FilterType
import app.bpeq.core.model.PreampMode
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.math.BigDecimal
import java.math.RoundingMode
import kotlin.math.abs

/** What an importer produced: the EQ plus notes on anything it changed or skipped. */
data class ImportResult(val eq: Eq, val format: String, val notes: List<String> = emptyList())

class ImportException(message: String) : Exception(message)

/**
 * Parametric EQ text: Equalizer APO / Peace, AutoEQ ParametricEQ.txt, squig.link and graph.hangout.audio exports.
 * ```
 * Preamp: -6.1 dB
 * Filter 1: ON PK Fc 105 Hz Gain -2.5 dB Q 0.70
 * ```
 * Adapted from Contour's ApoText (Apache-2.0, https://github.com/Iwakura9/contour).
 * Types PK/PEQ, LS/LSC/LSQ, HS/HSC/HSQ; OFF = disabled band; lines not understood are skipped; decimal comma ok.
 * squig.link writes `Channel: L` / `Channel: R` blocks: a block for both channels wins, else the left one.
 */
object ApoText {
    private const val NUM = """[-+]?\d+(?:[.,]\d+)?"""
    private val PREAMP = Regex("""^\s*preamp\s*:\s*($NUM)\s*(?:db)?\s*$""", RegexOption.IGNORE_CASE)
    private val FILTER = Regex("""^\s*filter\s*\d*\s*:\s*(on|off)\s+([a-z]+)\s*(.*)$""", RegexOption.IGNORE_CASE)
    private val FC = Regex("""\bfc\s*($NUM)\s*(?:hz)?""", RegexOption.IGNORE_CASE)
    private val GAIN = Regex("""\bgain\s*($NUM)\s*(?:db)?""", RegexOption.IGNORE_CASE)
    private val Q = Regex("""\bq\s*($NUM)""", RegexOption.IGNORE_CASE)
    private val CHANNEL = Regex("""^\s*channel\s*:\s*(.*)$""", RegexOption.IGNORE_CASE)

    const val DEFAULT_Q = 0.707

    fun typeOf(code: String): FilterType? = when (code.uppercase()) {
        "PK", "PEQ" -> FilterType.PEAK
        "LS", "LSC", "LSQ" -> FilterType.LOW_SHELF
        "HS", "HSC", "HSQ" -> FilterType.HIGH_SHELF
        else -> null
    }

    fun codeOf(t: FilterType) = when (t) {
        FilterType.PEAK -> "PK"
        FilterType.LOW_SHELF -> "LSC"
        FilterType.HIGH_SHELF -> "HSC"
    }

    private fun num(s: String) = s.replace(',', '.').toDouble()

    private class Block(val side: String) {
        var preamp: Double? = null
        val bands = ArrayList<Band>()
        var skippedTypes = 0
    }

    private fun side(spec: String): String {
        val w = spec.uppercase().split(Regex("""[\s,]+""")).filter { it.isNotEmpty() }
        val l = w.any { it in setOf("L", "FL", "LEFT", "1") }
        val r = w.any { it in setOf("R", "FR", "RIGHT", "2") }
        return when { "ALL" in w || (l && r) -> "both"; l -> "L"; r -> "R"; else -> "other" }
    }

    fun looksLikeApo(text: String) = text.lineSequence().any { FILTER.containsMatchIn(it) }

    fun parse(text: String): ImportResult {
        val blocks = ArrayList<Block>()
        var block = Block("both").also { blocks += it }
        for (line in text.lineSequence()) {
            val ch = CHANNEL.matchEntire(line)
            if (ch != null) { block = Block(side(ch.groupValues[1])).also { blocks += it }; continue }
            val pre = PREAMP.matchEntire(line)
            if (pre != null) { block.preamp = num(pre.groupValues[1]); continue }
            val m = FILTER.matchEntire(line) ?: continue
            val type = typeOf(m.groupValues[2])
            if (type == null) { block.skippedTypes++; continue }
            val rest = m.groupValues[3]
            val fc = FC.find(rest)?.groupValues?.get(1)?.let(::num) ?: continue
            val gain = GAIN.find(rest)?.groupValues?.get(1)?.let(::num) ?: 0.0
            val q = Q.find(rest)?.groupValues?.get(1)?.let(::num) ?: DEFAULT_Q
            block.bands += Band(type, fc, gain, q, m.groupValues[1].equals("on", true))
        }
        val notes = ArrayList<String>()
        val both = blocks.filter { it.side == "both" && it.bands.isNotEmpty() }
        val left = blocks.filter { it.side == "L" && it.bands.isNotEmpty() }
        val right = blocks.filter { it.side == "R" && it.bands.isNotEmpty() }
        val chosen = when {
            both.isNotEmpty() -> both
            left.isNotEmpty() -> left.also { if (right.isNotEmpty()) notes += "Left channel imported (file has separate L/R)" }
            right.isNotEmpty() -> right
            else -> blocks.filter { it.bands.isNotEmpty() }
        }
        if (chosen.isEmpty()) throw ImportException("No filters found. Expected lines like \"Filter 1: ON PK Fc 100 Hz Gain -2 dB Q 1\".")
        val skipped = blocks.sumOf { it.skippedTypes }
        if (skipped > 0) notes += "$skipped filter(s) of unsupported type skipped (only PK, LS, HS)"
        var bands = chosen.flatMap { it.bands }
        // Files from squig.link / hangout often pad unused slots with 0 dB filters: drop them.
        val zero = bands.count { abs(it.gainDb) < 1e-9 }
        if (zero in 1 until bands.size) { bands = bands.filter { abs(it.gainDb) >= 1e-9 }; notes += "$zero empty (0 dB) filter(s) skipped" }
        val preamp = chosen.lastOrNull { it.preamp != null }?.preamp ?: blocks.first().preamp
        val eq = if (preamp == null) Eq(bands, PreampMode.AUTO) else Eq(bands, PreampMode.MANUAL, preamp)
        return ImportResult(eq, "Equalizer APO / AutoEQ / squig.link", notes)
    }

    private fun fmt(x: Double, minDec: Int, maxDec: Int): String {
        var d = BigDecimal.valueOf(x).setScale(maxDec, RoundingMode.HALF_UP).stripTrailingZeros()
        if (d.scale() < minDec) d = d.setScale(minDec)
        return d.toPlainString()
    }

    /** Equalizer APO / Peace text (CRLF), squig.link-compatible. */
    fun format(bands: List<Band>, preampDb: Double): String = buildString {
        append("Preamp: ").append(fmt(preampDb, 1, 2)).append(" dB\r\n")
        bands.forEachIndexed { i, b ->
            append("Filter ${i + 1}: ").append(if (b.enabled) "ON " else "OFF ").append(codeOf(b.type))
            append(" Fc ").append(fmt(b.freqHz, 0, 2)).append(" Hz Gain ").append(fmt(b.gainDb, 1, 2))
            append(" dB Q ").append(fmt(b.q, 2, 3)).append("\r\n")
        }
    }
}

/** EQ by Ear session JSON: `{"bands":[{"type":"PK","fc":100,"gain":-2,"q":1,"enabled":true}],"preampDb":-3,"preampAuto":false}`. */
object EqByEarJson {
    @Serializable private data class FileBand(val type: String, val fc: Double, val gain: Double = 0.0, val q: Double = ApoText.DEFAULT_Q, val enabled: Boolean = true)
    @Serializable private data class FileSession(val bands: List<FileBand> = emptyList(), val preampDb: Double? = null, val preampAuto: Boolean = false)

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    fun looksLike(text: String) = text.trimStart().startsWith("{") && "\"bands\"" in text

    fun parse(text: String): ImportResult {
        val s = runCatching { json.decodeFromString(FileSession.serializer(), text) }
            .getOrElse { throw ImportException("Not valid EQ by Ear JSON: ${it.message?.lineSequence()?.firstOrNull()}") }
        var skipped = 0
        val bands = s.bands.mapNotNull { b ->
            val t = ApoText.typeOf(b.type) ?: run { skipped++; return@mapNotNull null }
            Band(t, b.fc, b.gain, b.q, b.enabled)
        }
        if (bands.isEmpty()) throw ImportException("The JSON has no PK/LS/HS bands.")
        val eq = if (s.preampAuto || s.preampDb == null) Eq(bands, PreampMode.AUTO) else Eq(bands, PreampMode.MANUAL, s.preampDb)
        return ImportResult(eq, "EQ by Ear JSON", if (skipped > 0) listOf("$skipped band(s) of unsupported type skipped") else emptyList())
    }
}

/** Detects the format and imports. */
object EqImport {
    fun parse(text: String): ImportResult {
        val t = text.removePrefix("﻿")
        return when {
            EqByEarJson.looksLike(t) -> EqByEarJson.parse(t)
            ApoText.looksLikeApo(t) -> ApoText.parse(t)
            else -> throw ImportException("Unrecognised format. Supported: Equalizer APO / Peace, AutoEQ, squig.link, graph.hangout.audio text, EQ by Ear JSON.")
        }
    }

    /** Profile name from a file name: no extension, no squig.link " Filters" / AutoEQ " ParametricEQ" suffix. */
    fun nameFromFile(fileName: String?): String? {
        var n = fileName?.substringAfterLast('/')?.trim() ?: return null
        n = n.substringBeforeLast('.', n).trim()
        for (suffix in listOf(" Filters", " ParametricEQ", " ParametricEq")) if (n.endsWith(suffix, true)) n = n.dropLast(suffix.length)
        return n.trim().ifEmpty { null }
    }
}
