package com.jackdengler.quiet.takeover

import android.content.Intent
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.tv.material3.Text
import com.jackdengler.quiet.BuildConfig
import com.jackdengler.quiet.settings.SettingRow
import com.jackdengler.quiet.ui.LocalAccent
import com.jackdengler.quiet.ui.QuietFonts

/** Walks through enabling the accessibility service that makes Home open Quiet. */
@Composable
fun OnboardingScreen(onDone: () -> Unit) {
    BackHandler(onBack = onDone)
    val context = LocalContext.current
    var enabled by remember { mutableStateOf(QuietAccessibilityService.isEnabled(context)) }
    LifecycleResumeEffect(Unit) {
        enabled = QuietAccessibilityService.isEnabled(context)
        onPauseOrDispose { }
    }
    val first = remember { FocusRequester() }
    LaunchedEffect(enabled) {
        withFrameNanos { }
        runCatching { first.requestFocus() }
    }
    val accent by LocalAccent.current
    val body = TextStyle(fontFamily = QuietFonts.Manrope, fontSize = 24.sp, lineHeight = 36.sp, color = Color.White.copy(alpha = 0.88f))

    Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.8f)), contentAlignment = Alignment.CenterStart) {
        Column(Modifier.padding(start = 160.dp).width(1100.dp)) {
            Text(
                if (BuildConfig.LAUNCHER) "Make the Home button open Quiet" else "Turn on the fantasy overlay",
                style = TextStyle(fontFamily = QuietFonts.InstrumentSerif, fontStyle = FontStyle.Italic, fontSize = 52.sp, color = Color.White),
            )
            Spacer(Modifier.height(14.dp))
            Text(
                when {
                    enabled && BuildConfig.LAUNCHER -> "All set. Home now opens Quiet."
                    enabled -> "All set. Scores appear over whatever you're watching."
                    BuildConfig.LAUNCHER -> "Google TV won't let you choose a home app without a computer. Quiet works around this with an accessibility service that only watches which app is in front."
                    else -> "The scorebug is drawn by an accessibility service, which lets it float over any app. It only watches which app is in front, never what's on screen."
                },
                style = body.copy(color = if (enabled) accent else body.color),
            )
            Spacer(Modifier.height(30.dp))
            if (!enabled) {
                Step(1, "Choose “Open Accessibility settings” below.", body)
                Step(2, "Find “${if (BuildConfig.LAUNCHER) "Quiet Home" else "Quiet Scores overlay"}” in the list (under Services on some TVs) and open it.", body)
                Step(3, "Turn it on and confirm. Then press Back to return here.", body)
                Spacer(Modifier.height(10.dp))
                Text(
                    "If the switch snaps back to off: open Settings → Apps → See all apps → Quiet, choose “Allow restricted settings”, then try again. " +
                        "No such option? Use Quiet settings → Reinstall Quiet, then try again.",
                    style = body.copy(fontSize = 19.sp, color = Color.White.copy(alpha = 0.6f)),
                )
                Spacer(Modifier.height(30.dp))
            }
            Row(horizontalArrangement = Arrangement.spacedBy(18.dp)) {
                if (!enabled) {
                    SettingRow("Open Accessibility settings", "", Modifier.width(460.dp).focusRequester(first)) {
                        val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        runCatching { context.startActivity(intent) }
                    }
                    SettingRow("Not now", "", Modifier.width(220.dp), onClick = onDone)
                } else {
                    SettingRow("Done", "", Modifier.width(220.dp).focusRequester(first), onClick = onDone)
                }
            }
        }
    }
}

@Composable
private fun Step(n: Int, text: String, style: TextStyle) {
    val accent by LocalAccent.current
    Row(Modifier.padding(vertical = 6.dp)) {
        Text("$n", modifier = Modifier.width(44.dp), style = style.copy(fontWeight = FontWeight.SemiBold, color = accent))
        Text(text, style = style)
    }
}
