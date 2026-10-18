package com.jackdengler.quiet

import android.app.Application
import android.util.Log
import androidx.work.Configuration
import com.jackdengler.quiet.apps.AppRepository
import com.jackdengler.quiet.data.Prefs
import com.jackdengler.quiet.photos.PhotoRepository

/**
 * Process-wide singletons, created lazily so nothing runs before the first frame. WorkManager's
 * startup initializer is removed in the manifest; it initializes on first use instead.
 */
class QuietApp : Application(), Configuration.Provider {
    companion object {
        /** Cache key holding a one-line description of the last crash. */
        const val LAST_CRASH = "last_crash"
    }

    val apps by lazy { AppRepository(this) }
    val photos by lazy { PhotoRepository(this) }
    val prefs by lazy { Prefs(this) }

    override fun onCreate() {
        super.onCreate()
        // No adb on this TV: keep the last crash so Quiet settings can show it.
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, e ->
            runCatching {
                val where = e.stackTrace.firstOrNull { it.className.startsWith(packageName) } ?: e.stackTrace.firstOrNull()
                val text = "${e.javaClass.simpleName}: ${e.message?.take(160)} at ${where?.className?.substringAfterLast('.')}:${where?.lineNumber}"
                // commit() (not apply()) because the process is about to die.
                getSharedPreferences("quiet_cache", MODE_PRIVATE).edit().putString(LAST_CRASH, text).commit()
            }
            previous?.uncaughtException(thread, e)
        }
    }

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder().setMinimumLoggingLevel(Log.ERROR).build()
}
