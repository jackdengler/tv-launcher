package com.jackdengler.quiet

import android.app.Application
import android.util.Log
import androidx.work.Configuration
import com.jackdengler.quiet.apps.AppRepository
import com.jackdengler.quiet.photos.PhotoRepository

/**
 * Process-wide singletons, created lazily so nothing runs before the first frame. WorkManager's
 * startup initializer is removed in the manifest; it initializes on first use instead.
 */
class QuietApp : Application(), Configuration.Provider {
    val apps by lazy { AppRepository(this) }
    val photos by lazy { PhotoRepository(this) }

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder().setMinimumLoggingLevel(Log.ERROR).build()
}
