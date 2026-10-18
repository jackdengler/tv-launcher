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
        val app = applicationContext as QuietApp
        val fromCommons = if (app.prefs.santaMonica) Commons.santaMonica(::fileName) else emptyList()
        val fromOpenverse = if (app.prefs.santaMonica) Openverse.santaMonica(::fileName) else emptyList()
        val santaMonica = (fromCommons + fromOpenverse).distinctBy { it.file }
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
        var failed = 0
        for (entry in missing) {
            if (isStopped) break
            val luma = runCatching { fetch(entry.url, File(cache.dir, entry.file)) }.getOrNull()
            if (luma != null) {
                ready += entry.copy(tags = retag(entry.tags, luma))
                cache.write(ready) // photos appear as they arrive
            } else {
                failed++
            }
        }
        val time = java.text.DateFormat.getTimeInstance(java.text.DateFormat.SHORT).format(java.util.Date())
        val smReady = ready.count { Selection.SANTA_MONICA_TAG in it.tags }
        app.prefs.putCached(
            STATUS,
            "Synced $time · Santa Monica photos: $smReady (found ${fromCommons.size} on Wikimedia, " +
                "${fromOpenverse.size} on Openverse) · photos.json: ${if (listed == null) "unreachable" else listed.size}" +
                if (failed > 0) " · $failed downloads failed" else "",
        )
        if (!isStopped) {
            val keep = ready.map { it.file }.toSet() + "index.json"
            cache.dir.listFiles()?.filter { it.name !in keep }?.forEach { it.delete() }
            if (listed != null) response?.etag?.let { syncPrefs.edit().putString("etag", it).apply() }
        }
        Result.success()
    }

    /**
     * Search keywords aren't proof: a "night" result can be a daytime shot. Average brightness
     * decides between night and daylight tags.
     */
    private fun retag(tags: Set<String>, luma: Float): Set<String> {
        if (Selection.SANTA_MONICA_TAG !in tags) return tags
        return when {
            "night" in tags && luma > 0.38f -> tags - "night" + "day"
            "night" !in tags && luma < 0.16f -> tags - setOf("day", "dawn", "golden", "clear", "cloudy", "fog") + "night"
            else -> tags
        }
    }

    /** Downloads, downscales and stores a photo; returns its average brightness (0..1) or null. */
    private fun fetch(url: String, dest: File): Float? {
        val raw = File(dest.parentFile, dest.name + ".part")
        try {
            if (!Http.download(url, raw)) return null
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(raw.path, bounds)
            if (bounds.outWidth <= 0) return null
            val opts = BitmapFactory.Options().apply {
                inSampleSize = sampleSizeFor(bounds.outWidth, bounds.outHeight, PhotoRepository.SCREEN_W, PhotoRepository.SCREEN_H)
            }
            val bitmap = BitmapFactory.decodeFile(raw.path, opts)?.cover(PhotoRepository.SCREEN_W, PhotoRepository.SCREEN_H)
                ?: return null
            val luma = averageLuma(bitmap)
            val tmp = File(dest.parentFile, dest.name + ".tmp")
            tmp.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 88, it) }
            bitmap.recycle()
            return if (tmp.renameTo(dest)) luma else null
        } finally {
            raw.delete()
        }
    }

    private fun averageLuma(bitmap: Bitmap): Float {
        val small = Bitmap.createScaledBitmap(bitmap, 64, 36, true)
        val px = IntArray(64 * 36)
        small.getPixels(px, 0, 64, 0, 0, 64, 36)
        small.recycle()
        return px.sumOf { c ->
            (0.2126 * ((c shr 16) and 0xFF) + 0.7152 * ((c shr 8) and 0xFF) + 0.0722 * (c and 0xFF)) / 255.0
        }.toFloat() / px.size
    }

    companion object {
        /** Cache key for the last sync's summary, shown in settings. */
        const val STATUS = "photo_status"

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
