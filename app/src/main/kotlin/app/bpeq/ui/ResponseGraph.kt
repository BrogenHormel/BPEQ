package app.bpeq.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.graphics.toArgb
import app.bpeq.core.dsp.FreqGrid
import app.bpeq.core.dsp.Response
import app.bpeq.core.model.Band
import app.bpeq.core.model.Eq
import kotlin.math.hypot
import kotlin.math.ln
import kotlin.math.roundToInt

/** An extra curve drawn on the graph (overlays: device curve, measurement, target, other profile...). */
data class Overlay(val db: DoubleArray, val color: Color, val dashed: Boolean = false, val freqs: DoubleArray = FreqGrid.DISPLAY.freqs, val width: Float = 2f)

private const val DB_RANGE = 12.0

/**
 * Live response graph: log frequency 20 Hz-20 kHz, +/-12 dB. Combined curve, selected band's own curve, and a node
 * per enabled band. Drag a node to move frequency/gain, pinch (two fingers) to change the selected band's Q.
 */
@Composable
fun ResponseGraph(
    eq: Eq,
    selectedId: Long?,
    modifier: Modifier = Modifier,
    overlays: List<Overlay> = emptyList(),
    onSelect: (Long?) -> Unit = {},
    onDragStart: () -> Unit = {},
    onDrag: (Long, Double, Double) -> Unit = { _, _, _ -> },
    onPinch: (Long, Double) -> Unit = { _, _ -> },
    onDragEnd: () -> Unit = {},
    interactive: Boolean = true,
    /** Minimal drawing (curve + zero line), for thumbnails. */
    compact: Boolean = !interactive,
    showNodes: Boolean = true,
    /** Bands for the combined curve if they differ from the nodes (e.g. with tone sliders blended in). */
    totalBands: List<Band>? = null,
) {
    val grid = FreqGrid.DISPLAY
    val total = remember(eq.bands, totalBands) { Response.ideal(totalBands ?: eq.bands, grid) }
    val selBand = eq.bands.firstOrNull { it.id == selectedId }
    val selCurve = remember(selBand) { selBand?.let { Response.band(it, grid) } }
    val bands by rememberUpdatedState(eq.bands)
    val selected by rememberUpdatedState(selectedId)

    val cs = MaterialTheme.colorScheme
    val gridColor = cs.outlineVariant
    val labelColor = cs.onSurfaceVariant
    val curveColor = cs.primary
    val selColor = cs.secondary

    Canvas(
        modifier.pointerInput(interactive) {
            if (!interactive) return@pointerInput
            fun xOf(f: Double) = (ln(f / 20.0) / ln(1000.0) * size.width).toFloat()
            fun fOf(x: Float) = 20.0 * Math.pow(1000.0, (x / size.width).toDouble().coerceIn(0.0, 1.0))
            fun yOf(db: Double) = ((DB_RANGE - db) / (2 * DB_RANGE) * size.height).toFloat()
            fun dbOf(y: Float) = DB_RANGE - y / size.height * 2 * DB_RANGE
            val hit = 28.dp.toPx()
            awaitEachGesture {
                val down = awaitFirstDown()
                val near = bands.filter { it.enabled }.minByOrNull {
                    hypot(xOf(it.freqHz) - down.position.x, yOf(it.gainDb.coerceIn(-DB_RANGE, DB_RANGE)) - down.position.y)
                }?.takeIf { hypot(xOf(it.freqHz) - down.position.x, yOf(it.gainDb.coerceIn(-DB_RANGE, DB_RANGE)) - down.position.y) < hit }
                var target: Band? = near ?: bands.firstOrNull { it.id == selected }
                if (near != null) onSelect(near.id)
                val start = down.position
                val startNode = near?.let { Offset(xOf(it.freqHz), yOf(it.gainDb)) }
                var prevSpan = 0f
                var moved = false
                var started = false
                while (true) {
                    val ev = awaitPointerEvent()
                    val pressed = ev.changes.filter { it.pressed }
                    if (pressed.isEmpty()) break
                    val t = target ?: continue
                    if (pressed.size >= 2) {
                        val span = (pressed[0].position - pressed[1].position).getDistance()
                        if (prevSpan > 0f && span > 0f) {
                            if (!started) { started = true; onDragStart() }
                            onPinch(t.id, (span / prevSpan).toDouble()); moved = true
                        }
                        prevSpan = span
                        ev.changes.forEach { it.consume() }
                    } else if (startNode != null) {
                        prevSpan = 0f
                        val p = pressed[0].position
                        val d = p - start
                        if (moved || d.getDistance() > viewConfiguration.touchSlop) {
                            if (!started) { started = true; onDragStart() }
                            moved = true
                            val f = fOf(startNode.x + d.x).roundToInt().toDouble()
                            val g = (dbOf(startNode.y + d.y) * 10).roundToInt() / 10.0
                            onDrag(t.id, f, g)
                            pressed[0].consume()
                        }
                    }
                }
                if (started) onDragEnd()
                if (near == null && !moved) onSelect(null)
            }
        },
    ) {
        val w = size.width; val h = size.height
        fun xOf(f: Double) = (ln(f / 20.0) / ln(1000.0) * w).toFloat()
        fun yOf(db: Double) = ((DB_RANGE - db.coerceIn(-DB_RANGE - 2, DB_RANGE + 2)) / (2 * DB_RANGE) * h).toFloat()

        if (compact) {
            drawLine(gridColor, Offset(0f, yOf(0.0)), Offset(w, yOf(0.0)), 1f)
            drawCurve(grid.freqs, total, curveColor, 2.dp.toPx(), false, ::xOf, ::yOf)
            return@Canvas
        }
        // grid
        val paint = android.graphics.Paint().apply { color = labelColor.toArgb(); textSize = 9.dp.toPx(); isAntiAlias = true }
        for (f in listOf(20.0, 50.0, 100.0, 200.0, 500.0, 1000.0, 2000.0, 5000.0, 10000.0, 20000.0)) {
            val x = xOf(f)
            drawLine(gridColor, Offset(x, 0f), Offset(x, h), 1f)
            if (f != 20000.0) drawContext.canvas.nativeCanvas.drawText(fmtFreq(f), x + 2.dp.toPx(), h - 3.dp.toPx(), paint)
        }
        var db = -12
        while (db <= 12) {
            val y = yOf(db.toDouble())
            drawLine(if (db == 0) labelColor.copy(alpha = 0.6f) else gridColor, Offset(0f, y), Offset(w, y), if (db == 0) 1.5f else 1f)
            if (db != 12 && db != -12) drawContext.canvas.nativeCanvas.drawText("${if (db > 0) "+" else ""}$db", 2.dp.toPx(), y - 2.dp.toPx(), paint)
            db += 6
        }

        overlays.forEach { o -> drawCurve(o.freqs, o.db, o.color, o.width.dp.toPx(), o.dashed, ::xOf, ::yOf) }

        selCurve?.let { c ->
            val path = curvePath(grid.freqs, c, ::xOf, ::yOf)
            val fill = Path().apply { addPath(path); lineTo(w, yOf(0.0)); lineTo(0f, yOf(0.0)); close() }
            drawPath(fill, selColor.copy(alpha = 0.18f))
            drawPath(path, selColor.copy(alpha = 0.8f), style = Stroke(1.5.dp.toPx()))
        }
        drawCurve(grid.freqs, total, curveColor, 2.5.dp.toPx(), false, ::xOf, ::yOf)

        // nodes
        if (showNodes) eq.bands.forEachIndexed { i, b ->
            val p = Offset(xOf(b.freqHz), yOf(b.gainDb))
            val sel = b.id == selectedId
            val col = if (!b.enabled) labelColor.copy(alpha = 0.4f) else if (sel) selColor else curveColor
            drawCircle(col, if (sel) 11.dp.toPx() else 8.dp.toPx(), p)
            val tp = android.graphics.Paint().apply {
                color = android.graphics.Color.BLACK; textSize = 9.dp.toPx(); isAntiAlias = true; textAlign = android.graphics.Paint.Align.CENTER
                isFakeBoldText = true
            }
            drawContext.canvas.nativeCanvas.drawText("${i + 1}", p.x, p.y + 3.dp.toPx(), tp)
        }
    }
}

private fun curvePath(freqs: DoubleArray, db: DoubleArray, xOf: (Double) -> Float, yOf: (Double) -> Float): Path = Path().apply {
    for (i in freqs.indices) {
        val x = xOf(freqs[i]); val y = yOf(db[i])
        if (i == 0) moveTo(x, y) else lineTo(x, y)
    }
}

private fun DrawScope.drawCurve(freqs: DoubleArray, db: DoubleArray, color: Color, width: Float, dashed: Boolean, xOf: (Double) -> Float, yOf: (Double) -> Float) {
    if (freqs.isEmpty() || db.size != freqs.size) return
    drawPath(curvePath(freqs, db, xOf, yOf), color, style = Stroke(width,
        pathEffect = if (dashed) PathEffect.dashPathEffect(floatArrayOf(10f, 8f)) else null))
}
