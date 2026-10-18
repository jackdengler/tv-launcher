package com.jackdengler.quiet.home

import android.annotation.SuppressLint
import android.graphics.Color
import android.os.Handler
import android.os.Looper
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebView
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.delay
import org.json.JSONArray

/** Pulls YouTube video ids out of links ("watch?v=", "youtu.be/", "/live/") or bare ids. */
fun youtubeIds(text: String): List<String> = Regex("""(?:v=|youtu\.be/|/live/|/embed/|^|\s)([A-Za-z0-9_-]{11})(?=$|[\s&?/#])""")
    .findAll(text).map { it.groupValues[1] }.distinct().toList()

/**
 * A live camera as the background: a muted, chromeless YouTube player scaled to cover the
 * screen. It stays invisible until the stream is really playing, so the photos underneath show
 * while it loads or if every stream fails ([onPlaying] reports which). It pauses whenever Quiet
 * isn't visible and rejoins the live edge when it comes back.
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun LiveCam(videoIds: List<String>, onPlaying: (Boolean) -> Unit) {
    var playing by remember { mutableStateOf(false) }
    val alpha by animateFloatAsState(if (playing) 1f else 0f, tween(1800), label = "cam")
    val report = remember(onPlaying) {
        { p: Boolean ->
            playing = p
            onPlaying(p)
        }
    }
    var web by remember { mutableStateOf<WebView?>(null) }

    // Nothing playing after a while: give up quietly and keep the photos.
    LaunchedEffect(videoIds) {
        delay(30_000)
        if (!playing) report(false)
    }

    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle, web) {
        val observer = LifecycleEventObserver { _, event ->
            val w = web ?: return@LifecycleEventObserver
            when (event) {
                Lifecycle.Event.ON_STOP -> {
                    w.evaluateJavascript("player && player.pauseVideo && player.pauseVideo()", null)
                    w.onPause()
                }
                Lifecycle.Event.ON_START -> {
                    w.onResume()
                    w.evaluateJavascript("load()", null) // back to the live edge
                }
                else -> Unit
            }
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
    DisposableEffect(Unit) {
        onDispose {
            web?.destroy()
            report(false)
        }
    }

    AndroidView(
        modifier = Modifier.fillMaxSize().graphicsLayer { this.alpha = alpha },
        factory = { context ->
            WebView(context).apply {
                setBackgroundColor(Color.TRANSPARENT)
                isFocusable = false
                isFocusableInTouchMode = false
                settings.javaScriptEnabled = true
                settings.domStorageEnabled = true
                settings.mediaPlaybackRequiresUserGesture = false
                webChromeClient = WebChromeClient()
                val main = Handler(Looper.getMainLooper())
                addJavascriptInterface(
                    object {
                        @JavascriptInterface fun playing(p: Boolean) { main.post { report(p) } }
                        @JavascriptInterface fun failed() { main.post { report(false) } }
                    },
                    "Quiet",
                )
                loadDataWithBaseURL(ORIGIN, page(videoIds), "text/html", "utf-8", null)
                web = this
            }
        },
    )
}

private const val ORIGIN = "https://github.com"

/** YouTube IFrame player, muted and without controls, covering the screen; tries each id in turn. */
private fun page(ids: List<String>) = """
<!doctype html><html><head><meta name="viewport" content="width=device-width,initial-scale=1">
<style>
  html,body{margin:0;height:100%;overflow:hidden;background:transparent}
  #wrap{position:absolute;inset:0;overflow:hidden}
  #p{position:absolute;top:50%;left:50%;width:100vw;height:56.25vw;min-height:100vh;min-width:177.78vh;
     transform:translate(-50%,-50%) scale(1.02);pointer-events:none}
</style></head>
<body><div id="wrap"><div id="p"></div></div>
<script>
  var ids = ${JSONArray(ids)}, i = 0, player = null;
  function load() {
    if (!window.YT || !YT.Player) return;
    if (player) { try { player.destroy(); } catch (e) {} 
      if (!document.getElementById('p')) { var d = document.createElement('div'); d.id = 'p'; document.getElementById('wrap').appendChild(d); } }
    player = new YT.Player('p', {
      videoId: ids[i],
      playerVars: { autoplay: 1, mute: 1, controls: 0, disablekb: 1, fs: 0, rel: 0, modestbranding: 1,
                    playsinline: 1, iv_load_policy: 3, origin: '$ORIGIN' },
      events: {
        onReady: function (e) { e.target.mute(); e.target.playVideo(); },
        onStateChange: function (e) {
          if (e.data === 1) Quiet.playing(true);
          if (e.data === 0) { Quiet.playing(false); load(); }
        },
        onError: function () {
          Quiet.playing(false);
          i++;
          if (i < ids.length) load(); else Quiet.failed();
        }
      }
    });
  }
  function onYouTubeIframeAPIReady() { load(); }
  var tag = document.createElement('script');
  tag.src = 'https://www.youtube.com/iframe_api';
  document.body.appendChild(tag);
</script></body></html>
""".trimIndent()
