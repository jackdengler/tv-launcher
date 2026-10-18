package com.jackdengler.quiet.painter

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.lerp
import kotlin.math.cos
import kotlin.math.sin

/** Draws the brush over the whole screen while it is working. */
@Composable
fun PainterOverlay(painter: PainterState, paint: () -> Color) {
    val time = remember { mutableFloatStateOf(0f) }
    if (painter.visible) {
        LaunchedEffect(Unit) {
            val start = withFrameNanos { it }
            while (true) withFrameNanos { time.floatValue = (it - start) / 1e9f }
        }
    }
    Canvas(Modifier.fillMaxSize()) {
        if (!painter.visible) return@Canvas
        drawBrush(painter.tip, painter.scale, painter.lift, painter.heading, time.floatValue, paint())
    }
}

// Brush geometry along its axis (px at scale 1), from the bristle tip toward the handle end.
private const val BRISTLE = 46f
private const val FERRULE = 30f
private const val HANDLE = 190f
private const val HALF = 8f

/**
 * A round artist's brush resting at about 40°, bristle tip at [tip]: paint-loaded bristles,
 * a crimped metal ferrule and a lacquered handle, each shaded as a cylinder, with a cast
 * shadow that grows and softens as the brush lifts.
 */
private fun DrawScope.drawBrush(tip: Offset, s: Float, lift: Float, heading: Float, t: Float, paint: Color) {
    // Rest angle (handle up and to the right) with a slight lean into the stroke and a tiny tremor.
    val lean = sin(heading) * 6f + cos(heading) * 3f
    val angle = -40f + lean * (1f - lift) + sin(t * 5.3f) * 0.6f
    val raise = lift * 14f * s

    // Cast shadow: the same silhouette, offset down-right, darker when close to the surface.
    val shadowOffset = Offset(10f * s + lift * 26f * s, 12f * s + lift * 30f * s)
    for (k in 0 until 3) {
        val spread = 1f + k * 0.18f + lift * 0.25f
        translate(shadowOffset.x, shadowOffset.y) {
            rotate(angle, pivot = tip) {
                drawSilhouette(tip, s * spread, Color.Black.copy(alpha = (0.10f - lift * 0.04f) / (k + 1)))
            }
        }
    }

    translate(0f, -raise) {
        rotate(angle, pivot = tip) {
            translate(tip.x, tip.y) {
                // Bristles: a tapered tuft, deepest colour at the tip, wet highlight along the top.
                val tuft = Path().apply {
                    moveTo(0f, 0f)
                    cubicTo(10f * s, -3.5f * s, 26f * s, -HALF * s, BRISTLE * s, -HALF * 0.92f * s)
                    lineTo(BRISTLE * s, HALF * 0.92f * s)
                    cubicTo(26f * s, HALF * s, 10f * s, 3.5f * s, 0f, 0f)
                    close()
                }
                val hair = Color(0xFF6B5641)
                drawPath(
                    tuft,
                    Brush.horizontalGradient(
                        0f to lerp(paint, Color.Black, 0.25f),
                        0.45f to paint,
                        0.75f to lerp(paint, hair, 0.65f),
                        1f to hair,
                        startX = 0f, endX = BRISTLE * s,
                    ),
                )
                drawPath(
                    tuft,
                    Brush.verticalGradient(
                        0f to Color.Black.copy(alpha = 0.35f),
                        0.35f to Color.Transparent,
                        0.55f to Color.White.copy(alpha = 0.18f),
                        1f to Color.Black.copy(alpha = 0.4f),
                        startY = -HALF * s, endY = HALF * s,
                    ),
                )
                drawLine(Color.White.copy(alpha = 0.35f), Offset(6f * s, -1.6f * s), Offset(30f * s, -4.6f * s), 1.2f * s, StrokeCap.Round)

                // Ferrule: brushed nickel with two crimps.
                val fx = BRISTLE * s
                val ferrule = Brush.verticalGradient(
                    0f to Color(0xFF55595D), 0.22f to Color(0xFFDDE1E4), 0.4f to Color(0xFFF7F8F9),
                    0.62f to Color(0xFF9AA0A5), 1f to Color(0xFF4A4E52),
                    startY = -HALF * s, endY = HALF * s,
                )
                drawRoundRect(ferrule, Offset(fx, -HALF * s), Size(FERRULE * s, HALF * 2 * s), CornerRadius(2f * s))
                for (cx in listOf(0.62f, 0.78f)) {
                    drawLine(
                        Color.Black.copy(alpha = 0.35f), Offset(fx + FERRULE * s * cx, -HALF * s),
                        Offset(fx + FERRULE * s * cx, HALF * s), 1.3f * s,
                    )
                }

                // Handle: lacquered, swelling slightly then tapering to a rounded end.
                val hx = fx + FERRULE * s
                val handle = Path().apply {
                    moveTo(hx, -HALF * 0.9f * s)
                    cubicTo(hx + 50f * s, -HALF * 1.05f * s, hx + 110f * s, -HALF * 0.85f * s, hx + HANDLE * s, -HALF * 0.55f * s)
                    quadraticBezierTo(hx + (HANDLE + 7f) * s, 0f, hx + HANDLE * s, HALF * 0.55f * s)
                    cubicTo(hx + 110f * s, HALF * 0.85f * s, hx + 50f * s, HALF * 1.05f * s, hx, HALF * 0.9f * s)
                    close()
                }
                drawPath(
                    handle,
                    Brush.verticalGradient(
                        0f to Color(0xFF2A0D0D), 0.25f to Color(0xFF8E2A22), 0.38f to Color(0xFFD9785E),
                        0.5f to Color(0xFF9A3326), 1f to Color(0xFF240A0A),
                        startY = -HALF * s, endY = HALF * s,
                    ),
                )
                // Specular streak along the lacquer.
                drawLine(
                    Color.White.copy(alpha = 0.45f), Offset(hx + 8f * s, -3.4f * s), Offset(hx + HANDLE * 0.82f * s, -2.4f * s),
                    1.1f * s, StrokeCap.Round,
                )
            }
        }
    }
}

private fun DrawScope.drawSilhouette(tip: Offset, s: Float, color: Color) {
    val len = (BRISTLE + FERRULE + HANDLE) * s
    drawRoundRect(color, Offset(tip.x, tip.y - HALF * s), Size(len, HALF * 2 * s), CornerRadius(HALF * s))
}
