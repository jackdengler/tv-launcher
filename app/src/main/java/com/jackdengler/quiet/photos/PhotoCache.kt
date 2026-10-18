package com.jackdengler.quiet.photos

import android.content.Context
import org.json.JSONObject
import java.io.File

/**
 * Downloaded photos on disk (filesDir/photos), plus an index of their tags.
 * Phase 2 fills this; until then it is empty.
 */
class PhotoCache(context: Context) {
    val dir = File(context.filesDir, "photos")
    private val index = File(dir, "index.json")

    fun entries(): List<PhotoSource> {
        if (!index.exists()) return emptyList()
        return runCatching {
            val arr = JSONObject(index.readText()).getJSONArray("photos")
            (0 until arr.length()).mapNotNull { i ->
                val o = arr.getJSONObject(i)
                val file = File(dir, o.getString("file"))
                if (file.exists()) PhotoSource.Cached(file, o.optJSONArray("tags").toStringSet()) else null
            }
        }.getOrDefault(emptyList())
    }
}
