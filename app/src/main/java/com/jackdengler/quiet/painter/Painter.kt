package com.jackdengler.quiet.painter

import android.graphics.PathMeasure
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
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.asAndroidPath
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.math.atan2
import kotlin.math.hypot

/**
 * The brush's route for one repaint: every contour of every new glyph, in reading order, with
 * pen-up moves between them. [progress] 0..1 runs along the whole route; the text reveals only
 * what the brush has traced, so the paint follows the real letter shapes.
 */
class BrushRoute(val contours: List<List<Offset>>) {
    private class Segment(val points: List<Offset>, val painted: Boolean, val start: Float, val length: Float)

    private val segments: List<Segment>
    val totalLength: Float
    val paintedLength: Float

    init {
        val list = ArrayList<Segment>()
        var at = 0f
        var last: Offset? = null
        var painted = 0f
        for (c in contours) {
            if (c.size < 2) continue
            last?.let { from ->
                val hop = listOf(from, c.first())
                val len = polyLength(hop) * LIFT_WEIGHT
                list += Segment(hop, false, at, len)
                at += len
            }
            val len = polyLength(c)
            list += Segment(c, true, at, len)
            at += len
            painted += len
            last = c.last()
        }
        segments = list
        totalLength = at
        paintedLength = painted
    }

    val start: Offset get() = contours.firstOrNull()?.firstOrNull() ?: Offset.Zero

    /** Brush position, whether it is lifted, and its direction of travel (radians) at [p]. */
    fun at(p: Float): Triple<Offset, Boolean, Float> {
        if (segments.isEmpty()) return Triple(start, true, 0f)
        val d = totalLength * p.coerceIn(0f, 1f)
        val seg = segments.lastOrNull { it.start <= d } ?: segments.first()
        val local = ((d - seg.start) / (if (seg.painted) 1f else LIFT_WEIGHT)).coerceAtLeast(0f)
        val (point, dir) = along(seg.points, local)
        return Triple(point, !seg.painted, dir)
    }

    // Completed contours are cached so each frame only re-traces the contour in progress.
    private val done = Path()
    private var doneCount = 0

    /** Mask of everything painted up to [p]. */
    fun mask(p: Float): Path {
        val d = totalLength * p.coerceIn(0f, 1f)
        while (doneCount < segments.size && segments[doneCount].start + segments[doneCount].length <= d) {
            val s = segments[doneCount]
            if (s.painted) addPolyline(done, s.points, Float.MAX_VALUE)
            doneCount++
        }
        val out = Path().apply { addPath(done) }
        segments.getOrNull(doneCount)?.takeIf { it.painted }?.let { s -> addPolyline(out, s.points, d - s.start) }
        return out
    }

    companion object {
        /** Pen-up moves take time too, but less than painting the same distance. */
        private const val LIFT_WEIGHT = 0.45f
        private const val SAMPLE_PX = 2.5f

        /** Samples the outlines of [glyphs] (a text path) into polylines, one per contour. */
        fun fromGlyphs(glyphs: Path): BrushRoute {
            val measure = PathMeasure(glyphs.asAndroidPath(), false)
            val out = ArrayList<List<Offset>>()
            val pos = FloatArray(2)
            do {
                val len = measure.length
                if (len < 1f) continue
                val n = (len / SAMPLE_PX).toInt().coerceAtLeast(2)
                out += List(n + 1) { i ->
                    measure.getPosTan(len * i / n, pos, null)
                    Offset(pos[0], pos[1])
                }
            } while (measure.nextContour())
            return BrushRoute(out)
        }

        private fun polyLength(p: List<Offset>) = p.zipWithNext { a, b -> hypot(b.x - a.x, b.y - a.y) }.sum()

        private fun along(points: List<Offset>, distance: Float): Pair<Offset, Float> {
            var remaining = distance
            for (i in 0 until points.size - 1) {
                val a = points[i]
                val b = points[i + 1]
                val l = hypot(b.x - a.x, b.y - a.y)
                if (remaining <= l || i == points.size - 2) {
                    val f = if (l == 0f) 0f else (remaining / l).coerceIn(0f, 1f)
                    return (a + (b - a) * f) to atan2(b.y - a.y, b.x - a.x)
                }
                remaining -= l
            }
            return points.last() to 0f
        }

        private fun addPolyline(path: Path, points: List<Offset>, maxDistance: Float) {
            path.moveTo(points[0].x, points[0].y)
            var remaining = maxDistance
            for (i in 1 until points.size) {
                val a = points[i - 1]
                val b = points[i]
                val l = hypot(b.x - a.x, b.y - a.y)
                if (remaining < l) {
                    val e = a + (b - a) * (remaining / l)
                    path.lineTo(e.x, e.y)
                    return
                }
                remaining -= l
                path.lineTo(b.x, b.y)
            }
        }
    }
}

/**
 * One repaint. The old text after the unchanged prefix fades out while the brush arrives, then
 * the brush traces [route] (local px of the text) and the new text appears where it has painted.
 */
class PaintJob(
    val origin: () -> Offset,
    val route: BrushRoute,
    /** Width of the painted trail; about the font's stroke weight. */
    val stroke: Float,
    /** Brush size (1 = clock). */
    val scale: Float,
    val durationMs: Int,
) {
    var eraseProgress by mutableFloatStateOf(0f)
    var paintProgress by mutableFloatStateOf(0f)
    @Volatile var cancelled = false
    val done = CompletableDeferred<Unit>()
}

val LocalPainter = staticCompositionLocalOf<PainterState?> { null }

/**
 * The brush. Jobs queue up; it glides in, traces each one, and glides back out of frame when
 * there's nothing left to do.
 */
@Stable
class PainterState {
    /** Bristle tip in screen px. */
    var tip by mutableStateOf(Offset(-400f, -400f))
        private set

    /** 0 = touching the surface, 1 = lifted high (bigger, softer shadow). */
    var lift by mutableFloatStateOf(1f)
        private set

    /** Direction of travel (radians), for a slight lean into the stroke. */
    var heading by mutableFloatStateOf(0f)
        private set
    var visible by mutableStateOf(false)
        private set
    var scale by mutableFloatStateOf(1f)
        private set

    /** True while Quiet's home screen is in front; text changes are painted only then. */
    var active by mutableStateOf(false)

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
        }
    }

    private suspend fun perform(job: PaintJob) {
        val o = job.origin()
        scale = job.scale
        val start = job.route.start + o
        if (!visible) {
            tip = Offset(start.x + 420f * scale, start.y - 360f * scale)
            lift = 1f
            visible = true
        }
        // Glide to the first stroke while the old text fades.
        coroutineScope {
            launch {
                Animatable(0f).animateTo(1f, tween(ARRIVE_MS, easing = FastOutSlowInEasing)) { job.eraseProgress = value }
            }
            travel(start, ARRIVE_MS)
        }
        job.eraseProgress = 1f
        Animatable(lift).animateTo(0f, tween(TOUCH_MS)) { lift = value }
        Animatable(0f).animateTo(1f, tween(job.durationMs, easing = LinearEasing)) {
            val (p, lifted, dir) = job.route.at(value)
            tip = p + o
            heading = dir
            lift += ((if (lifted) 0.35f else 0f) - lift) * 0.25f
            job.paintProgress = value
        }
        Animatable(lift).animateTo(1f, tween(TOUCH_MS)) { lift = value }
    }

    private suspend fun leave() {
        travel(Offset(tip.x + 380f * scale, tip.y - 420f * scale), LEAVE_MS)
        visible = false
    }

    private suspend fun travel(to: Offset, ms: Int) {
        Animatable(tip, Offset.VectorConverter).animateTo(to, tween(ms, easing = FastOutSlowInEasing)) { tip = value }
    }

    private companion object {
        const val ARRIVE_MS = 1_300
        const val TOUCH_MS = 350
        const val LEAVE_MS = 1_600
        const val NEXT_JOB_WAIT_MS = 1_500L
    }
}
