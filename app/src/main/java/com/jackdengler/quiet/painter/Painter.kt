package com.jackdengler.quiet.painter

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.VectorConverter
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.math.hypot

/**
 * One repaint: scrub out the old text in [region] (local px of the text), then paint the new
 * text in with zigzag brush strokes. The text draws itself from [eraseProgress]/[paintProgress];
 * the painter moves its brush along the same strokes in screen coordinates.
 */
class PaintJob(
    val origin: () -> Offset,
    val region: Rect,
    /** Size of the painter for this text (1 = clock). */
    val scale: Float,
    val erase: Boolean,
) {
    var eraseProgress by mutableFloatStateOf(0f)
    var paintProgress by mutableFloatStateOf(0f)
    @Volatile var cancelled = false
    val done = CompletableDeferred<Unit>()

    /** Brush path through the region: [ROWS] passes, alternating direction. */
    val strokes: List<Offset> = zigzag(region)
    val rowHeight: Float get() = region.height / ROWS

    companion object {
        const val ROWS = 3

        fun zigzag(r: Rect): List<Offset> = (0 until ROWS).flatMap { i ->
            val y = r.top + r.height * (i + 0.5f) / ROWS
            if (i % 2 == 0) listOf(Offset(r.left, y), Offset(r.right, y)) else listOf(Offset(r.right, y), Offset(r.left, y))
        }
    }
}

/** A drop of paint falling off the brush. */
class Drip(val at: Offset, val born: Float, val size: Float)

val LocalPainter = staticCompositionLocalOf<PainterState?> { null }

/**
 * The little painter. Jobs queue up; he drops in on his rope, scrubs and repaints each one,
 * and climbs back out of frame when there's nothing left to do.
 */
@Stable
class PainterState {
    /** Brush tip in screen px. */
    var tip by mutableStateOf(Offset(-400f, -400f))
        private set
    var visible by mutableStateOf(false)
        private set
    var scale by mutableFloatStateOf(1f)
        private set
    var painting by mutableStateOf(false)
        private set
    var scrubbing by mutableStateOf(false)
        private set

    /** True while Quiet's home screen is in front; text changes are painted only then. */
    var active by mutableStateOf(false)

    /** Seconds; set by the overlay's frame clock. */
    var time by mutableFloatStateOf(0f)
    val drips = ArrayDeque<Drip>()

    private val queue = Channel<PaintJob>(Channel.UNLIMITED)
    private var current: PaintJob? = null

    fun submit(job: PaintJob) {
        queue.trySend(job)
    }

    suspend fun run() {
        try {
            while (true) {
                var job: PaintJob? = queue.receive()
                while (job != null) {
                    current = job
                    if (!job.cancelled) perform(job)
                    job.done.complete(Unit)
                    current = null
                    job = withTimeoutOrNull(NEXT_JOB_WAIT_MS) { queue.receive() }
                }
                leave()
            }
        } finally {
            // Interrupted (Quiet left the screen): let every waiting text switch right away.
            current?.done?.complete(Unit)
            current = null
            while (true) queue.tryReceive().getOrNull()?.done?.complete(Unit) ?: break
            visible = false
            painting = false
            scrubbing = false
        }
    }

    private suspend fun perform(job: PaintJob) {
        val o = job.origin()
        val path = job.strokes.map { it + o }
        scale = job.scale
        if (!visible) {
            tip = Offset(path.first().x + 140f * scale, -180f * scale)
            visible = true
        }
        travel(path.first(), TRAVEL_MS)
        if (job.erase) {
            scrubbing = true
            sweep(path.reversed(), ERASE_MS) { job.eraseProgress = it }
            scrubbing = false
        } else {
            job.eraseProgress = 1f
        }
        painting = true
        val length = job.region.width * PaintJob.ROWS
        val ms = (length * 0.9f).toInt().coerceIn(PAINT_MIN_MS, PAINT_MAX_MS)
        var lastDrip = 0f
        sweep(path, ms) { p ->
            job.paintProgress = p
            if (p - lastDrip > 0.22f) {
                lastDrip = p
                drips.addLast(Drip(tip, time, 2.2f * scale))
                while (drips.size > 12) drips.removeFirst()
            }
        }
        painting = false
    }

    private suspend fun leave() {
        travel(Offset(tip.x + 60f * scale, -200f * scale), LEAVE_MS)
        visible = false
    }

    private suspend fun travel(to: Offset, ms: Int) {
        Animatable(tip, Offset.VectorConverter).animateTo(to, tween(ms, easing = FastOutSlowInEasing)) { tip = value }
    }

    private suspend fun sweep(points: List<Offset>, ms: Int, onProgress: (Float) -> Unit) {
        Animatable(0f).animateTo(1f, tween(ms, easing = LinearEasing)) {
            tip = pointAt(points, value)
            onProgress(value)
        }
    }

    private companion object {
        const val TRAVEL_MS = 650
        const val ERASE_MS = 520
        const val PAINT_MIN_MS = 900
        const val PAINT_MAX_MS = 2_600
        const val LEAVE_MS = 800
        const val NEXT_JOB_WAIT_MS = 900L
    }
}

/** The point at fraction [p] of the way along a polyline. */
fun pointAt(points: List<Offset>, p: Float): Offset {
    if (points.size < 2) return points.firstOrNull() ?: Offset.Zero
    val lengths = points.zipWithNext { a, b -> hypot(b.x - a.x, b.y - a.y) }
    var remaining = lengths.sum() * p.coerceIn(0f, 1f)
    for (i in lengths.indices) {
        val l = lengths[i]
        if (remaining <= l || i == lengths.lastIndex) {
            val f = if (l == 0f) 0f else (remaining / l).coerceIn(0f, 1f)
            return points[i] + (points[i + 1] - points[i]) * f
        }
        remaining -= l
    }
    return points.last()
}

/** The polyline from the start to fraction [p] of the way along, as a path for masking. */
fun partialPath(points: List<Offset>, p: Float): androidx.compose.ui.graphics.Path {
    val path = androidx.compose.ui.graphics.Path()
    if (points.isEmpty() || p <= 0f) return path
    path.moveTo(points[0].x, points[0].y)
    val end = pointAt(points, p)
    val lengths = points.zipWithNext { a, b -> hypot(b.x - a.x, b.y - a.y) }
    var remaining = lengths.sum() * p.coerceIn(0f, 1f)
    for (i in lengths.indices) {
        if (remaining <= lengths[i]) break
        remaining -= lengths[i]
        path.lineTo(points[i + 1].x, points[i + 1].y)
    }
    path.lineTo(end.x, end.y)
    return path
}
