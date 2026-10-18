package com.jackdengler.quiet.apps

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import com.jackdengler.quiet.ui.LocalAccent
import com.jackdengler.quiet.ui.QuietFonts
import com.jackdengler.quiet.ui.tvClickable

private const val CARD_W = 232
private const val CARD_H = CARD_W * 9 / 16

/**
 * Grid of every launchable app. With [onPick] set it becomes a picker (used to swap a home
 * tile); otherwise selecting an app launches it. [extras] are shown first (e.g. Quiet settings).
 */
@Composable
fun AllAppsScreen(
    apps: AppRepository,
    onBack: () -> Unit,
    title: String = "All apps",
    onPick: ((AppEntry) -> Unit)? = null,
    extras: List<Pair<String, () -> Unit>> = emptyList(),
) {
    BackHandler(onBack = onBack)
    val version by apps.version.collectAsState()
    val list by produceState<List<AppEntry>?>(null, version) { value = apps.installed() }
    val first = remember { FocusRequester() }
    LaunchedEffect(list != null) {
        if (list != null) {
            withFrameNanos { }
            runCatching { first.requestFocus() }
        }
    }

    Column(
        Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.62f)).padding(start = 96.dp, end = 96.dp, top = 56.dp),
    ) {
        Text(
            title,
            style = TextStyle(
                fontFamily = QuietFonts.InstrumentSerif, fontStyle = FontStyle.Italic,
                fontSize = 48.sp, color = Color.White,
            ),
        )
        Spacer(Modifier.height(24.dp))
        val items = list ?: return@Column
        LazyVerticalGrid(
            columns = GridCells.Fixed(6),
            horizontalArrangement = Arrangement.spacedBy(28.dp),
            verticalArrangement = Arrangement.spacedBy(28.dp),
            contentPadding = PaddingValues(top = 12.dp, bottom = 64.dp, start = 12.dp, end = 12.dp),
        ) {
            itemsIndexed(extras, key = { _, e -> "extra:" + e.first }) { i, (label, action) ->
                AppCard(label, null, action, if (i == 0) Modifier.focusRequester(first) else Modifier)
            }
            itemsIndexed(items, key = { _, a -> a.packageName }) { i, app ->
                val px = with(LocalDensity.current) { CARD_W.dp.roundToPx() }
                val image by produceState<ImageBitmap?>(null, app) { value = apps.banner(app, px) ?: apps.icon(app, px / 3) }
                AppCard(
                    app.label, image,
                    onClick = { if (onPick != null) onPick(app) else apps.launch(app) },
                    modifier = if (i == 0 && extras.isEmpty()) Modifier.focusRequester(first) else Modifier,
                )
            }
        }
    }
}

@Composable
private fun AppCard(label: String, image: ImageBitmap?, onClick: () -> Unit, modifier: Modifier) {
    var focused by remember { mutableStateOf(false) }
    val f by animateFloatAsState(if (focused) 1f else 0f, tween(200), label = "card")
    val accent by LocalAccent.current
    val isBanner = image != null && image.width > image.height * 1.3f
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            modifier
                .size(CARD_W.dp, CARD_H.dp)
                .graphicsLayer { val s = 1f + 0.08f * f; scaleX = s; scaleY = s }
                .clip(RoundedCornerShape(14.dp))
                .background(Color.White.copy(alpha = 0.10f + 0.08f * f))
                .border(3.dp, accent.copy(alpha = f), RoundedCornerShape(14.dp))
                .onFocusChanged { focused = it.isFocused }
                .tvClickable(onClick),
            contentAlignment = Alignment.Center,
        ) {
            when {
                isBanner -> Image(image!!, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                image != null -> Image(image, null, Modifier.size(72.dp).clip(RoundedCornerShape(14.dp)))
                else -> Text(label, style = TextStyle(fontFamily = QuietFonts.Manrope, fontSize = 22.sp, color = Color.White))
            }
        }
        Text(
            label,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 10.dp).graphicsLayer { alpha = 0.55f + 0.45f * f },
            style = TextStyle(
                fontFamily = QuietFonts.Manrope, fontWeight = FontWeight.Normal, fontSize = 17.sp,
                color = Color.White, textAlign = TextAlign.Center,
            ),
        )
    }
}
