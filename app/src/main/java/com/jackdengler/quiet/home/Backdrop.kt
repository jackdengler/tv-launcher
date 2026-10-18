package com.jackdengler.quiet.home

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.EaseInOutSine
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import com.jackdengler.quiet.photos.LoadedPhoto
import com.jackdengler.quiet.ui.DefaultAccent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlin.random.Random

private const val CROSSFADE_MS = 1800
private const val KEN_BURNS_MS = 45_000
private const val ZOOM_FROM = 1.04f
private const val ZOOM_TO = 1.14f

/** Max drift as a fraction of the screen; stays inside the 2% margin that 1.04× zoom gives. */
private const val MAX_DRIFT = 0.018f

class BackdropLayer(val photo: LoadedPhoto, private val drift: Offset) {
    val alpha = Animatable(0f)
    val progress = Animatable(0f)
    var zoomJob: Job? = null

    /** Current Ken Burns zoom and drift (px) for a screen of [screen] size. */
    fun transform(screen: Size): Pair<Float, Offset> {
        val p = progress.value
        return (ZOOM_FROM + (ZOOM_TO - ZOOM_FROM) * p) to Offset(drift.x * screen.width * p, drift.y * screen.height * p)
    }
}

/**
 * The rotating photo backdrop. Holds up to two layers while crossfading. Both the full-screen
 * photo and the frosted tiles draw from here with the same Ken Burns transform, so the frost
 * lines up with the photo behind it. All animation values are read in the draw phase only.
 */
@Stable
class BackdropState {
    val layers = mutableStateListOf<BackdropLayer>()
    var accent by mutableStateOf(DefaultAccent)
        private set
    /** Photo attribution for the visible photo (empty when none is needed). */
    var credit by mutableStateOf("")
        private set
    var dotCount by mutableIntStateOf(0)
    var dotIndex by mutableIntStateOf(0)

    /** Screen size in px, captured by the backdrop canvas. */
    var screen = Size.Zero
        private set

    /** Time of day and weather for the living effects. */
    var mood by mutableStateOf(Mood(com.jackdengler.quiet.photos.Scene.DAY, com.jackdengler.quiet.photos.Sky.CLEAR))

    /** Seconds since the backdrop started; drives the living effects. Read in draw only. */
    internal val clock = androidx.compose.runtime.mutableFloatStateOf(0f)

    /** True while the live camera covers the photo: frosted glass then uses a plain dark tint. */
    var liveCover by mutableStateOf(false)

    /** Night/ambient treatment for the photo (dim, desaturate). Read in draw. */
    var filter by mutableStateOf<ColorFilter?>(null)

    /** Shows [photo] with a crossfade. Animations run in [scope] and stop with it. */
    suspend fun show(photo: LoadedPhoto, scope: CoroutineScope) {
        if (layers.lastOrNull()?.photo?.key == photo.key) return
        val layer = BackdropLayer(photo, Offset(Random.nextFloat() * 2 - 1, Random.nextFloat() * 2 - 1) * MAX_DRIFT)
        layers.add(layer)
        accent = photo.accent
        credit = photo.credit
        layer.zoomJob = scope.launch {
            layer.progress.animateTo(
                1f, infiniteRepeatable(tween(KEN_BURNS_MS, easing = EaseInOutSine), RepeatMode.Reverse),
            )
        }
        // The very first photo fades in from black quickly; later ones crossfade slowly.
        layer.alpha.animateTo(1f, tween(if (layers.size == 1) 700 else CROSSFADE_MS))
        while (layers.size > 1 && layers.first() !== layer) layers.removeAt(0).zoomJob?.cancel()
    }

    /** Resumes Ken Burns on the visible layer after the scope that ran it was cancelled. */
    fun resume(scope: CoroutineScope) {
        val layer = layers.lastOrNull() ?: return
        if (layer.zoomJob?.isActive == true) return
        layer.zoomJob = scope.launch {
            val remaining = ((1f - layer.progress.value) * KEN_BURNS_MS).toInt().coerceAtLeast(1)
            layer.progress.animateTo(1f, tween(remaining, easing = EaseInOutSine))
            layer.progress.animateTo(
                0f, infiniteRepeatable(tween(KEN_BURNS_MS, easing = EaseInOutSine), RepeatMode.Reverse),
            )
        }
    }

    /**
     * Draws the photo layers as if this DrawScope were positioned at [origin] (in screen px).
     * [frost] selects the small pre-blurred copy used by tiles and pills.
     */
    fun DrawScope.drawPhotos(origin: Offset = Offset.Zero, frost: Boolean = false) {
        if (frost && liveCover) {
            drawRect(Color(0xFF1B212B).copy(alpha = 0.62f))
            return
        }
        val target = if (screen == Size.Zero) size else screen
        val dst = IntSize(target.width.toInt(), target.height.toInt())
        for (layer in layers) {
            val (scale, shift) = layer.transform(target)
            val image = if (frost) layer.photo.frost else layer.photo.image
            withTransform({
                translate(shift.x - origin.x, shift.y - origin.y)
                scale(scale, scale, pivot = Offset(target.width / 2, target.height / 2))
            }) {
                drawImage(
                    image,
                    srcOffset = IntOffset.Zero,
                    srcSize = IntSize(image.width, image.height),
                    dstSize = dst,
                    alpha = layer.alpha.value,
                    colorFilter = filter,
                    filterQuality = FilterQuality.Low,
                )
                if (!frost) drawLife(layer.photo.life, mood, clock.floatValue, layer.alpha.value)
            }
        }
    }

    internal fun capture(size: Size) {
        screen = size
    }
}

/** Night look: brightness ~0.5 and partial desaturation, scaled by [t] (0..1). */
fun nightFilter(t: Float): ColorFilter? {
    if (t <= 0f) return null
    val m = android.graphics.ColorMatrix().apply { setSaturation(1f - 0.55f * t) }
    val b = 1f - 0.5f * t
    m.postConcat(android.graphics.ColorMatrix().apply { setScale(b, b, b, 1f) })
    return ColorFilter.colorMatrix(androidx.compose.ui.graphics.ColorMatrix(m.array))
}

/** Full-screen photo with the two scrims: radial from the top-left, gradient at the bottom. */
@Composable
fun Backdrop(
    state: BackdropState,
    modifier: Modifier = Modifier,
    scrim: () -> Float = { 1f },
    amber: () -> Float = { 0f },
    /** Drawn between the photo and the scrims (the live camera). */
    live: @Composable () -> Unit = {},
) {
    // A frame clock for the living effects; it pauses whenever Quiet isn't visible.
    androidx.compose.runtime.LaunchedEffect(state) {
        val start = androidx.compose.runtime.withFrameNanos { it }
        while (true) androidx.compose.runtime.withFrameNanos { state.clock.floatValue = (it - start) / 1e9f }
    }
    androidx.compose.foundation.layout.Box(modifier.fillMaxSize()) {
        Canvas(Modifier.fillMaxSize()) {
            state.capture(size)
            with(state) { drawPhotos() }
        }
        live()
        Scrims(scrim, amber)
    }
}

@Composable
private fun Scrims(scrim: () -> Float, amber: () -> Float) {
    Canvas(Modifier.fillMaxSize()) {
        val warm = amber()
        if (warm > 0f) drawRect(Color(0xFFFF9440).copy(alpha = 0.16f * warm))
        val dark = scrim()
        drawRect(
            Brush.radialGradient(
                0f to Color.Black.copy(alpha = 0.55f * dark),
                1f to Color.Transparent,
                center = Offset.Zero,
                radius = size.width * 0.62f,
            ),
        )
        drawRect(
            Brush.verticalGradient(
                0f to Color.Transparent,
                1f to Color.Black.copy(alpha = 0.62f * dark),
                startY = size.height * 0.5f,
                endY = size.height,
            ),
        )
    }
}
