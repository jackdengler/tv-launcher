package com.jackdengler.quiet.home

import android.text.format.DateUtils
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import com.jackdengler.quiet.data.Headline
import com.jackdengler.quiet.ui.LocalAccent
import com.jackdengler.quiet.ui.QrCode
import com.jackdengler.quiet.ui.QuietFonts
import com.jackdengler.quiet.ui.tvClickable

/**
 * Every current headline with its summary. The focused story's link is shown as a QR code so
 * it can be read on the phone (the TV has no good browser).
 */
@Composable
fun NewsPanel(headlines: List<Headline>, onClose: () -> Unit) {
    BackHandler(onBack = onClose)
    var focusedIndex by remember { mutableIntStateOf(0) }
    val first = remember { FocusRequester() }
    LaunchedEffect(headlines.isNotEmpty()) {
        withFrameNanos { }
        runCatching { first.requestFocus() }
    }
    Row(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.78f)).padding(start = 96.dp, end = 96.dp, top = 56.dp)) {
        Column(Modifier.weight(1f)) {
            Text(
                "Headlines",
                style = TextStyle(fontFamily = QuietFonts.InstrumentSerif, fontStyle = FontStyle.Italic, fontSize = 48.sp, color = Color.White),
            )
            Spacer(Modifier.height(16.dp))
            if (headlines.isEmpty()) {
                Text("No headlines yet.", style = TextStyle(fontFamily = QuietFonts.Manrope, fontSize = 22.sp, color = Color.White))
            }
            LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp), contentPadding = PaddingValues(bottom = 80.dp)) {
                itemsIndexed(headlines) { i, h ->
                    StoryRow(h, if (i == 0) Modifier.focusRequester(first) else Modifier) { focusedIndex = i }
                }
            }
        }
        Spacer(Modifier.width(56.dp))
        val link = headlines.getOrNull(focusedIndex)?.link.orEmpty()
        Column(Modifier.width(300.dp).padding(top = 90.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            if (link.startsWith("http")) {
                QrCode(link, 240)
                Spacer(Modifier.height(16.dp))
                Text(
                    "Scan with your iPhone camera to read this story",
                    style = TextStyle(fontFamily = QuietFonts.Manrope, fontSize = 18.sp, color = Color.White.copy(alpha = 0.75f)),
                )
            }
        }
    }
}

@Composable
private fun StoryRow(h: Headline, modifier: Modifier, onFocused: () -> Unit) {
    val accent by LocalAccent.current
    var focused by remember { mutableStateOf(false) }
    val ink = if (focused) Color(0xFF151515) else Color.White
    Column(
        modifier
            .fillMaxWidth()
            .background(Color.White.copy(alpha = if (focused) 0.92f else 0.06f), RoundedCornerShape(16.dp))
            .onFocusChanged {
                focused = it.isFocused
                if (it.isFocused) onFocused()
            }
            .tvClickable(onClick = onFocused)
            .padding(horizontal = 24.dp, vertical = 16.dp),
    ) {
        val age = if (h.published > 0) {
            DateUtils.getRelativeTimeSpanString(h.published, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS).toString()
        } else {
            ""
        }
        Text(
            listOf(h.source.uppercase(), age).filter { it.isNotEmpty() }.joinToString("  ·  "),
            style = TextStyle(
                fontFamily = QuietFonts.Manrope, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, letterSpacing = 1.4.sp,
                color = if (focused) Color(0xFF555555) else accent,
            ),
        )
        Spacer(Modifier.height(4.dp))
        Text(h.title, style = TextStyle(fontFamily = QuietFonts.Manrope, fontWeight = FontWeight.SemiBold, fontSize = 23.sp, color = ink))
        if (h.summary.isNotEmpty()) {
            Spacer(Modifier.height(4.dp))
            Text(
                h.summary,
                maxLines = if (focused) 4 else 2,
                overflow = TextOverflow.Ellipsis,
                style = TextStyle(fontFamily = QuietFonts.Manrope, fontSize = 18.sp, lineHeight = 26.sp, color = ink.copy(alpha = 0.75f)),
            )
        }
    }
}
