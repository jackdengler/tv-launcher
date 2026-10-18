package com.jackdengler.quiet.home

import android.text.format.DateFormat
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.Image
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.widthIn
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.text.style.TextOverflow
import com.jackdengler.quiet.data.Ambient
import com.jackdengler.quiet.data.Headline
import com.jackdengler.quiet.data.Weather
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import com.jackdengler.quiet.R
import com.jackdengler.quiet.apps.AppEntry
import com.jackdengler.quiet.apps.AppRepository
import com.jackdengler.quiet.ui.LocalAccent
import com.jackdengler.quiet.ui.QuietFonts
import androidx.compose.runtime.withFrameNanos
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

val TextShadow = Shadow(Color.Black.copy(alpha = 0.35f), blurRadius = 18f)

/**
 * The home screen. [idle] and [night] are 0..1 animation fractions read only in the
 * layer/draw phase, so the transitions don't recompose the tree each frame.
 */
@Composable
fun HomeScreen(
    backdrop: BackdropState,
    apps: AppRepository,
    focusSignal: Int,
    onAllApps: () -> Unit,
    ambient: Ambient,
    isNight: Boolean,
    isIdle: Boolean,
    idle: () -> Float,
    night: () -> Float,
    onLongPressSlot: (Int) -> Unit = {},
    slots: List<Slot> = DefaultSlots,
) {
    val now by rememberMinuteClock()
    val soft = { 1f - 0.28f * night() }
    Box(Modifier.fillMaxSize()) {
        Column(Modifier.padding(start = 96.dp, top = 56.dp).graphicsLayer { alpha = (1f - idle()) * soft() }) {
            Clock(now)
            DateLine(now) { WeatherInline(ambient.weather, isNight) }
            Spacer(Modifier.height(26.dp))
            HeadlinePill(backdrop, ambient.headline)
        }
        PhotoDots(
            backdrop,
            Modifier.align(Alignment.TopEnd).padding(top = 84.dp, end = 96.dp).graphicsLayer { alpha = (1f - idle()) * soft() },
        )
        TileGrid(
            backdrop, apps, slots, focusSignal, onAllApps, onLongPressSlot, night,
            Modifier.align(Alignment.BottomCenter).padding(bottom = 64.dp)
                .graphicsLayer { alpha = (1f - idle()) * (1f - 0.15f * night()) },
        )
        if (backdrop.credit.isNotEmpty()) {
            Text(
                backdrop.credit,
                maxLines = 1,
                modifier = Modifier.align(Alignment.BottomEnd).padding(end = 36.dp, bottom = 20.dp).widthIn(max = 520.dp),
                style = TextStyle(fontFamily = QuietFonts.Manrope, fontSize = 13.sp, color = Color.White.copy(alpha = 0.42f)),
            )
        }
        // Idle: the photo takes over; a compact clock, date, weather and headline sit bottom-left.
        AnimatedVisibility(
            visible = isIdle,
            enter = fadeIn(tween(IDLE_FADE_MS)),
            exit = fadeOut(tween(IDLE_FADE_MS / 2)),
            modifier = Modifier.align(Alignment.BottomStart).padding(start = 96.dp, bottom = 80.dp),
        ) {
            Column(Modifier.graphicsLayer { alpha = soft() }) {
                Clock(now, size = 88)
                DateLine(now, size = 26) { WeatherInline(ambient.weather, isNight, size = 24) }
                Spacer(Modifier.height(18.dp))
                HeadlinePill(backdrop, ambient.headline, maxWidth = 900, scale = 0.85f)
            }
        }
    }
}

const val IDLE_FADE_MS = 1200

/** Wall-clock millis, updated on each minute boundary. */
@Composable
fun rememberMinuteClock(): State<Long> {
    val now = remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            val t = System.currentTimeMillis()
            now.longValue = t
            delay(60_000 - t % 60_000 + 50)
        }
    }
    return now
}

@Composable
fun Clock(now: Long, size: Int = 148, alpha: Float = 1f) {
    val context = LocalContext.current
    val pattern = if (DateFormat.is24HourFormat(context)) "H:mm" else "h:mm"
    val format = remember(pattern) { SimpleDateFormat(pattern, Locale.getDefault()) }
    Text(
        format.format(Date(now)),
        style = TextStyle(
            fontFamily = QuietFonts.Manrope,
            fontWeight = FontWeight.ExtraLight,
            fontSize = size.sp,
            lineHeight = (size * 1.05f).sp,
            letterSpacing = (-size * 0.02f).sp,
            fontFeatureSettings = "tnum",
            color = Color.White.copy(alpha = alpha),
            shadow = TextShadow,
        ),
    )
}

@Composable
fun DateLine(now: Long, size: Int = 34, alpha: Float = 0.9f, trailing: @Composable () -> Unit = {}) {
    val format = remember { SimpleDateFormat("EEEE, MMMM d", Locale.getDefault()) }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            format.format(Date(now)),
            style = TextStyle(
                fontFamily = QuietFonts.InstrumentSerif,
                fontStyle = FontStyle.Italic,
                fontSize = size.sp,
                color = Color.White.copy(alpha = alpha),
                shadow = TextShadow,
            ),
        )
        trailing()
    }
}

@Composable
fun WeatherInline(weather: Weather?, night: Boolean, size: Int = 30) {
    weather ?: return
    val accent by LocalAccent.current
    Row(verticalAlignment = Alignment.CenterVertically) {
        Spacer(Modifier.width((size * 0.8f).dp))
        Image(
            painterResource(weather.icon(night)),
            contentDescription = null,
            modifier = Modifier.size((size * 0.95f).dp),
            colorFilter = ColorFilter.tint(accent),
        )
        Spacer(Modifier.width((size * 0.35f).dp))
        Text(
            if (weather.label.isEmpty()) "${weather.temp}°" else "${weather.temp}° · ${weather.label}",
            style = TextStyle(
                fontFamily = QuietFonts.Manrope,
                fontWeight = FontWeight.Normal,
                fontSize = (size * 0.85f).sp,
                fontFeatureSettings = "tnum",
                color = accent,
                shadow = TextShadow,
            ),
        )
    }
}

/** One headline in a small frosted pill with the feed's name as a tag; crossfades on change. */
@Composable
fun HeadlinePill(backdrop: BackdropState, headline: Headline?, maxWidth: Int = 980, scale: Float = 1f) {
    val accent by LocalAccent.current
    Crossfade(headline, animationSpec = tween(700), label = "headline") { h ->
        if (h == null) return@Crossfade
        Row(
            Modifier
                .widthIn(max = maxWidth.dp)
                .frost(backdrop, CircleShape, Color.Black.copy(alpha = 0.22f))
                .border(1.dp, Color.White.copy(alpha = 0.14f), CircleShape)
                .padding(horizontal = (20 * scale).dp, vertical = (10 * scale).dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                h.source.uppercase(),
                maxLines = 1,
                style = TextStyle(
                    fontFamily = QuietFonts.Manrope, fontWeight = FontWeight.SemiBold,
                    fontSize = (14 * scale).sp, letterSpacing = (1.6 * scale).sp, color = accent,
                ),
            )
            Spacer(Modifier.width((14 * scale).dp))
            Text(
                h.title,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = TextStyle(
                    fontFamily = QuietFonts.Manrope, fontWeight = FontWeight.Normal,
                    fontSize = (20 * scale).sp, color = Color.White.copy(alpha = 0.92f),
                ),
            )
        }
    }
}

@Composable
private fun PhotoDots(backdrop: BackdropState, modifier: Modifier) {
    val count = backdrop.dotCount.coerceAtMost(MAX_DOTS)
    if (count < 2) return
    val active = backdrop.dotIndex % count
    val accent by LocalAccent.current
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        repeat(count) { i ->
            val width by animateDpAsState(if (i == active) 24.dp else 7.dp, tween(400), label = "dot")
            Box(
                Modifier.size(width, 7.dp).background(
                    if (i == active) accent else Color.White.copy(alpha = 0.45f),
                    CircleShape,
                ),
            )
        }
    }
}

private const val MAX_DOTS = 8

@Composable
private fun TileGrid(
    backdrop: BackdropState,
    apps: AppRepository,
    slots: List<Slot>,
    focusSignal: Int,
    onAllApps: () -> Unit,
    onLongPressSlot: (Int) -> Unit,
    night: () -> Float,
    modifier: Modifier,
) {
    val version by apps.version.collectAsState()
    val installed by produceState<List<AppEntry>>(emptyList(), version) { value = apps.installed() }
    var label by remember { mutableStateOf("") }
    val first = remember { FocusRequester() }
    LaunchedEffect(focusSignal) {
        withFrameNanos { }
        runCatching { first.requestFocus() }
    }

    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        for (row in slots.indices.chunked(4)) {
            Row(horizontalArrangement = Arrangement.spacedBy(TILE_GAP.dp)) {
                for (i in row) {
                    val slot = slots[i]
                    val tileModifier = if (i == 0) Modifier.focusRequester(first) else Modifier
                    SlotTile(backdrop, apps, slot, installed, tileModifier, onAllApps, { onLongPressSlot(i) }, night) { name ->
                        label = name
                    }
                }
            }
            Spacer(Modifier.height(TILE_GAP.dp))
        }
        Text(
            label,
            modifier = Modifier.width(600.dp).height(28.dp),
            style = TextStyle(
                fontFamily = QuietFonts.Manrope,
                fontWeight = FontWeight.Normal,
                fontSize = 20.sp,
                letterSpacing = 0.5.sp,
                color = Color.White.copy(alpha = 0.8f),
                shadow = TextShadow,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            ),
        )
    }
}

@Composable
private fun SlotTile(
    backdrop: BackdropState,
    apps: AppRepository,
    slot: Slot,
    installed: List<AppEntry>,
    modifier: Modifier,
    onAllApps: () -> Unit,
    onLongPress: () -> Unit,
    night: () -> Float,
    onLabel: (String) -> Unit,
) {
    when (slot) {
        is Slot.App -> {
            val entry = slot.packages.firstNotNullOfOrNull { p -> installed.firstOrNull { it.packageName == p } }
            val px = with(LocalDensity.current) { 72.dp.roundToPx() }
            val icon by produceState<androidx.compose.ui.graphics.ImageBitmap?>(null, entry) {
                value = entry?.let { apps.icon(it, px) }
            }
            FrostTile(
                backdrop,
                onClick = { if (entry != null) apps.launch(entry) else apps.openStore(slot.packages.first()) },
                modifier = modifier,
                onLongClick = onLongPress,  // swap in a different app
                onFocus = { if (it) onLabel(entry?.label ?: "${slot.label} · not installed") },
                soft = night,
            ) {
                if (icon != null) TileImage(icon) else Text(
                    slot.label,
                    style = TextStyle(fontFamily = QuietFonts.Manrope, fontSize = 16.sp, color = if (it) Color.Black else Color.White),
                )
            }
        }
        Slot.AllApps -> FrostTile(
            backdrop, onClick = onAllApps, modifier = modifier,
            onFocus = { if (it) onLabel("All apps") }, soft = night,
        ) { TileIcon(painterResource(R.drawable.ic_apps), it) }
        Slot.Settings -> FrostTile(
            backdrop, onClick = apps::openSystemSettings, modifier = modifier,
            onFocus = { if (it) onLabel("Settings") }, soft = night,
        ) { TileIcon(painterResource(R.drawable.ic_settings), it) }
    }
}
