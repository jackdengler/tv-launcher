package com.jackdengler.quiet.home

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import com.jackdengler.quiet.data.Weather
import com.jackdengler.quiet.ui.LocalAccent
import com.jackdengler.quiet.ui.QuietFonts

/** Weather glance: now, the next 12 hours and the next 3 days. Back closes it. */
@Composable
fun ForecastPanel(weather: Weather?, night: Boolean, onClose: () -> Unit) {
    BackHandler(onBack = onClose)
    val accent by LocalAccent.current
    // Something must hold focus so Back reaches this screen's handler.
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        withFrameNanos { }
        runCatching { focus.requestFocus() }
    }
    val text = TextStyle(fontFamily = QuietFonts.Manrope, color = Color.White, fontFeatureSettings = "tnum")
    Box(
        Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.74f)).focusRequester(focus).focusable(),
        contentAlignment = Alignment.Center,
    ) {
        if (weather == null || weather.hours.isEmpty()) {
            Text("Forecast loading…", style = text.copy(fontSize = 26.sp))
            return@Box
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Image(
                    painterResource(weather.icon(night)), null, Modifier.size(88.dp),
                    colorFilter = ColorFilter.tint(accent),
                )
                Spacer(Modifier.width(24.dp))
                Text("${weather.temp}°", style = text.copy(fontSize = 120.sp, fontWeight = FontWeight.ExtraLight))
                Spacer(Modifier.width(24.dp))
                Text(
                    weather.label,
                    style = TextStyle(fontFamily = QuietFonts.InstrumentSerif, fontStyle = FontStyle.Italic, fontSize = 44.sp, color = Color.White),
                )
            }
            Spacer(Modifier.height(36.dp))
            Row(
                Modifier.background(Color.White.copy(alpha = 0.07f), RoundedCornerShape(24.dp)).padding(horizontal = 24.dp, vertical = 20.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                weather.hours.forEach { h ->
                    Column(Modifier.width(96.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(h.label, style = text.copy(fontSize = 17.sp, color = Color.White.copy(alpha = 0.7f)))
                        Spacer(Modifier.height(10.dp))
                        Image(
                            painterResource(Weather.iconFor(h.code, dark = !h.isDay)), null, Modifier.size(34.dp),
                            colorFilter = ColorFilter.tint(accent),
                        )
                        Spacer(Modifier.height(10.dp))
                        Text("${h.temp}°", style = text.copy(fontSize = 24.sp))
                    }
                }
            }
            Spacer(Modifier.height(28.dp))
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                weather.days.drop(1).take(3).forEach { d ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(d.label, modifier = Modifier.width(220.dp), style = text.copy(fontSize = 24.sp))
                        Image(
                            painterResource(Weather.iconFor(d.code, dark = false)), null, Modifier.size(32.dp),
                            colorFilter = ColorFilter.tint(accent),
                        )
                        Spacer(Modifier.width(20.dp))
                        Text(Weather.describe(d.code), modifier = Modifier.width(260.dp), style = text.copy(fontSize = 22.sp, color = Color.White.copy(alpha = 0.75f)))
                        Text(
                            "${d.high}°  /  ${d.low}°",
                            modifier = Modifier.width(180.dp),
                            style = text.copy(fontSize = 24.sp, textAlign = TextAlign.End),
                        )
                    }
                }
            }
        }
    }
}
