package com.jackdengler.quiet.takeover

import android.accessibilityservice.AccessibilityService
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import android.provider.Settings
import android.view.accessibility.AccessibilityEvent
import com.jackdengler.quiet.MainActivity

/**
 * Home-button takeover without adb: when a stock launcher window comes to the front, start
 * Quiet on top of it (the approach Projectivy uses). Phase 6 adds the ESPN overlay here.
 */
class QuietAccessibilityService : AccessibilityService() {

    private var stockLaunchers: Set<String> = emptySet()
    private var lastLaunch = 0L

    override fun onServiceConnected() {
        stockLaunchers = findStockLaunchers()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        if (event.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return
        val pkg = event.packageName?.toString() ?: return
        if (pkg in stockLaunchers) bringQuietForward()
    }

    override fun onInterrupt() = Unit

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
