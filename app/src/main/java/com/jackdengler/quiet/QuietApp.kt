package com.jackdengler.quiet

import android.app.Application
import com.jackdengler.quiet.apps.AppRepository
import com.jackdengler.quiet.photos.PhotoRepository

/** Process-wide singletons, created lazily so nothing runs before the first frame. */
class QuietApp : Application() {
    val apps by lazy { AppRepository(this) }
    val photos by lazy { PhotoRepository(this) }
}
