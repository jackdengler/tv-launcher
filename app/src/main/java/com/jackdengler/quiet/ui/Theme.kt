package com.jackdengler.quiet.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Density
import com.jackdengler.quiet.R

object QuietFonts {
    val Manrope = FontFamily(
        Font(R.font.manrope_extralight, FontWeight.ExtraLight),
        Font(R.font.manrope_regular, FontWeight.Normal),
        Font(R.font.manrope_semibold, FontWeight.SemiBold),
    )
    val InstrumentSerif = FontFamily(
        Font(R.font.instrument_serif_italic, FontWeight.Normal, FontStyle.Italic),
    )
}

val DefaultAccent = Color(0xFFF2D7B6)

/** The current photo's accent, as State so draw-phase readers skip recomposition. */
val LocalAccent = staticCompositionLocalOf<State<Color>> { mutableStateOf(DefaultAccent) }

/**
 * Lays the UI out in "design pixels": 1.dp == 1.sp == one pixel of a 1920-wide screen, so the
 * sizes in SPEC.md (148 px clock, 120 px tiles) can be written directly, at any panel density.
 */
@Composable
fun DesignPixels(content: @Composable () -> Unit) {
    val metrics = LocalContext.current.resources.displayMetrics
    val width = maxOf(metrics.widthPixels, metrics.heightPixels)
    CompositionLocalProvider(
        androidx.compose.ui.platform.LocalDensity provides Density(width / 1920f, 1f),
        content = content,
    )
}
