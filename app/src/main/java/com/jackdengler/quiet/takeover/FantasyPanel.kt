package com.jackdengler.quiet.takeover

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.tv.material3.Text
import com.jackdengler.quiet.data.Prefs
import com.jackdengler.quiet.ui.QuietFonts
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/** Both leagues, expanded: every starter's points, the win chart, refreshed each minute. */
@Composable
fun FantasyPanel(prefs: Prefs, onClose: () -> Unit) {
    BackHandler(onBack = onClose)
    var result by remember { mutableStateOf<FantasyLoader.Result?>(null) }
    LaunchedEffect(Unit) {
        while (true) {
            result = withContext(Dispatchers.IO) { FantasyLoader.load(prefs, onlyShown = false) }
            delay(60_000)
        }
    }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        withFrameNanos { }
        runCatching { focus.requestFocus() }
    }
    Box(
        Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.8f)).focusRequester(focus).focusable(),
        contentAlignment = Alignment.TopCenter,
    ) {
        Column(Modifier.padding(top = 50.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                "Fantasy",
                style = TextStyle(fontFamily = QuietFonts.InstrumentSerif, fontStyle = FontStyle.Italic, fontSize = 48.sp, color = Color.White),
            )
            val r = result
            when {
                r == null -> Text("Loading…", style = TextStyle(fontFamily = QuietFonts.Manrope, fontSize = 22.sp, color = Color.White))
                r.cards.isEmpty() -> Text(
                    r.errors.firstOrNull() ?: "Add a league in Quiet settings → ESPN fantasy overlay.",
                    style = TextStyle(fontFamily = QuietFonts.Manrope, fontSize = 22.sp, color = Color.White),
                )
                else -> Row(Modifier.padding(top = 20.dp), horizontalArrangement = Arrangement.spacedBy(40.dp)) {
                    for ((_, card) in r.cards) {
                        AndroidView(
                            factory = { FantasyCardView(it).apply { style = "full"; scaleUp = 1.25f } },
                            update = { it.cards = listOf(card) },
                        )
                    }
                }
            }
        }
    }
}
