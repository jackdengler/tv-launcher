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
        val prefs = applicationContext.getSharedPreferences("photo_sync", Context.MODE_PRIVATE)
        val cache = PhotoCache(applicationContext)
        val response = runCatching { Http.get(PHOTOS_URL, prefs.getString("etag", null)) }
            .getOrElse { return@withContext Result.retry() }
        if (response.code == 304) return@withContext Result.success()
        val body = response.body ?: return@withContext Result.retry()

        val wanted = runCatching {
            val arr = JSONObject(body).getJSONArray("photos")
            List(arr.length()) { i ->
                val o = arr.getJSONObject(i)
                val url = o.getString("url")
                PhotoCache.Entry(fileName(url), url, o.optJSONArray("tags").toStringSet())
            }
        }.getOrElse { return@withContext Result.failure() }

        cache.dir.mkdirs()
        val ready = ArrayList<PhotoCache.Entry>()
        for (entry in wanted.distinctBy { it.file }) {
            if (isStopped) break
            val file = File(cache.dir, entry.file)
            if (file.exists() || runCatching { fetch(entry.url, file) }.getOrDefault(false)) ready += entry
            // Publish progress so photos appear as they arrive, not after the whole batch.
            cache.write(ready)
        }
        if (!isStopped) {
            val keep = ready.map { it.file }.toSet() + "index.json"
            cache.dir.listFiles()?.filter { it.name !in keep }?.forEach { it.delete() }
            response.etag?.let { prefs.edit().putString("etag", it).apply() }
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

        private fun fileName(url: String): String {
            val digest = MessageDigest.getInstance("SHA-1").digest(url.toByteArray())
            return digest.joinToString("") { "%02x".format(it) }.take(20) + ".jpg"
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
