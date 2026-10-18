package com.jackdengler.quiet.photos

import com.jackdengler.quiet.net.Http
import org.json.JSONObject
import java.net.URLEncoder

/**
 * Wikimedia Commons: freely licensed photos, searched without a key. The main source for the
 * live Santa Monica set; each search is tagged with the time of day / weather it looks for.
 * Results come back as 1920-px-wide thumbnails served by Wikimedia.
 */
object Commons {
    private const val API = "https://commons.wikimedia.org/w/api.php"
    private const val PER_QUERY = 8

    private val QUERIES = listOf(
        "Santa Monica Pier sunset" to setOf("golden"),
        "Santa Monica beach sunset" to setOf("golden"),
        "Santa Monica Pier night" to setOf("night"),
        "Pacific Park Santa Monica night" to setOf("night"),
        "Santa Monica Pier" to setOf("day", "clear"),
        "Santa Monica State Beach" to setOf("day", "clear"),
        "Santa Monica Pier fog" to setOf("day", "dawn", "fog"),
        "Santa Monica cloudy" to setOf("day", "cloudy"),
        "Santa Monica sunrise" to setOf("dawn"),
    )

    /** Old photos (dated 1800s–1970s in the title) are skipped: we want today's pier. */
    private val HISTORIC = Regex("""\b(18\d\d|19[0-7]\d)""")

    /** Blocking. Returns the entries plus a short per-source note for the status line. */
    fun santaMonica(fileName: (String) -> String): List<PhotoCache.Entry> = QUERIES.flatMap { (query, tags) ->
        runCatching { search(query) }.getOrDefault(emptyList()).map { hit ->
            PhotoCache.Entry(fileName(hit.first), hit.first, tags + Selection.SANTA_MONICA_TAG, hit.second)
        }
    }.distinctBy { it.file }

    private fun search(query: String): List<Pair<String, String>> {
        val url = API + "?action=query&format=json&formatversion=2&generator=search&gsrnamespace=6&gsrlimit=40" +
            "&gsrsearch=" + URLEncoder.encode(query, "UTF-8") +
            "&prop=imageinfo&iiprop=url%7Csize%7Cmime%7Cextmetadata&iiextmetadatafilter=Artist%7CLicenseShortName&iiurlwidth=1920"
        val body = Http.get(url).body ?: return emptyList()
        val pages = JSONObject(body).optJSONObject("query")?.optJSONArray("pages") ?: return emptyList()
        return (0 until pages.length()).map { pages.getJSONObject(it) }
            .sortedBy { it.optInt("index", Int.MAX_VALUE) }
            .mapNotNull { page ->
                val title = page.optString("title")
                val info = page.optJSONArray("imageinfo")?.optJSONObject(0) ?: return@mapNotNull null
                val w = info.optInt("width")
                val h = info.optInt("height")
                val thumb = info.optString("thumburl")
                val ok = info.optString("mime") == "image/jpeg" && w >= 1600 && h > 0 &&
                    w.toDouble() / h in 1.3..2.4 && thumb.startsWith("https://") &&
                    title.contains("santa monica", ignoreCase = true) && !HISTORIC.containsMatchIn(title)
                if (!ok) return@mapNotNull null
                val meta = info.optJSONObject("extmetadata")
                val artist = meta?.optJSONObject("Artist")?.optString("value").orEmpty().stripHtml()
                val license = meta?.optJSONObject("LicenseShortName")?.optString("value").orEmpty().stripHtml()
                val credit = listOfNotNull(
                    artist.takeIf { it.isNotBlank() }?.let { "Photo: ${it.take(40)}" },
                    license.takeIf { it.isNotBlank() },
                    "Wikimedia Commons",
                ).joinToString(" · ")
                thumb to credit
            }
            .take(PER_QUERY)
    }

    private fun String.stripHtml() = replace(Regex("<[^>]*>"), "").replace("&amp;", "&").replace(Regex("\\s+"), " ").trim()
}
