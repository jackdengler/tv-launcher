package com.jackdengler.quiet.home

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.scale
import com.jackdengler.quiet.photos.PhotoLife
import com.jackdengler.quiet.photos.Scene
import com.jackdengler.quiet.photos.Sky
import kotlin.math.PI
import kotlin.math.floor
import kotlin.math.pow
import kotlin.math.sin

/** The current time of day and weather, which decide which effects play. */
data class Mood(val scene: Scene, val sky: Sky)

/**
 * Cinemagraph-style life drawn over a photo, in the photo's own coordinates (so it rides the
 * Ken Burns move): breathing sun glow, glints on water, twinkling lights at night, gulls,
 * drifting clouds, a marine-layer fog bank and rain. [t] is seconds; [alpha] is the layer's fade.
 */
fun DrawScope.drawLife(life: PhotoLife, mood: Mood, t: Float, alpha: Float) {
    if (alpha <= 0f) return
    val w = size.width
    val h = size.height
    val night = mood.scene == Scene.NIGHT
    val horizonY = life.horizon * h

    // Breathing light around the sun (day, dawn and golden hour).
    if (!night && life.hasSun && mood.sky != Sky.RAIN) {
        val pulse = 0.10f + 0.05f * sin(t * 0.55f)
        val warm = if (mood.scene == Scene.DAY) Color(0xFFFFF6E0) else Color(0xFFFFC27A)
        drawCircle(
            Brush.radialGradient(
                listOf(warm.copy(alpha = pulse * alpha), Color.Transparent),
                center = Offset(life.sunX * w, life.sunY * h), radius = w * 0.2f,
            ),
            radius = w * 0.2f, center = Offset(life.sunX * w, life.sunY * h),
        )
    }

    // Clouds drifting across the sky.
    if (mood.sky == Sky.CLOUDY || (mood.sky == Sky.CLEAR && !night)) {
        val count = if (mood.sky == Sky.CLOUDY) 5 else 2
        val strength = if (mood.sky == Sky.CLOUDY) 0.13f else 0.06f
        val tint = if (night) Color(0xFF9AA3B5) else Color.White
        for (i in 0 until count) {
            val speed = 5f + 7f * hash(i * 3 + 1)
            val span = w * 1.8f
            val x = ((hash(i) * span + t * speed) % span) - w * 0.4f
            val y = (0.08f + hash(i + 11) * (life.horizon - 0.15f).coerceAtLeast(0.05f)) * h
            val r = w * (0.14f + 0.12f * hash(i + 23))
            scale(1f, 0.32f, pivot = Offset(x, y)) {
                drawCircle(
                    Brush.radialGradient(listOf(tint.copy(alpha = strength * alpha), Color.Transparent), Offset(x, y), r),
                    radius = r, center = Offset(x, y),
                )
            }
        }
    }

    // Marine layer: soft bands rolling along the horizon.
    if (mood.sky == Sky.FOG) {
        for (i in 0 until 3) {
            val breathe = 0.16f + 0.06f * sin(t * 0.3f + i * 2f)
            val cy = horizonY + (i - 1) * h * 0.09f + sin(t * 0.07f + i) * h * 0.02f
            val band = h * 0.22f
            drawRect(
                Brush.verticalGradient(
                    listOf(Color.Transparent, Color.White.copy(alpha = breathe * alpha), Color.Transparent),
                    startY = cy - band / 2, endY = cy + band / 2,
                ),
                topLeft = Offset(0f, cy - band / 2), size = Size(w, band),
            )
        }
    }

    // Glints on the water: brief four-point sparkles.
    if (!night && mood.sky != Sky.RAIN && mood.sky != Sky.FOG) {
        val g = life.glints
        for (k in 0 until g.size / 2) {
            val phase = hash(k + 101)
            val s = sin(t * (1.1f + 1.7f * phase) + phase * 2f * PI.toFloat())
            val a = s.coerceAtLeast(0f).pow(14) * 0.9f * alpha
            if (a < 0.02f) continue
            val c = Offset(g[k * 2] * w, g[k * 2 + 1] * h)
            val len = 5f + 7f * phase
            val color = Color.White.copy(alpha = a)
            drawLine(color, c - Offset(len, 0f), c + Offset(len, 0f), strokeWidth = 1.4f, cap = StrokeCap.Round)
            drawLine(color, c - Offset(0f, len * 0.7f), c + Offset(0f, len * 0.7f), strokeWidth = 1.4f, cap = StrokeCap.Round)
            drawCircle(color, radius = 2f, center = c)
        }
    }

    // Night: lights shimmer and bloom.
    if (night) {
        val l = life.lights
        for (k in 0 until l.size / 2) {
            val phase = hash(k + 307)
            val a = (0.25f + 0.3f * sin(t * (0.7f + 2.2f * phase) + phase * 20f)).coerceAtLeast(0f) * alpha
            val c = Offset(l[k * 2] * w, l[k * 2 + 1] * h)
            val r = 6f + 8f * phase
            val tint = if (phase > 0.5f) Color(0xFFFFE2B0) else Color(0xFFFFFFFF)
            drawCircle(Brush.radialGradient(listOf(tint.copy(alpha = a), Color.Transparent), c, r), radius = r, center = c)
        }
    }

    // Gulls: now and then a few cross the sky, wings beating.
    if (!night && mood.sky != Sky.RAIN) {
        val period = 34f
        val n = floor(t / period).toInt()
        val roll = hash(n * 7 + 3)
        val u = (t - n * period) / 15f
        if (roll < 0.65f && u in 0f..1f) {
            val leftToRight = hash(n + 5) > 0.5f
            val x0 = if (leftToRight) -0.05f * w else 1.05f * w
            val x1 = if (leftToRight) 1.05f * w else -0.05f * w
            val y = (0.1f + hash(n + 9) * (life.horizon - 0.2f).coerceAtLeast(0.08f)) * h
            val birds = 1 + (roll * 10).toInt() % 3
            val ink = (if (mood.scene == Scene.DAY) Color(0xFF2E3238) else Color(0xFF1C1A1F)).copy(alpha = 0.7f * alpha)
            for (b in 0 until birds) {
                val bx = x0 + (x1 - x0) * u + (if (leftToRight) -1 else 1) * b * 34f
                val by = y + b * 14f * (if (b % 2 == 0) 1 else -1) + sin(t * 0.8f + b) * 6f
                val flap = sin(t * 8f + b * 1.7f)
                val span = 11f - b
                val path = Path().apply {
                    moveTo(bx - span, by - flap * 3f)
                    quadraticBezierTo(bx - span / 2, by - span * 0.55f * (0.4f + flap), bx, by)
                    quadraticBezierTo(bx + span / 2, by - span * 0.55f * (0.4f + flap), bx + span, by - flap * 3f)
                }
                drawPath(path, ink, style = Stroke(width = 2.2f, cap = StrokeCap.Round))
            }
        }
    }

    // Rain streaks.
    if (mood.sky == Sky.RAIN) {
        val color = Color.White.copy(alpha = 0.2f * alpha)
        for (i in 0 until 150) {
            val speed = 700f + 500f * hash(i + 500)
            val x = (hash(i + 600) * w + t * 60f) % w
            val y = (hash(i + 700) * h + t * speed) % h
            drawLine(color, Offset(x, y), Offset(x - 6f, y + 24f), strokeWidth = 1.3f, cap = StrokeCap.Round)
        }
    }
}

/** Deterministic pseudo-random 0..1 for an index. */
private fun hash(i: Int): Float {
    val v = sin(i * 12.9898) * 43758.5453
    return (v - floor(v)).toFloat()
}
