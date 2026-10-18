package com.jackdengler.quiet.photos

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.core.graphics.ColorUtils
import androidx.palette.graphics.Palette
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.io.InputStream

/** A photo the backdrop can show: bundled in the APK or downloaded into the disk cache. */
sealed interface PhotoSource {
    val key: String
    val tags: Set<String>

    /** Attribution shown on screen (required by CC BY licenses); empty for bundled art. */
    val credit: String

    data class Asset(val path: String, override val tags: Set<String>) : PhotoSource {
        override val key get() = "asset:$path"
        override val credit get() = ""
    }

    data class Cached(val file: File, override val tags: Set<String>, override val credit: String = "") : PhotoSource {
        override val key get() = "file:${file.name}"
    }
}

/** A decoded photo, ready to draw: full image, a tiny blurred copy for frosted tiles, and its accent. */
class LoadedPhoto(val key: String, val image: ImageBitmap, val frost: ImageBitmap, val accent: Color, val credit: String = "")

enum class TimeSlot(val tag: String) {
    MORNING("morning"), DAY("day"), NIGHT("night");

    companion object {
        fun at(hour: Int) = when (hour) {
            in 5..10 -> MORNING
            in 11..17 -> DAY
            else -> NIGHT
        }
    }
}

class PhotoRepository(private val context: Context) {

    private val bundled: List<PhotoSource> by lazy {
        val json = context.assets.open("photos/bundled.json").bufferedReader().use { it.readText() }
        val arr = JSONObject(json).getJSONArray("photos")
        List(arr.length()) { i ->
            val o = arr.getJSONObject(i)
            PhotoSource.Asset("photos/" + o.getString("file"), o.optJSONArray("tags").toStringSet())
        }
    }

    /** Every photo available right now. Disk access only; call off the main thread. */
    fun sources(): List<PhotoSource> = PhotoCache(context).entries() + bundled

    suspend fun load(source: PhotoSource): LoadedPhoto? = withContext(Dispatchers.IO) {
        runCatching {
            val full = decodeCover(source, SCREEN_W, SCREEN_H)
            val small = Bitmap.createScaledBitmap(full, FROST_W, FROST_H, true)
            val accent = accentOf(small)
            val frost = small.boxBlur(FROST_RADIUS)
            full.prepareToDraw()
            frost.prepareToDraw()
            LoadedPhoto(source.key, full.asImageBitmap(), frost.asImageBitmap(), accent, source.credit)
        }.getOrNull()
    }

    private fun open(source: PhotoSource): InputStream = when (source) {
        is PhotoSource.Asset -> context.assets.open(source.path)
        is PhotoSource.Cached -> source.file.inputStream()
    }

    private fun decodeCover(source: PhotoSource, w: Int, h: Int): Bitmap {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        open(source).use { BitmapFactory.decodeStream(it, null, bounds) }
        val opts = BitmapFactory.Options().apply {
            inSampleSize = sampleSizeFor(bounds.outWidth, bounds.outHeight, w, h)
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        val decoded = open(source).use { BitmapFactory.decodeStream(it, null, opts) }
            ?: error("undecodable ${source.key}")
        return decoded.cover(w, h)
    }

    companion object {
        const val SCREEN_W = 1920
        const val SCREEN_H = 1080
        private const val FROST_W = 320
        private const val FROST_H = 180
        private const val FROST_RADIUS = 7

        fun sampleSizeFor(srcW: Int, srcH: Int, w: Int, h: Int): Int {
            var s = 1
            while (srcW / (s * 2) >= w && srcH / (s * 2) >= h) s *= 2
            return s
        }

        /** Scales and center-crops to exactly w×h (no-op when it already matches). */
        fun Bitmap.cover(w: Int, h: Int): Bitmap {
            if (width == w && height == h) return this
            val scale = maxOf(w / width.toFloat(), h / height.toFloat())
            val cw = (w / scale).toInt()
            val ch = (h / scale).toInt()
            val src = Rect((width - cw) / 2, (height - ch) / 2, (width + cw) / 2, (height + ch) / 2)
            val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            Canvas(out).drawBitmap(this, src, Rect(0, 0, w, h), Paint(Paint.FILTER_BITMAP_FLAG))
            recycle()
            return out
        }

        /** A light, muted tone from the photo, clamped so it always reads on a dark scrim. */
        fun accentOf(bitmap: Bitmap): Color {
            val palette = Palette.from(bitmap).maximumColorCount(16).generate()
            val swatch = palette.lightMutedSwatch ?: palette.lightVibrantSwatch
                ?: palette.mutedSwatch ?: palette.dominantSwatch ?: return com.jackdengler.quiet.ui.DefaultAccent
            val hsl = swatch.hsl.copyOf()
            hsl[1] = hsl[1].coerceIn(0.15f, 0.45f)
            hsl[2] = hsl[2].coerceIn(0.72f, 0.84f)
            return Color(ColorUtils.HSLToColor(hsl))
        }
    }
}

internal fun org.json.JSONArray?.toStringSet(): Set<String> =
    if (this == null) emptySet() else (0 until length()).map { getString(it).lowercase() }.toSet()

/**
 * Which photos suit right now. [key] identifies the situation (a new key restarts the cycle);
 * [rank] scores each photo: 0 = unsuitable, higher = better match.
 */
class Selection(val key: String, val rank: (PhotoSource) -> Int) {
    companion object {
        /** Photo mix: photos tagged for the current time slot (or untagged). */
        fun timeOfDay(hour: Int): Selection {
            val slot = TimeSlot.at(hour)
            return Selection("mix/${slot.tag}") { p ->
                when {
                    SANTA_MONICA_TAG in p.tags -> 0
                    p.tags.isEmpty() || slot.tag in p.tags -> 2
                    else -> 1
                }
            }
        }

        /** Live Santa Monica: photos matching the real time of day there, then the weather. */
        fun santaMonica(scene: Scene, sky: Sky): Selection = Selection("sm/${scene.tag}/${sky.tag}") { p ->
            val skyTags = Sky.entries.map { it.tag }.filter { it in p.tags }
            when {
                SANTA_MONICA_TAG !in p.tags || scene.tag !in p.tags -> 0
                sky.tag in skyTags -> 3
                skyTags.isEmpty() -> 2
                else -> 1
            }
        }

        const val SANTA_MONICA_TAG = "santa-monica"
    }
}

/**
 * Shuffled cycle through the best-matching photos (at least two; it widens to weaker matches,
 * then to everything, rather than repeat one photo). Never repeats back to back.
 */
class Playlist(private val repository: PhotoRepository) {
    private var cycle: List<PhotoSource> = emptyList()
    private var key: String? = null

    /** Index of the photo last returned by [next], within the current cycle. */
    var position = -1
        private set
    val cycleLength get() = cycle.size

    /** Disk access; call off the main thread. */
    fun next(selection: Selection): PhotoSource? {
        if (position + 1 >= cycle.size || selection.key != key) {
            val last = cycle.getOrNull(position)?.key
            key = selection.key
            val ranked = repository.sources().groupBy(selection.rank)
            var pool = emptyList<PhotoSource>()
            for (r in ranked.keys.filter { it > 0 }.sortedDescending()) {
                pool = pool + ranked.getValue(r)
                if (pool.size >= 2) break
            }
            if (pool.isEmpty()) pool = ranked.values.flatten()
            cycle = pool.shuffled().let { list ->
                if (list.size > 1 && list.first().key == last) list.drop(1) + list.first() else list
            }
            position = -1
        }
        return cycle.getOrNull(++position)
    }
}
