package com.jackdengler.quiet.home

import android.graphics.Bitmap
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.Paint
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.jackdengler.quiet.ui.LocalAccent
import com.jackdengler.quiet.ui.tvClickable

const val TILE_SIZE = 120
const val TILE_RADIUS = 20
const val TILE_GAP = 22
private const val FOCUS_MS = 280
private const val GLOW_PAD = 36

/**
 * A frosted-glass tile: the pre-blurred photo cropped to the tile's screen position, a light
 * veil, and on focus a near-white fill, 1.1× scale and an accent glow (280 ms).
 * [soft] (0..1) tones everything down for night mode.
 */
@Composable
fun FrostTile(
    backdrop: BackdropState,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    onLongClick: (() -> Unit)? = null,
    onFocus: (Boolean) -> Unit = {},
    soft: () -> Float = { 0f },
    content: @Composable (focused: Boolean) -> Unit,
) {
    val accent = LocalAccent.current
    var focused by remember { mutableStateOf(false) }
    val f by animateFloatAsState(if (focused) 1f else 0f, tween(FOCUS_MS), label = "focus")
    val origin = remember { FloatArray(2) }
    val glow = rememberGlow()
    val padPx = with(LocalDensity.current) { GLOW_PAD.dp.toPx() }

    Box(
        modifier
            .size(TILE_SIZE.dp)
            .onGloballyPositioned { val p = it.positionInRoot(); origin[0] = p.x; origin[1] = p.y }
            .graphicsLayer {
                val s = 1f + 0.1f * f
                scaleX = s
                scaleY = s
            }
            .drawBehind {
                if (f > 0f) {
                    drawImage(
                        glow,
                        dstOffset = IntOffset(-padPx.toInt(), -padPx.toInt()),
                        dstSize = IntSize((size.width + 2 * padPx).toInt(), (size.height + 2 * padPx).toInt()),
                        alpha = f * (0.85f - 0.35f * soft()),
                        colorFilter = ColorFilter.tint(accent.value),
                    )
                }
            }
            .clip(RoundedCornerShape(TILE_RADIUS.dp))
            .drawBehind {
                with(backdrop) { drawPhotos(Offset(origin[0], origin[1]), frost = true) }
                val veil = lerp(Color.White.copy(alpha = 0.10f), Color.White.copy(alpha = 0.92f - 0.2f * soft()), f)
                drawRect(veil)
                drawRoundRect(
                    Color.White.copy(alpha = 0.16f * (1f - f)),
                    cornerRadius = CornerRadius(TILE_RADIUS.dp.toPx()),
                    style = Stroke(1.dp.toPx()),
                )
            }
            .onFocusChanged { focused = it.isFocused; onFocus(it.isFocused) }
            .tvClickable(onClick, onLongClick),
        contentAlignment = Alignment.Center,
    ) { content(focused) }
}

@Composable
fun TileImage(image: ImageBitmap?, size: Int = 72) {
    if (image != null) {
        Image(
            bitmap = image,
            contentDescription = null,
            modifier = Modifier.size(size.dp).clip(RoundedCornerShape(14.dp)),
        )
    }
}

@Composable
fun TileIcon(painter: Painter, focused: Boolean = false) {
    Image(
        painter,
        contentDescription = null,
        modifier = Modifier.size(52.dp),
        colorFilter = ColorFilter.tint(if (focused) Color(0xFF1A1A1A) else Color.White.copy(alpha = 0.9f)),
    )
}

/** A soft white rounded-rect glow, rendered once in software and tinted at draw time. */
@Composable
private fun rememberGlow(): ImageBitmap {
    val density = LocalDensity.current
    return remember(density) {
        val tile = with(density) { TILE_SIZE.dp.toPx() }
        val pad = with(density) { GLOW_PAD.dp.toPx() }
        val radius = with(density) { TILE_RADIUS.dp.toPx() }
        val side = (tile + 2 * pad).toInt()
        val bmp = Bitmap.createBitmap(side, side, Bitmap.Config.ARGB_8888)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = android.graphics.Color.WHITE
            maskFilter = BlurMaskFilter(pad * 0.55f, BlurMaskFilter.Blur.NORMAL)
        }
        Canvas(bmp).drawRoundRect(pad, pad, pad + tile, pad + tile, radius, radius, paint)
        bmp.asImageBitmap()
    }
}

/**
 * Frosted-glass background for any element: the pre-blurred photo cropped to this element's
 * screen position, clipped to [shape], plus a [veil] tint.
 */
fun Modifier.frost(backdrop: BackdropState, shape: androidx.compose.ui.graphics.Shape, veil: Color): Modifier =
    androidx.compose.ui.composed {
        val origin = remember { FloatArray(2) }
        onGloballyPositioned { val p = it.positionInRoot(); origin[0] = p.x; origin[1] = p.y }
            .clip(shape)
            .drawBehind {
                with(backdrop) { drawPhotos(Offset(origin[0], origin[1]), frost = true) }
                drawRect(veil)
            }
    }
