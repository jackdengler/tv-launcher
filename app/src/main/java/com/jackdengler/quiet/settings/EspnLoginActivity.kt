package com.jackdengler.quiet.settings

import android.os.Bundle
import android.view.KeyEvent
import android.webkit.CookieManager
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import com.jackdengler.quiet.QuietApp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Sign in to ESPN on the TV and capture the espn_s2 / SWID cookies a private league needs, so no
 * computer is required. Cookies stay on the TV (Quiet's private prefs); nothing is sent anywhere
 * except ESPN.
 */
class EspnLoginActivity : ComponentActivity() {
    private lateinit var web: WebView
    private var saved = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val cookies = CookieManager.getInstance()
        cookies.setAcceptCookie(true)
        web = WebView(this).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView, url: String) {
                    capture()
                }
            }
        }
        cookies.setAcceptThirdPartyCookies(web, true)
        setContentView(web)
        web.loadUrl(LOGIN_URL)
        web.requestFocus()
        // The sign-in form posts inside an iframe, so also poll rather than rely on page loads.
        lifecycleScope.launch {
            while (!saved) {
                delay(1_000)
                capture()
            }
        }
    }

    private fun capture() {
        if (saved) return
        val manager = CookieManager.getInstance()
        val all = COOKIE_URLS.mapNotNull { manager.getCookie(it) }.joinToString("; ")
        val jar = all.split(";").mapNotNull { part ->
            val i = part.indexOf('=')
            if (i <= 0) null else part.substring(0, i).trim() to part.substring(i + 1).trim()
        }.toMap()
        // SWID is given to anonymous visitors too; espn_s2 only exists after signing in.
        val s2 = jar["espn_s2"]?.takeIf { it.isNotBlank() } ?: return
        val swid = jar["SWID"]?.takeIf { it.isNotBlank() } ?: return
        saved = true
        val prefs = (application as QuietApp).prefs
        prefs.espnS2 = s2
        prefs.espnSwid = swid
        Toast.makeText(this, "Signed in. ESPN cookies saved.", Toast.LENGTH_LONG).show()
        finish()
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (keyCode == KeyEvent.KEYCODE_BACK && web.canGoBack()) {
            web.goBack()
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onDestroy() {
        web.destroy()
        super.onDestroy()
    }

    private companion object {
        const val LOGIN_URL = "https://www.espn.com/login"
        val COOKIE_URLS = listOf("https://fantasy.espn.com", "https://www.espn.com", "https://espn.com")
    }
}
