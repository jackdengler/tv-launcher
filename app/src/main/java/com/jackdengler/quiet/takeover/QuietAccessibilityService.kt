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
import com.jackdengler.quiet.data.PlayPop
import com.jackdengler.quiet.data.Plays
import com.jackdengler.quiet.data.Prefs
import com.jackdengler.quiet.data.Watched
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.CoroutineExceptionHandler
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

    // An error in any overlay job is recorded and that job ends; it must never crash Quiet
    // (which would also take down the launcher and switch this service off).
    private val scope = CoroutineScope(
        SupervisorJob() + Dispatchers.Main.immediate + CoroutineExceptionHandler { _, e ->
            val text = "${e.javaClass.simpleName}: ${e.message?.take(160)}"
            (application as? QuietApp)?.prefs?.putCached(Espn.STATUS, "Overlay error: $text")
        },
    )
    private var overlay: FantasyOverlay? = null
    private var overlayJob: Job? = null
    private var quietInFront = false

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
        playJob?.cancel()
        overlay?.hide()
        val anyShown = prefs.espnLeagues.any { (n) -> if (n == 1) prefs.espnShow1 else prefs.espnShow2 }
        if (!prefs.espnEnabled || !anyShown) return
        if (prefs.espnPlays) playJob = scope.launch { watchPlays(prefs) }
        overlayJob = scope.launch {
            while (isActive) {
                try {
                    overlayTick(prefs)
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (e: Exception) {
                    prefs.putCached(Espn.STATUS, "Overlay error: ${e.javaClass.simpleName}: ${e.message?.take(160)}")
                }
                delay(OVERLAY_REFRESH_MS)
            }
        }
    }

    /** One refresh of the scorebug: fetch, show or hide, and record a status line. */
    private suspend fun overlayTick(prefs: Prefs) {
        val now = Calendar.getInstance()
        val screenOn = getSystemService(PowerManager::class.java).isInteractive
        if (screenOn && !quietInFront && (prefs.espnAlways || Espn.inGameWindow(now))) {
            val result = withContext(Dispatchers.IO) { FantasyLoader.load(prefs, onlyShown = true, now = now) }
            latest = result
            val time = java.text.DateFormat.getTimeInstance(java.text.DateFormat.SHORT).format(now.time)
            val cards = result.cards.map { it.second }
            prefs.putCached(
                Espn.STATUS,
                (listOf("Updated $time") + result.cards.map { (n, c) -> "League $n: ${c.score.headline}" } + result.errors)
                    .joinToString(" · "),
            )
            if (!quietInFront) overlay?.show(cards, prefs.espnStyle, prefs.espnCorner)
            if (cards.any { it.leadChange != 0 }) {
                // Celebrate (or commiserate) for a moment, then settle back.
                highlightJob?.cancel()
                highlightJob = scope.launch {
                    delay(LEAD_HIGHLIGHT_MS)
                    val calm = cards.map { LeagueCard(it.score, it.history, it.myLogo, it.oppLogo) }
                    if (!quietInFront) overlay?.show(calm, prefs.espnStyle, prefs.espnCorner)
                }
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
    }

    /** Latest fantasy data from the overlay loop; the play watcher reads it. */
    @Volatile private var latest: FantasyLoader.Result? = null
    private var playJob: Job? = null

    /**
     * Pop-ups for plays involving my starters or my opponents'. Polls ESPN's game feed every
     * 15 s, only for live games with one of those players, and holds each play back by the
     * chosen delay so it shows when the play does on the (delayed) TV stream.
     */
    private suspend fun watchPlays(prefs: Prefs) = coroutineScope {
        val seen = HashSet<String>()
        val primed = HashSet<String>()
        val queue = Channel<PlayPop>(Channel.UNLIMITED)
        launch {
            for (pop in queue) {
                val showAt = pop.happenedAt + prefs.espnPlayDelay * 1000L
                val wait = showAt - System.currentTimeMillis()
                if (wait > 0) delay(wait)
                if (System.currentTimeMillis() - showAt > STALE_PLAY_MS || quietInFront) continue
                overlay?.showPlay(pop, prefs.espnCorner)
                delay(if (pop.big) BIG_PLAY_MS else PLAY_MS)
                overlay?.hidePlay()
                delay(500)
            }
        }
        while (isActive) {
            val data = latest
            val live = data?.games?.filter { it.state == "in" }.orEmpty()
            val screenOn = getSystemService(PowerManager::class.java).isInteractive
            if (data != null && live.isNotEmpty() && screenOn && !quietInFront) {
                val watched = data.cards.flatMap { (_, c) ->
                    c.score.me.starters.map { Watched(it.fullName, it.proTeam, true, c.score.scoring) } +
                        c.score.opp.starters.map { Watched(it.fullName, it.proTeam, false, c.score.scoring) }
                }
                val teams = watched.map { it.proTeam }.toSet()
                for (game in live.filter { g -> g.teamIds.any { it in teams } && g.eventId.isNotBlank() }) {
                    val plays = withContext(Dispatchers.IO) { runCatching { Plays.recent(game.eventId) }.getOrDefault(emptyList()) }
                    if (primed.add(game.eventId)) {
                        // First look at this game: don't replay everything that already happened.
                        plays.forEach { seen += it.id }
                        continue
                    }
                    val inGame = watched.filter { it.proTeam in game.teamIds }
                    for (play in plays) {
                        if (!seen.add(play.id)) continue
                        val stamp = if (play.wallclock > 0) play else play.copy(wallclock = System.currentTimeMillis())
                        runCatching { Plays.match(stamp, inGame) }.getOrDefault(emptyList()).forEach { queue.trySend(it) }
                    }
                }
            }
            delay(PLAY_POLL_MS)
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
        private const val PLAY_POLL_MS = 15_000L
        private const val PLAY_MS = 6_000L
        private const val BIG_PLAY_MS = 9_000L
        private const val STALE_PLAY_MS = 120_000L

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
