package com.jackdengler.quiet

import android.content.Intent
import android.os.Bundle
import android.os.SystemClock
import android.view.KeyEvent
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import com.jackdengler.quiet.apps.AllAppsScreen
import com.jackdengler.quiet.data.Ambient
import com.jackdengler.quiet.home.Backdrop
import com.jackdengler.quiet.home.DefaultSlots
import com.jackdengler.quiet.home.Slot
import com.jackdengler.quiet.home.effectiveSlots
import com.jackdengler.quiet.takeover.FantasyPanel
import com.jackdengler.quiet.takeover.OnboardingScreen
import com.jackdengler.quiet.takeover.QuietAccessibilityService
import com.jackdengler.quiet.home.BackdropState
import com.jackdengler.quiet.home.ForecastPanel
import com.jackdengler.quiet.home.HomeScreen
import com.jackdengler.quiet.home.Mood
import com.jackdengler.quiet.home.NewsPanel
import com.jackdengler.quiet.home.IDLE_FADE_MS
import com.jackdengler.quiet.home.nightFilter
import com.jackdengler.quiet.home.rememberMinuteClock
import com.jackdengler.quiet.photos.PhotoRepository
import com.jackdengler.quiet.photos.PhotoSyncWorker
import com.jackdengler.quiet.photos.Playlist
import com.jackdengler.quiet.photos.Selection
import com.jackdengler.quiet.photos.Sun
import com.jackdengler.quiet.data.Prefs
import com.jackdengler.quiet.settings.SettingsScreen
import com.jackdengler.quiet.ui.DesignPixels
import com.jackdengler.quiet.ui.LocalAccent
import com.jackdengler.quiet.update.Updater
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.util.Calendar

enum class Screen { Home, AllApps, Settings, Onboarding, PickApp, News, Forecast, Fantasy }

class MainActivity : ComponentActivity() {
    private var screen by mutableStateOf(Screen.Home)

    /** Home tile being reassigned by the app picker. */
    private var pickSlot by mutableIntStateOf(-1)
    private var homeSignal by mutableIntStateOf(0)

    /** Uptime of the last key press; drives idle mode. */
    private var lastInput = SystemClock.uptimeMillis()
    private var idle by mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val app = application as QuietApp
        if (!BuildConfig.LAUNCHER) {
            setContent { DesignPixels { ScoresRoot(app) { finish() } } }
            return
        }
        setContent {
            DesignPixels {
                QuietRoot(app, screen, homeSignal, idle, ::idleWatch, pickSlot, { pickSlot = it }) { screen = it }
            }
        }
    }

    /** Any key wakes from idle; the waking press itself is swallowed so it doesn't launch a tile. */
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        lastInput = SystemClock.uptimeMillis()
        if (idle) {
            if (event.action == KeyEvent.ACTION_UP) updateIdle(false)
            return true
        }
        return super.dispatchKeyEvent(event)
    }

    /** Enters idle after [IDLE_MS] without input while the home grid is showing. */
    private suspend fun idleWatch() {
        while (true) {
            val wait = lastInput + IDLE_MS - SystemClock.uptimeMillis()
            if (wait <= 0 && screen == Screen.Home && !idle) updateIdle(true)
            delay(if (wait > 0) wait else IDLE_MS)
        }
    }

    private fun updateIdle(value: Boolean) {
        idle = value
        // Keep the panel (and Google TV's screensaver) off while idle shows the photo.
        if (value) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    override fun onPause() {
        super.onPause()
        if (idle) updateIdle(false)
    }

    /** Home pressed while Quiet is already in front: back to the tile grid. */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        lastInput = SystemClock.uptimeMillis()
        updateIdle(false)
        screen = Screen.Home
        homeSignal++
    }

    private companion object {
        const val IDLE_MS = 3 * 60_000L
    }
}

private const val OFFERED_BUILD = "offered_build"

/** Quiet Scores: settings for the fantasy overlay, its setup screen, and the full scores view. */
@Composable
private fun ScoresRoot(app: QuietApp, close: () -> Unit) {
    var screen by remember {
        mutableStateOf(if (QuietAccessibilityService.isEnabled(app)) Screen.Settings else Screen.Onboarding)
    }
    // Offer a new build as soon as the app opens (once per build), so it's never left behind.
    val context = LocalContext.current
    LaunchedEffect(Unit) {
        Updater.check(force = true)
        val update = Updater.state as? Updater.State.Available ?: return@LaunchedEffect
        if (app.prefs.cached(OFFERED_BUILD) != update.build.toString()) {
            app.prefs.putCached(OFFERED_BUILD, update.build.toString())
            Updater.install(context)
        }
    }
    Box(
        Modifier.fillMaxSize().background(
            androidx.compose.ui.graphics.Brush.linearGradient(
                listOf(androidx.compose.ui.graphics.Color(0xFF1B2330), androidx.compose.ui.graphics.Color(0xFF0B0E13)),
            ),
        ),
    ) {
        when (screen) {
            Screen.Onboarding -> OnboardingScreen { screen = Screen.Settings }
            Screen.Fantasy -> FantasyPanel(app.prefs) { screen = Screen.Settings }
            else -> SettingsScreen(
                app.prefs,
                onBack = close,
                onHomeSetup = { screen = Screen.Onboarding },
                scoresOnly = true,
                onFantasy = { screen = Screen.Fantasy },
            )
        }
    }
}

fun isNightHour(hour: Int) = hour >= 22 || hour < 6

@Composable
private fun QuietRoot(
    app: QuietApp,
    screen: Screen,
    homeSignal: Int,
    idle: Boolean,
    idleWatch: suspend () -> Unit,
    pickSlot: Int,
    setPickSlot: (Int) -> Unit,
    navigate: (Screen) -> Unit,
) {
    val backdrop = remember { BackdropState() }
    val accent = animateColorAsState(backdrop.accent, tween(1500), label = "accent")
    val ambient = remember { Ambient(app.prefs) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val settingsVersion by app.prefs.changes.collectAsState()
    LaunchedEffect(settingsVersion) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) { ambient.run() }
    }
    LaunchedEffect(Unit) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) { idleWatch() }
    }
    PhotoRotation(backdrop, app.photos, app.prefs) { ambient.weather?.code }
    LaunchedEffect(Unit) {
        // First run (or the service was turned off): explain the Home button setup once.
        if (!app.prefs.onboardingSeen && !QuietAccessibilityService.isEnabled(app)) navigate(Screen.Onboarding)
    }
    LaunchedEffect(Unit) {
        // Network work is scheduled well after the first frame, never on the startup path.
        delay(BACKGROUND_START_DELAY_MS)
        withContext(Dispatchers.IO) { PhotoSyncWorker.schedule(app) }
    }

    val now by rememberMinuteClock()
    val isNight = isNightHour(Calendar.getInstance().apply { timeInMillis = now }.get(Calendar.HOUR_OF_DAY))
    val night = animateFloatAsState(if (isNight) 1f else 0f, tween(3000), label = "night")
    val idleT = animateFloatAsState(if (idle) 1f else 0f, tween(IDLE_FADE_MS), label = "idle")
    LaunchedEffect(backdrop) {
        snapshotFlow { night.value }.collect { backdrop.filter = nightFilter(it) }
    }

    CompositionLocalProvider(LocalAccent provides accent) {
        Box(Modifier.fillMaxSize()) {
            Backdrop(
                backdrop,
                scrim = { 1f - 0.45f * idleT.value },
                amber = { night.value },
            ) {
                if (app.prefs.background == "live") {
                    val ids = remember(settingsVersion) { com.jackdengler.quiet.home.youtubeIds(app.prefs.liveCams) }
                    if (ids.isNotEmpty()) {
                        com.jackdengler.quiet.home.LiveCam(ids) { playing -> backdrop.liveCover = playing }
                    }
                } else {
                    androidx.compose.runtime.SideEffect { backdrop.liveCover = false }
                }
            }
            when (screen) {
                Screen.Home -> HomeScreen(
                    backdrop, app.apps, homeSignal,
                    onAllApps = { navigate(Screen.AllApps) },
                    ambient = ambient,
                    isNight = isNight,
                    isIdle = idle,
                    idle = { idleT.value },
                    night = { night.value },
                    onLongPressSlot = { i ->
                        if (DefaultSlots[i] is Slot.App) {
                            setPickSlot(i)
                            navigate(Screen.PickApp)
                        }
                    },
                    slots = remember(settingsVersion) { effectiveSlots(app.prefs::slotPackage) },
                    onNews = { navigate(Screen.News) },
                    onForecast = { navigate(Screen.Forecast) },
                )
                Screen.Fantasy -> FantasyPanel(app.prefs) { navigate(Screen.AllApps) }
                Screen.News -> NewsPanel(ambient.headlines) { navigate(Screen.Home) }
                Screen.Forecast -> ForecastPanel(ambient.weather, isNight) { navigate(Screen.Home) }
                Screen.PickApp -> AllAppsScreen(
                    app.apps,
                    onBack = { navigate(Screen.Home) },
                    title = "Choose an app for this tile",
                    onPick = {
                        app.prefs.setSlotPackage(pickSlot, it.packageName)
                        navigate(Screen.Home)
                    },
                    extras = listOf(
                        "Reset to default" to {
                            app.prefs.setSlotPackage(pickSlot, null)
                            navigate(Screen.Home)
                        },
                    ),
                )
                Screen.Onboarding -> OnboardingScreen {
                    app.prefs.onboardingSeen = true
                    navigate(Screen.Home)
                }
                Screen.AllApps -> {
                    val context = LocalContext.current
                    val scope = rememberCoroutineScope()
                    LaunchedEffect(Unit) { Updater.check() }
                    val update = Updater.state as? Updater.State.Available
                    AllAppsScreen(
                        app.apps,
                        onBack = { navigate(Screen.Home) },
                        extras = listOfNotNull(
                            "Quiet settings" to { navigate(Screen.Settings) },
                            ("Fantasy" to { navigate(Screen.Fantasy) }).takeIf { app.prefs.espnLeagues.isNotEmpty() },
                            update?.let { "Update Quiet (build ${it.build})" to { scope.launch { Updater.install(context) }; Unit } },
                        ),
                    )
                }
                Screen.Settings -> SettingsScreen(
                    app.prefs,
                    onBack = { navigate(Screen.AllApps) },
                    onHomeSetup = { navigate(Screen.Onboarding) },
                )
            }
        }
    }
}

private const val ROTATE_MS = 60_000L
private const val BACKGROUND_START_DELAY_MS = 8_000L

/** Advances the photo every 60 s while Quiet is visible; stops entirely when it is not. */
@Composable
private fun PhotoRotation(backdrop: BackdropState, photos: PhotoRepository, prefs: Prefs, weatherCode: () -> Int?) {
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(backdrop) {
        val playlist = Playlist(photos)
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            backdrop.resume(this)
            while (true) {
                val now = System.currentTimeMillis()
                val scene = if (prefs.santaMonica) {
                    Sun.scene(Sun.SANTA_MONICA_LAT, Sun.SANTA_MONICA_LON, now)
                } else {
                    Sun.scene(prefs.latitude, prefs.longitude, now)
                }
                val sky = Sun.sky(weatherCode())
                backdrop.mood = Mood(scene, sky)
                val selection = if (prefs.santaMonica) {
                    Selection.santaMonica(scene, sky)
                } else {
                    Selection.timeOfDay(Calendar.getInstance().get(Calendar.HOUR_OF_DAY))
                }
                val source = withContext(Dispatchers.IO) { playlist.next(selection) }
                val photo = source?.let { photos.load(it) }
                if (photo != null) {
                    backdrop.dotCount = playlist.cycleLength
                    backdrop.dotIndex = playlist.position
                    backdrop.show(photo, this)
                }
                delay(ROTATE_MS)
            }
        }
    }
}
