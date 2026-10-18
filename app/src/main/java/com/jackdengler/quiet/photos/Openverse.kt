package com.jackdengler.quiet.photos

import com.jackdengler.quiet.net.Http
import org.json.JSONObject
import java.net.URLEncoder

/**
 * Openverse (openverse.org): openly licensed photos, searched without a key. Used to build the
 * live Santa Monica set, one search per time of day / weather, tagged by the search it came from.
 */
object Openverse {
    private const val API = "https://api.openverse.org/v1/images/"
    private const val PER_QUERY = 6

    /** Search → tags. Time tags: dawn/day/golden/night. Sky tags only where the photo shows weather. */
    private val QUERIES = listOf(
        "santa monica sunrise" to setOf("dawn"),
        "santa monica pier" to setOf("day", "clear"),
        "santa monica beach" to setOf("day", "clear"),
        "santa monica pier cloudy" to setOf("day", "cloudy"),
        "santa monica fog" to setOf("day", "dawn", "fog"),
        "santa monica rain" to setOf("day", "rain"),
        "santa monica pier sunset" to setOf("golden"),
        "santa monica sunset mountains" to setOf("golden"),
        "santa monica pier night" to setOf("night"),
        "santa monica ferris wheel night" to setOf("night"),
    )

    /** Blocking. A failed search is skipped; the others still count. */
    fun santaMonica(fileName: (String) -> String): List<PhotoCache.Entry> = QUERIES.flatMap { (query, tags) ->
        runCatching { search(query) }.getOrDefault(emptyList()).map { hit ->
            PhotoCache.Entry(fileName(hit.url), hit.url, tags + Selection.SANTA_MONICA_TAG, hit.credit)
        }
    }.distinctBy { it.file }

    private class Hit(val url: String, val credit: String)

    private fun search(query: String): List<Hit> {
        val url = API + "?q=" + URLEncoder.encode(query, "UTF-8") +
            "&aspect_ratio=wide&size=large&category=photograph&mature=false&page_size=20"
        val body = Http.get(url, headers = mapOf("Accept" to "application/json")).body ?: return emptyList()
        val results = JSONObject(body).optJSONArray("results") ?: return emptyList()
        return (0 until results.length()).map { results.getJSONObject(it) }
            .filter { o ->
                val w = o.optInt("width")
                val h = o.optInt("height")
                // Big enough for 1080p, landscape, and actually about Santa Monica.
                w >= 1600 && h > 0 && w.toDouble() / h in 1.3..2.4 && !o.optBoolean("mature") &&
                    (o.optString("title") + " " + o.optJSONArray("tags")?.toString().orEmpty())
                        .contains("santa monica", ignoreCase = true)
            }
            .take(PER_QUERY)
            .mapNotNull { o -> o.optString("url").takeIf { it.startsWith("https://") }?.let { Hit(it, credit(o)) } }
    }

    private fun credit(o: JSONObject): String {
        val who = o.optString("creator").ifBlank { o.optString("provider") }
        val license = when (val l = o.optString("license").lowercase()) {
            "cc0", "pdm" -> "public domain"
            "" -> ""
            else -> "CC ${l.uppercase()} ${o.optString("license_version")}".trim()
        }
        return listOf("Photo: $who".takeIf { who.isNotBlank() }, license.ifBlank { null }).filterNotNull().joinToString(" · ")
    }
}
