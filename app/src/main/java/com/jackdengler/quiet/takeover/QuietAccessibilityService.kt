package com.jackdengler.quiet.takeover

import android.accessibilityservice.AccessibilityService
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.PowerManager
import android.os.SystemClock
import android.provider.Settings
import android.view.accessibility.AccessibilityEvent
import com.jackdengler.quiet.MainActivity
import com.jackdengler.quiet.QuietApp
import com.jackdengler.quiet.data.Espn
import com.jackdengler.quiet.data.Nfl
import com.jackdengler.quiet.data.Prefs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Calendar

/**
 * Home-button takeover without adb: when a stock launcher window comes to the front, start
 * Quiet on top of it (the approach Projectivy uses). Also hosts the ESPN fantasy overlay.
 */
class QuietAccessibilityService : AccessibilityService() {

    private var stockLaunchers: Set<String> = emptySet()
    private var lastLaunch = 0L

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var overlay: FantasyOverlay? = null
    private var overlayJob: Job? = null
    private var quietInFront = false

    // Lead tracking for the "Took the lead" highlight, per league/team/week.
    private var leadKey = ""
    private var lastLead = 0
    private var highlightJob: Job? = null

    override fun onServiceConnected() {
        stockLaunchers = findStockLaunchers()
        overlay = FantasyOverlay(this)
        val prefs = (application as QuietApp).prefs
        // Restart the overlay loop whenever settings change; it does nothing while disabled.
        scope.launch {
            prefs.changes.collectLatest { restartOverlay(prefs) }
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        if (event.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return
        val pkg = event.packageName?.toString() ?: return
        if (pkg in stockLaunchers) bringQuietForward()
        // Ignore transient system windows (IME, system UI) when tracking what's in front.
        if (pkg == "com.android.systemui" || pkg.contains("inputmethod")) return
        // Our own events only count when they come from one of Quiet's activities. The overlay
        // window and toasts belong to this package too, and showing the overlay must not make
        // the service think Quiet is in front (that hid it again right after it appeared).
        if (pkg == packageName && event.className?.toString()?.startsWith("$packageName.") != true) return
        val inFront = pkg == packageName || pkg in stockLaunchers
        if (inFront != quietInFront) {
            quietInFront = inFront
            if (inFront) overlay?.hide() else (application as? QuietApp)?.prefs?.let(::restartOverlay)
        }
    }

    override fun onInterrupt() = Unit

    override fun onDestroy() {
        scope.cancel()
        overlay?.destroy()
        super.onDestroy()
    }

    /**
     * While enabled: once a minute, check the game window; when inside it (or "always") and Quiet
     * isn't in front, fetch the score and show it. No network outside the windows.
     */
    private fun restartOverlay(prefs: Prefs) {
        overlayJob?.cancel()
        overlay?.hide()
        if (!prefs.espnEnabled || prefs.espnLeague.isBlank() || prefs.espnTeam.isBlank()) return
        overlayJob = scope.launch {
            while (isActive) {
                val now = Calendar.getInstance()
                val screenOn = getSystemService(PowerManager::class.java).isInteractive
                if (screenOn && !quietInFront && (prefs.espnAlways || Espn.inGameWindow(now))) {
                    val result = withContext(Dispatchers.IO) {
                        // The public scoreboard tells us which starters are still playing.
                        val states = runCatching { Nfl.states(Nfl.scoreboard()) }.getOrDefault(emptyMap())
                        runCatching { Espn.fetch(prefs.espnLeague, prefs.espnTeam, prefs.espnS2, prefs.espnSwid, now, states) }
                    }
                    val time = java.text.DateFormat.getTimeInstance(java.text.DateFormat.SHORT).format(now.time)
                    result.onSuccess { score ->
                        prefs.putCached(Espn.STATUS, "OK at $time: ${score.headline}")
                        val key = "${prefs.espnLeague}/${prefs.espnTeam}/${score.week}"
                        val previous = lastLead.takeIf { leadKey == key }
                        leadKey = key
                        lastLead = score.lead
                        val change = if (previous != null && previous != score.lead && score.lead != 0) score.lead else 0
                        if (!quietInFront) overlay?.show(score, change)
                        if (change != 0) {
                            highlightJob?.cancel()
                            highlightJob = scope.launch {
                                delay(LEAD_HIGHLIGHT_MS)
                                overlay?.clearHighlight()
                            }
                        }
                    }.onFailure {
                        prefs.putCached(Espn.STATUS, "Failed at $time: ${it.message ?: it.javaClass.simpleName}")
                    }
                } else {
                    overlay?.hide()
                    val why = when {
                        !screenOn -> "Screen off"
                        quietInFront -> "Hidden while Quiet is on screen"
                        else -> "Outside game windows (Thu/Mon 7 pm, Sun 6 am). Set When to show: Always to test"
                    }
                    prefs.putCached(Espn.STATUS, why)
                }
                delay(OVERLAY_REFRESH_MS)
            }
        }
    }

    private fun bringQuietForward() {
        val now = SystemClock.uptimeMillis()
        if (now - lastLaunch < DEBOUNCE_MS) return
        lastLaunch = now
        val intent = Intent(this, MainActivity::class.java)
            .setAction(Intent.ACTION_MAIN)
            .addCategory(Intent.CATEGORY_HOME)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
        runCatching { startActivity(intent) }
    }

    /** Known stock launchers plus every other HOME app on the device (excluding settings' fallback). */
    private fun findStockLaunchers(): Set<String> {
        val home = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
        val found = packageManager.queryIntentActivities(home, 0).map { it.activityInfo.packageName }
        return (KNOWN_LAUNCHERS + found) - packageName - SETTINGS_PACKAGES
    }

    companion object {
        private const val DEBOUNCE_MS = 400L
        private const val OVERLAY_REFRESH_MS = 60_000L
        private const val LEAD_HIGHLIGHT_MS = 15_000L

        private val KNOWN_LAUNCHERS = setOf(
            "com.google.android.apps.tv.launcherx", // Google TV
            "com.google.android.tvlauncher", // Android TV
            "com.google.android.leanbacklauncher",
        )
        private val SETTINGS_PACKAGES = setOf("com.android.settings", "com.android.tv.settings")

        fun isEnabled(context: Context): Boolean {
            val enabled = Settings.Secure.getString(context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
                ?: return false
            val me = ComponentName(context, QuietAccessibilityService::class.java)
            return enabled.split(':').any { ComponentName.unflattenFromString(it) == me }
        }
    }
}
