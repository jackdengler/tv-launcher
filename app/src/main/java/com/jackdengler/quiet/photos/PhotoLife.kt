package com.jackdengler.quiet.photos

import android.graphics.Bitmap
import kotlin.math.abs

/**
 * What can move in a photo, found once per photo on a background thread from its small copy.
 * All coordinates are normalized (0..1). [glints]/[lights] are packed x,y pairs.
 */
class PhotoLife(
    /** Approximate horizon (sky above, land/sea below). */
    val horizon: Float,
    /** Small bright spots below the horizon: sun on water, wet sand, chrome. */
    val glints: FloatArray,
    /** Every small bright spot: lamps, the Ferris wheel, windows at night. */
    val lights: FloatArray,
    /** The brightest point in the sky when it's the sun or its glow, else null. */
    val sunX: Float,
    val sunY: Float,
) {
    val hasSun get() = sunX >= 0f

    companion object {
        val EMPTY = PhotoLife(0.5f, FloatArray(0), FloatArray(0), -1f, -1f)
        private const val MAX_SPOTS = 90

        fun analyze(bmp: Bitmap): PhotoLife {
            val w = bmp.width
            val h = bmp.height
            val px = IntArray(w * h)
            bmp.getPixels(px, 0, w, 0, 0, w, h)
            val lum = FloatArray(w * h) { i ->
                val c = px[i]
                (0.2126f * ((c shr 16) and 0xFF) + 0.7152f * ((c shr 8) and 0xFF) + 0.0722f * (c and 0xFF)) / 255f
            }

            // Horizon: the strongest change in row brightness between 30% and 75% of the height.
            val rows = FloatArray(h) { y -> (0 until w).sumOf { x -> lum[y * w + x].toDouble() }.toFloat() / w }
            var horizon = (h * 0.5f).toInt()
            var best = 0f
            for (y in (h * 0.3f).toInt() until (h * 0.75f).toInt()) {
                val d = abs(rows[(y - 3).coerceAtLeast(0)] - rows[(y + 3).coerceAtMost(h - 1)])
                if (d > best) { best = d; horizon = y }
            }

            // Bright local maxima, strongest first, kept a few pixels apart.
            val sorted = lum.copyOf().also { it.sort() }
            val threshold = maxOf(0.72f, sorted[(sorted.size * 0.992f).toInt().coerceAtMost(sorted.size - 1)])
            val candidates = ArrayList<Int>()
            for (y in 1 until h - 1) for (x in 1 until w - 1) {
                val i = y * w + x
                val v = lum[i]
                if (v < threshold) continue
                if (v >= lum[i - 1] && v >= lum[i + 1] && v >= lum[i - w] && v >= lum[i + w] &&
                    v >= lum[i - w - 1] && v >= lum[i - w + 1] && v >= lum[i + w - 1] && v >= lum[i + w + 1]
                ) candidates += i
            }
            candidates.sortByDescending { lum[it] }
            val kept = ArrayList<Int>()
            for (i in candidates) {
                if (kept.size >= MAX_SPOTS) break
                val x = i % w
                val y = i / w
                if (kept.none { abs(it % w - x) < 4 && abs(it / w - y) < 4 }) kept += i
            }
            fun pack(list: List<Int>) = FloatArray(list.size * 2) { k ->
                val i = list[k / 2]
                if (k % 2 == 0) (i % w + 0.5f) / w else (i / w + 0.5f) / h
            }

            // Sun: the brightest sky pixel, if it is really bright.
            var sun = -1
            for (i in 0 until horizon * w) if (sun < 0 || lum[i] > lum[sun]) sun = i
            val hasSun = sun >= 0 && lum[sun] > 0.93f

            return PhotoLife(
                horizon = horizon / h.toFloat(),
                glints = pack(kept.filter { it / w > horizon }),
                lights = pack(kept),
                sunX = if (hasSun) (sun % w + 0.5f) / w else -1f,
                sunY = if (hasSun) (sun / w + 0.5f) / h else -1f,
            )
        }
    }
}
