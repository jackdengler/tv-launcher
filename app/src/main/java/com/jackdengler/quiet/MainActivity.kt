package com.jackdengler.quiet

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import com.jackdengler.quiet.apps.AllAppsScreen
import com.jackdengler.quiet.home.Backdrop
import com.jackdengler.quiet.home.BackdropState
import com.jackdengler.quiet.home.HomeScreen
import com.jackdengler.quiet.photos.PhotoRepository
import com.jackdengler.quiet.photos.Playlist
import com.jackdengler.quiet.ui.DesignPixels
import com.jackdengler.quiet.ui.LocalAccent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.util.Calendar

enum class Screen { Home, AllApps }

class MainActivity : ComponentActivity() {
    private var screen by mutableStateOf(Screen.Home)
    private var homeSignal by mutableIntStateOf(0)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val app = application as QuietApp
        setContent {
            DesignPixels {
                QuietRoot(app, screen, homeSignal) { screen = it }
            }
        }
    }

    /** Home pressed while Quiet is already in front: back to the tile grid. */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        screen = Screen.Home
        homeSignal++
    }
}

@Composable
private fun QuietRoot(app: QuietApp, screen: Screen, homeSignal: Int, navigate: (Screen) -> Unit) {
    val backdrop = remember { BackdropState() }
    val accent = animateColorAsState(backdrop.accent, tween(1500), label = "accent")
    PhotoRotation(backdrop, app.photos)

    CompositionLocalProvider(LocalAccent provides accent) {
        Box(Modifier.fillMaxSize()) {
            Backdrop(backdrop)
            when (screen) {
                Screen.Home -> HomeScreen(
                    backdrop, app.apps, homeSignal,
                    onAllApps = { navigate(Screen.AllApps) },
                )
                Screen.AllApps -> AllAppsScreen(app.apps, onBack = { navigate(Screen.Home) })
            }
        }
    }
}

private const val ROTATE_MS = 60_000L

/** Advances the photo every 60 s while Quiet is visible; stops entirely when it is not. */
@Composable
private fun PhotoRotation(backdrop: BackdropState, photos: PhotoRepository) {
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(backdrop) {
        val playlist = Playlist(photos)
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            backdrop.resume(this)
            while (true) {
                val hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
                val source = withContext(Dispatchers.IO) { playlist.next(hour) }
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
