package com.jackdengler.quiet.photos

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.jackdengler.quiet.BuildConfig
import com.jackdengler.quiet.QuietApp
import com.jackdengler.quiet.net.Http
import com.jackdengler.quiet.photos.PhotoRepository.Companion.cover
import com.jackdengler.quiet.photos.PhotoRepository.Companion.sampleSizeFor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

/**
 * Fetches photos.json from the repo, downloads new images, downscales them to 1920×1080 JPEGs in
 * the disk cache and prunes ones no longer listed. Runs only on WorkManager, only with network.
 */
class PhotoSyncWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val syncPrefs = applicationContext.getSharedPreferences("photo_sync", Context.MODE_PRIVATE)
        val cache = PhotoCache(applicationContext)
        val previous = cache.read()

        // 1. photos.json from the repo (ETag: unchanged → keep the entries we already have).
        val response = runCatching { Http.get(PHOTOS_URL, syncPrefs.getString("etag", null)) }.getOrNull()
        val listed: List<PhotoCache.Entry>? = when {
            response == null -> null
            response.code == 304 -> previous.filter { Selection.SANTA_MONICA_TAG !in it.tags }
            else -> response.body?.let { body ->
                runCatching {
                    val arr = JSONObject(body).getJSONArray("photos")
                    List(arr.length()) { i ->
                        val o = arr.getJSONObject(i)
                        val url = o.getString("url")
                        PhotoCache.Entry(fileName(url), url, o.optJSONArray("tags").toStringSet())
                    }
                }.getOrNull()
            }
        }

        // 2. Live Santa Monica set, searched by time of day and weather (only when it's in use).
        val santaMonica = if ((applicationContext as QuietApp).prefs.santaMonica) Openverse.santaMonica(::fileName) else emptyList()
        if (listed == null && santaMonica.isEmpty()) return@withContext Result.retry()
        val oldSantaMonica = previous.filter { Selection.SANTA_MONICA_TAG in it.tags }
        val wanted = (listed ?: previous.filter { Selection.SANTA_MONICA_TAG !in it.tags }) +
            santaMonica.ifEmpty { if ((applicationContext as QuietApp).prefs.santaMonica) oldSantaMonica else emptyList() }

        cache.dir.mkdirs()
        val unique = wanted.distinctBy { it.file }
        // Files already on disk first, so the index never shrinks while new ones download.
        val (have, missing) = unique.partition { File(cache.dir, it.file).exists() }
        val ready = ArrayList(have)
        cache.write(ready)
        for (entry in missing) {
            if (isStopped) break
            if (runCatching { fetch(entry.url, File(cache.dir, entry.file)) }.getOrDefault(false)) {
                ready += entry
                cache.write(ready) // photos appear as they arrive
            }
        }
        if (!isStopped) {
            val keep = ready.map { it.file }.toSet() + "index.json"
            cache.dir.listFiles()?.filter { it.name !in keep }?.forEach { it.delete() }
            if (listed != null) response?.etag?.let { syncPrefs.edit().putString("etag", it).apply() }
        }
        Result.success()
    }

    private fun fetch(url: String, dest: File): Boolean {
        val raw = File(dest.parentFile, dest.name + ".part")
        try {
            if (!Http.download(url, raw)) return false
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(raw.path, bounds)
            if (bounds.outWidth <= 0) return false
            val opts = BitmapFactory.Options().apply {
                inSampleSize = sampleSizeFor(bounds.outWidth, bounds.outHeight, PhotoRepository.SCREEN_W, PhotoRepository.SCREEN_H)
            }
            val bitmap = BitmapFactory.decodeFile(raw.path, opts)?.cover(PhotoRepository.SCREEN_W, PhotoRepository.SCREEN_H)
                ?: return false
            val tmp = File(dest.parentFile, dest.name + ".tmp")
            tmp.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 88, it) }
            bitmap.recycle()
            return tmp.renameTo(dest)
        } finally {
            raw.delete()
        }
    }

    companion object {
        const val PHOTOS_URL = "https://raw.githubusercontent.com/${BuildConfig.REPO}/main/photos.json"

        fun fileName(url: String): String {
            val digest = MessageDigest.getInstance("SHA-1").digest(url.toByteArray())
            return digest.joinToString("") { "%02x".format(it) }.take(20) + ".jpg"
        }

        /** Re-sync right away (e.g. after switching backgrounds). */
        fun syncNow(context: Context) {
            val constraints = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()
            WorkManager.getInstance(context).enqueueUniqueWork(
                "photo-sync-now",
                ExistingWorkPolicy.REPLACE,
                OneTimeWorkRequestBuilder<PhotoSyncWorker>().setConstraints(constraints).build(),
            )
        }

        /** Periodic sync every 6 h plus one now; both wait for a network connection. */
        fun schedule(context: Context) {
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .setRequiresStorageNotLow(true)
                .build()
            val wm = WorkManager.getInstance(context)
            wm.enqueueUniquePeriodicWork(
                "photo-sync",
                ExistingPeriodicWorkPolicy.KEEP,
                PeriodicWorkRequestBuilder<PhotoSyncWorker>(6, TimeUnit.HOURS).setConstraints(constraints).build(),
            )
            wm.enqueueUniqueWork(
                "photo-sync-now",
                ExistingWorkPolicy.KEEP,
                OneTimeWorkRequestBuilder<PhotoSyncWorker>().setConstraints(constraints).build(),
            )
        }
    }
}
