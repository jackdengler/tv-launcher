package com.jackdengler.quiet.update

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.net.Uri
import android.provider.Settings
import android.widget.Toast
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.jackdengler.quiet.BuildConfig
import com.jackdengler.quiet.net.Http
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * One-click self-update from this repo's "latest" GitHub release. CI names each release
 * "Quiet (build N)" and uses N as versionCode, so a higher N means a newer APK. Only runs when
 * the user opens settings or all apps, never on startup.
 */
object Updater {
    sealed interface State {
        data object Idle : State
        data object Checking : State
        data object UpToDate : State
        data class Available(val build: Int) : State
        data class Downloading(val percent: Int) : State
        data object Installing : State
        data class Failed(val message: String) : State
    }

    var state by mutableStateOf<State>(State.Idle)
        private set

    private const val API = "https://api.github.com/repos/${BuildConfig.REPO}/releases/latest"
    private const val APK = "https://github.com/${BuildConfig.REPO}/releases/latest/download/quiet.apk"
    private val BUILD = Regex("""build (\d+)""")

    /** Checks at most once per [MIN_CHECK_MS] unless [force]. */
    suspend fun check(force: Boolean = false) {
        if (state is State.Downloading || state is State.Installing) return
        if (!force && System.currentTimeMillis() - lastCheck < MIN_CHECK_MS) return
        state = State.Checking
        val latest = withContext(Dispatchers.IO) {
            runCatching {
                val body = Http.get(API, headers = mapOf("Accept" to "application/vnd.github+json")).body
                body?.let { BUILD.find(JSONObject(it).optString("name"))?.groupValues?.get(1)?.toInt() }
            }.getOrNull()
        }
        lastCheck = System.currentTimeMillis()
        state = when {
            latest == null -> State.Failed("Couldn't reach GitHub")
            latest > BuildConfig.VERSION_CODE -> State.Available(latest)
            else -> State.UpToDate
        }
    }

    private var lastCheck = 0L
    private const val MIN_CHECK_MS = 30 * 60_000L

    /** Downloads the APK and asks Android to install it (one confirmation on screen). */
    suspend fun install(context: Context) {
        val app = context.applicationContext
        if (!app.packageManager.canRequestPackageInstalls()) {
            // One-time: let Quiet install updates. The user turns it on and clicks Update again.
            Toast.makeText(app, "Allow Quiet to install updates, then press Back and choose Update again.", Toast.LENGTH_LONG).show()
            val intent = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${app.packageName}"))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            runCatching { app.startActivity(intent) }
            return
        }
        state = State.Downloading(0)
        val ok = withContext(Dispatchers.IO) {
            runCatching {
                val apk = File(app.cacheDir, "update.apk")
                download(apk) { state = State.Downloading(it) }
                commit(app, apk)
                true
            }.getOrDefault(false)
        }
        state = if (ok) State.Installing else State.Failed("Download failed")
    }

    private fun download(dest: File, progress: (Int) -> Unit) {
        val c = (URL(APK).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15_000
            readTimeout = 30_000
            instanceFollowRedirects = true
        }
        try {
            if (c.responseCode !in 200..299) error("HTTP ${c.responseCode}")
            val total = c.contentLengthLong
            c.inputStream.use { input ->
                dest.outputStream().use { out ->
                    val buf = ByteArray(64 * 1024)
                    var done = 0L
                    var last = -1
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        done += n
                        val pct = if (total > 0) (done * 100 / total).toInt() else 0
                        if (pct != last) { last = pct; progress(pct) }
                    }
                }
            }
        } finally {
            c.disconnect()
        }
    }

    private fun commit(context: Context, apk: File) {
        val installer = context.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
            setAppPackageName(context.packageName)
        }
        val id = installer.createSession(params)
        installer.openSession(id).use { session ->
            session.openWrite("quiet.apk", 0, apk.length()).use { out ->
                apk.inputStream().use { it.copyTo(out) }
                session.fsync(out)
            }
            val callback = PendingIntent.getBroadcast(
                context, id,
                Intent(context, InstallResultReceiver::class.java),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
            )
            session.commit(callback.intentSender)
        }
    }

    internal fun failed(message: String) {
        state = State.Failed(message)
    }
}

/** Receives the installer's result; shows the system "Update this app?" confirmation. */
class InstallResultReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                @Suppress("DEPRECATION")
                val confirm = intent.getParcelableExtra<Intent>(Intent.EXTRA_INTENT) ?: return
                context.startActivity(confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }
            PackageInstaller.STATUS_SUCCESS -> Unit // Quiet restarts as the new version.
            else -> Updater.failed(intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE) ?: "Install failed")
        }
    }
}
