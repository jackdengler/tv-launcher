package com.jackdengler.quiet.settings

import android.app.Activity
import android.os.Bundle
import android.widget.Toast
import com.jackdengler.quiet.QuietApp

/**
 * The "Scores on/off" tile: one click shows or hides the fantasy overlay, then gets out of the
 * way. Add it to the favourites row for a quick toggle.
 */
class ToggleScoresActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val prefs = (application as QuietApp).prefs
        prefs.espnEnabled = !prefs.espnEnabled
        Toast.makeText(this, if (prefs.espnEnabled) "Scores shown" else "Scores hidden", Toast.LENGTH_SHORT).show()
        finish()
    }
}
