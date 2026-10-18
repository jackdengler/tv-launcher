package com.jackdengler.quiet.painter

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import kotlin.math.sin

/** Draws the painter (and his drips) over the whole screen. Costs nothing while he's away. */
@Composable
fun PainterOverlay(painter: PainterState, paint: () -> Color) {
    LaunchedEffect(painter) {
        val start = withFrameNanos { it }
        while (true) withFrameNanos { painter.time = (it - start) / 1e9f }
    }
    Canvas(Modifier.fillMaxSize()) {
        val t = painter.time
        drawDrips(painter, t, paint())
        if (painter.visible) drawPainter(painter.tip, painter.scale, t, painter.painting, painter.scrubbing, paint())
    }
}

private fun DrawScope.drawDrips(painter: PainterState, t: Float, paint: Color) {
    val it = painter.drips.iterator()
    while (it.hasNext()) {
        val d = it.next()
        val age = t - d.born
        if (age > 1.4f || age < 0f) continue
        val fall = minOf(age * 26f, 16f) * (d.size / 2.2f)
        val a = (1f - age / 1.4f).coerceIn(0f, 1f)
        drawCircle(paint.copy(alpha = 0.85f * a), radius = d.size, center = d.at + Offset(0f, fall))
        drawLine(paint.copy(alpha = 0.6f * a), d.at, d.at + Offset(0f, fall), strokeWidth = d.size * 0.9f, cap = StrokeCap.Round)
    }
}

private val Skin = Color(0xFFF1C7A0)
private val Smock = Color(0xFFF5F0E6)
private val Beret = Color(0xFFB4374A)
private val Trousers = Color(0xFF39404F)
private val Wood = Color(0xFF8A5A2E)
private val Rope = Color(0xFFE9E4D8)
private val Ink = Color(0xFF2B2420)

/**
 * A little painter in a beret and smock, hanging from a rope like a window washer, holding a
 * long brush whose bristles are at [tip]. [s] scales him; [t] (seconds) animates the sway.
 */
private fun DrawScope.drawPainter(tip: Offset, s: Float, t: Float, painting: Boolean, scrubbing: Boolean, paint: Color) {
    val sway = sin(t * 2.1f) * 2.5f * s
    val jiggle = if (painting || scrubbing) sin(t * 22f) * 1.6f * s else 0f
    val hand = tip + Offset(20f * s, -22f * s + jiggle)
    val shoulder = hand + Offset(15f * s + sway * 0.3f, 9f * s)
    val neck = shoulder + Offset(4f * s, -3f * s)
    val head = neck + Offset(1f * s, -13f * s)
    val hip = neck + Offset(2f * s, 28f * s)
    fun o(x: Float, y: Float) = Offset(x * s, y * s)

    // Rope from the top of the screen to his harness.
    drawLine(Rope.copy(alpha = 0.85f), Offset(head.x + 3f * s, 0f), neck + o(3f, -2f), strokeWidth = 1.6f * s)

    // Soft shadow so he reads on bright photos.
    drawCircle(Color.Black.copy(alpha = 0.18f), radius = 30f * s, center = neck + o(2f, 12f))

    // Legs, dangling.
    val swing = sin(t * 1.7f) * 0.22f
    for ((dx, phase) in listOf(-5f to 0f, 5f to 1.4f)) {
        val top = hip + o(dx, 0f)
        val foot = top + o(sin(swing + phase * 0.3f) * 6f, 15f)
        drawLine(Trousers, top, foot, strokeWidth = 5f * s, cap = StrokeCap.Round)
        drawOval(Ink, topLeft = foot + o(-3f, -1.5f), size = Size(8f * s, 4f * s))
    }

    // Smock, with paint smudges in today's colour.
    val smock = Path().apply {
        moveTo(neck.x - 9f * s, neck.y)
        lineTo(neck.x + 9f * s, neck.y)
        lineTo(hip.x + 12f * s, hip.y + 2f * s)
        lineTo(hip.x - 12f * s, hip.y + 2f * s)
        close()
    }
    drawPath(smock, Smock)
    drawCircle(paint.copy(alpha = 0.9f), radius = 2.2f * s, center = neck + o(-3f, 12f))
    drawCircle(paint.copy(alpha = 0.7f), radius = 1.6f * s, center = neck + o(5f, 20f))

    // Paint bucket on the free arm.
    val bucketHand = neck + o(15f, 16f)
    drawLine(Smock, neck + o(7f, 3f), bucketHand, strokeWidth = 4f * s, cap = StrokeCap.Round)
    val bucket = Path().apply {
        moveTo(bucketHand.x + 1f * s, bucketHand.y + 2f * s)
        lineTo(bucketHand.x + 13f * s, bucketHand.y + 2f * s)
        lineTo(bucketHand.x + 11f * s, bucketHand.y + 13f * s)
        lineTo(bucketHand.x + 3f * s, bucketHand.y + 13f * s)
        close()
    }
    drawPath(bucket, Color(0xFFA9B0B8))
    drawLine(paint, bucketHand + o(1f, 2.5f), bucketHand + o(13f, 2.5f), strokeWidth = 2.6f * s, cap = StrokeCap.Round)

    // Brush arm and brush.
    drawLine(Smock, neck + o(-6f, 3f), hand, strokeWidth = 4f * s, cap = StrokeCap.Round)
    drawCircle(Skin, radius = 2.6f * s, center = hand)
    drawLine(Wood, hand + o(3f, -3f), tip + o(5f, -5f), strokeWidth = 2.6f * s, cap = StrokeCap.Round)
    drawLine(Color(0xFFC9CDD2), tip + o(6f, -6f), tip + o(4f, -4f), strokeWidth = 3.4f * s, cap = StrokeCap.Round)
    if (scrubbing) {
        drawCircle(Color.White.copy(alpha = 0.9f), radius = 5f * s, center = tip)
        drawCircle(Color(0xFFDADADA), radius = 3f * s, center = tip + o(1.5f, 1.5f))
    } else {
        drawLine(paint, tip + o(4f, -4f), tip, strokeWidth = 5f * s, cap = StrokeCap.Round)
    }

    // Head, face and beret.
    drawCircle(Skin, radius = 10f * s, center = head)
    drawCircle(Ink, radius = 1.3f * s, center = head + o(-4f, 0f))
    drawCircle(Ink, radius = 1.3f * s, center = head + o(3f, 0f))
    drawArc(
        Ink, startAngle = 20f, sweepAngle = 140f, useCenter = false,
        topLeft = head + o(-4f, 1f), size = Size(7f * s, 5f * s), style = Stroke(1.2f * s, cap = StrokeCap.Round),
    )
    drawCircle(Color(0xFFE79A8C).copy(alpha = 0.6f), radius = 1.8f * s, center = head + o(-6.5f, 3f))
    rotate(-12f, pivot = head) {
        drawOval(Beret, topLeft = head + o(-11f, -14f), size = Size(22f * s, 9f * s))
        drawCircle(Beret, radius = 1.8f * s, center = head + o(1f, -14.5f))
    }
}
