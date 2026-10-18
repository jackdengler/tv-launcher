package com.jackdengler.quiet.ui

import android.view.KeyEvent
import androidx.compose.foundation.focusable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.input.key.onKeyEvent

private val SelectKeys = setOf(
    KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER,
    KeyEvent.KEYCODE_NUMPAD_ENTER, KeyEvent.KEYCODE_BUTTON_A,
)

private class PressState {
    var down = false
    var longFired = false
}

/**
 * Focusable + OK-button click with a long-press, driven by raw key events so it does not depend
 * on touch-oriented gesture detection. A key-up whose key-down happened elsewhere (for example in
 * the app we just returned from) is ignored.
 */
fun Modifier.tvClickable(onClick: () -> Unit, onLongClick: (() -> Unit)? = null): Modifier = composed {
    val press = remember { PressState() }
    onKeyEvent { event ->
        val key = event.nativeKeyEvent
        if (key.keyCode !in SelectKeys) return@onKeyEvent false
        when (key.action) {
            KeyEvent.ACTION_DOWN -> {
                if (key.repeatCount == 0) {
                    press.down = true
                    press.longFired = false
                } else if (press.down && !press.longFired && onLongClick != null) {
                    // The first auto-repeat arrives after the system long-press timeout.
                    press.longFired = true
                    onLongClick()
                }
            }
            KeyEvent.ACTION_UP -> {
                if (press.down && !press.longFired) onClick()
                press.down = false
                press.longFired = false
            }
        }
        true
    }.focusable()
}
