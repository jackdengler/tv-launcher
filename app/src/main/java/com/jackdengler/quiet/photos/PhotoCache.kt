package com.jackdengler.quiet.photos

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** Downloaded photos on disk (filesDir/photos), plus an index of their tags. */
class PhotoCache(context: Context) {
    val dir = File(context.filesDir, "photos")
    private val index = File(dir, "index.json")

    data class Entry(val file: String, val url: String, val tags: Set<String>, val credit: String = "")

    fun indexModified(): Long = index.lastModified()

    fun entries(): List<PhotoSource> = read().mapNotNull { e ->
        File(dir, e.file).takeIf { it.exists() }?.let { PhotoSource.Cached(it, e.tags, e.credit) }
    }

    fun read(): List<Entry> {
        if (!index.exists()) return emptyList()
        return runCatching {
            val arr = JSONObject(index.readText()).getJSONArray("photos")
            List(arr.length()) { i ->
                val o = arr.getJSONObject(i)
                Entry(o.getString("file"), o.optString("url"), o.optJSONArray("tags").toStringSet(), o.optString("credit"))
            }
        }.getOrDefault(emptyList())
    }

    /** Atomically replaces the index (write to a temp file, then rename). */
    fun write(entries: List<Entry>) {
        dir.mkdirs()
        val arr = JSONArray()
        for (e in entries) {
            arr.put(
                JSONObject().put("file", e.file).put("url", e.url).put("tags", JSONArray(e.tags.toList())).put("credit", e.credit),
            )
        }
        val tmp = File(dir, "index.json.tmp")
        tmp.writeText(JSONObject().put("photos", arr).toString())
        tmp.renameTo(index)
    }
}
