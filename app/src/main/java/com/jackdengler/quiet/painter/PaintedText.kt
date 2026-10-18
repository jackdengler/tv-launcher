package com.jackdengler.quiet.painter

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Text the brush maintains. When [text] changes while the home screen is in front, everything
 * after the unchanged prefix fades out and the brush traces the new letters' outlines, the paint
 * appearing exactly in the letter shapes. Otherwise it just switches.
 *
 * [strokeWeight] is the font's stroke thickness as a fraction of its size; [msPerGlyph] sets the
 * pace (clamped by [minMs]/[maxMs]).
 */
@Composable
fun PaintedText(
    text: String,
    style: TextStyle,
    modifier: Modifier = Modifier,
    painterScale: Float = 1f,
    maxWidth: Int? = null,
    strokeWeight: Float = 0.14f,
    msPerGlyph: Int = 1_000,
    minMs: Int = 1_800,
    maxMs: Int = 9_000,
) {
    val painter = LocalPainter.current
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    val constraints = maxWidth?.let { Constraints(maxWidth = with(density) { it.dp.roundToPx() }) } ?: Constraints()
    fun layout(s: String) = measurer.measure(s, style, overflow = TextOverflow.Ellipsis, maxLines = 1, constraints = constraints)

    var shown by remember { mutableStateOf(text) }
    var incoming by remember { mutableStateOf<String?>(null) }
    var job by remember { mutableStateOf<PaintJob?>(null) }
    var split by remember { mutableStateOf(0f) }
    val origin = remember { FloatArray(2) }

    val old = layout(shown)
    val new = incoming?.let { layout(it) }

    LaunchedEffect(text) {
        if (text == shown) return@LaunchedEffect
        if (painter == null || !painter.active) {
            shown = text
            return@LaunchedEffect
        }
        val next = layout(text)
        val end = next.getLineEnd(0, true)
        val prefix = commonPrefix(shown, text).coerceAtMost(minOf(old.getLineEnd(0, true), end))
        if (prefix >= end) {
            shown = text
            return@LaunchedEffect
        }
        val fontPx = with(density) { style.fontSize.toPx() }
        val route = BrushRoute.fromGlyphs(next.getPathForRange(prefix, end))
        val glyphs = text.substring(prefix, end).count { !it.isWhitespace() }
        val j = PaintJob(
            origin = { Offset(origin[0], origin[1]) },
            route = route,
            stroke = fontPx * strokeWeight,
            scale = painterScale,
            durationMs = (glyphs * msPerGlyph).coerceIn(minMs, maxMs),
        )
        split = if (prefix == 0) 0f else next.getHorizontalPosition(prefix, true)
        incoming = text
        job = j
        try {
            painter.submit(j)
            withTimeoutOrNull(30_000) { j.done.await() }
        } finally {
            j.cancelled = true
            shown = text
            incoming = null
            job = null
        }
    }

    val w = maxOf(old.size.width, new?.size?.width ?: 0)
    val h = maxOf(old.size.height, new?.size?.height ?: 0)
    Canvas(
        modifier
            .size(with(density) { w.toDp() }, with(density) { h.toDp() })
            .onGloballyPositioned { val p = it.positionInRoot(); origin[0] = p.x; origin[1] = p.y },
    ) {
        val j = job
        if (j == null || new == null) {
            paint(old)
            return@Canvas
        }
        // Unchanged part stays put.
        clipRect(right = split) { paint(old) }
        // Old ending fades and smears down as the brush arrives.
        if (j.eraseProgress < 1f) {
            val e = j.eraseProgress
            clipRect(left = split) {
                translate(top = e * 10f) { paint(old, alpha = 1f - e) }
            }
        }
        // New letters appear wherever the brush has traced.
        if (j.paintProgress > 0f) {
            layer {
                clipRect(left = split - 2f) { paint(new) }
                drawPath(
                    j.route.mask(j.paintProgress), Color.Black,
                    style = Stroke(j.stroke * 1.6f, cap = StrokeCap.Round, join = StrokeJoin.Round),
                    blendMode = BlendMode.DstIn,
                )
            }
        }
    }
}

private fun commonPrefix(a: String, b: String): Int {
    var i = 0
    while (i < a.length && i < b.length && a[i] == b[i]) i++
    return i
}

/** Draws [block] into an offscreen layer so blend-mode masks only affect it. */
private inline fun DrawScope.layer(block: DrawScope.() -> Unit) {
    val canvas = drawContext.canvas
    canvas.saveLayer(Rect(Offset.Zero, size), Paint())
    block()
    canvas.restore()
}

private fun DrawScope.paint(layout: TextLayoutResult, alpha: Float = 1f) = drawText(layout, topLeft = Offset.Zero, alpha = alpha)
