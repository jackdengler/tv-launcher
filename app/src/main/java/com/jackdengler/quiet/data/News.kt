package com.jackdengler.quiet.data

import android.util.Xml
import com.jackdengler.quiet.net.Http
import org.json.JSONArray
import org.json.JSONObject
import org.xmlpull.v1.XmlPullParser
import java.io.StringReader

data class Headline(
    val title: String,
    val source: String,
    val summary: String = "",
    val link: String = "",
    /** Publish time (epoch ms), 0 when unknown. */
    val published: Long = 0L,
)

/** RSS 2.0 / Atom headline reader (title + feed name). Blocking; call from Dispatchers.IO. */
object News {
    private const val MAX = 12
    private const val MAX_TOTAL = 24

    /** Fetches every feed and interleaves them (A1, B1, A2, B2, …); a failing feed is skipped. */
    fun fetchAll(urls: List<String>): List<Headline> {
        val lists = urls.map { url -> runCatching { fetch(url) }.getOrDefault(emptyList()) }
        val longest = lists.maxOfOrNull { it.size } ?: 0
        return (0 until longest).flatMap { i -> lists.mapNotNull { it.getOrNull(i) } }.take(MAX_TOTAL)
    }

    fun fetch(url: String): List<Headline> {
        val body = Http.get(url).body ?: return emptyList()
        return parse(body, knownSource(url))
    }

    /** Short tags for feeds whose titles are long or generic. */
    private fun knownSource(url: String): String? {
        val host = runCatching { java.net.URI(url).host.orEmpty() }.getOrDefault("")
        return when {
            "dowjones" in host || "wsj" in host || "dj.com" in host -> "WSJ"
            "variety.com" in host -> "Variety"
            "bbc" in host -> "BBC News"
            else -> null
        }
    }

    fun parse(xml: String, sourceOverride: String? = null): List<Headline> {
        val parser = Xml.newPullParser()
        parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
        parser.setInput(StringReader(xml))
        var feedTitle = ""
        val items = ArrayList<Item>()
        var item: Item? = null
        while (parser.next() != XmlPullParser.END_DOCUMENT && items.size < MAX) {
            when (parser.eventType) {
                XmlPullParser.START_TAG -> {
                    val current = item
                    when (parser.name) {
                        "item", "entry" -> item = Item()
                        "title" -> {
                            val text = readText(parser)
                            if (current != null) current.title = text else if (feedTitle.isEmpty()) feedTitle = text
                        }
                        "description", "summary", "content:encoded", "content" ->
                            if (current != null && current.summary.isEmpty()) current.summary = readText(parser)
                        "link" -> if (current != null && current.link.isEmpty()) {
                            // RSS: <link>url</link>; Atom: <link href="url" rel="alternate"/>
                            val href = parser.getAttributeValue(null, "href")
                            val rel = parser.getAttributeValue(null, "rel")
                            current.link = if (href != null) href.takeIf { rel == null || rel == "alternate" }.orEmpty() else readText(parser)
                        }
                        "pubDate", "published", "updated", "dc:date" ->
                            if (current != null && current.published == 0L) current.published = parseDate(readText(parser))
                    }
                }
                XmlPullParser.END_TAG -> if (parser.name == "item" || parser.name == "entry") {
                    item?.takeIf { it.title.isNotBlank() }?.let(items::add)
                    item = null
                }
            }
        }
        val source = sourceOverride ?: shortSource(feedTitle)
        return items.map { Headline(it.title, source, cleanSummary(it.summary), it.link.trim(), it.published) }
    }

    private class Item {
        var title = ""
        var summary = ""
        var link = ""
        var published = 0L
    }

    /** Text content of the current element, including any nested markup, as plain text. */
    private fun readText(parser: XmlPullParser): String {
        val sb = StringBuilder()
        var depth = 1
        while (depth > 0) {
            when (parser.next()) {
                XmlPullParser.START_TAG -> depth++
                XmlPullParser.END_TAG -> depth--
                XmlPullParser.TEXT, XmlPullParser.CDSECT -> sb.append(parser.text)
                XmlPullParser.END_DOCUMENT -> break
            }
        }
        return sb.toString().trim()
    }

    private fun cleanSummary(raw: String): String {
        if (raw.isBlank()) return ""
        val text = android.text.Html.fromHtml(raw, android.text.Html.FROM_HTML_MODE_COMPACT).toString()
            .replace('\uFFFC', ' ').replace(Regex("\\s+"), " ").trim()
        return if (text.length > 360) text.take(357).trimEnd() + "…" else text
    }

    private val DATE_FORMATS = listOf("EEE, dd MMM yyyy HH:mm:ss Z", "EEE, dd MMM yyyy HH:mm:ss zzz", "yyyy-MM-dd'T'HH:mm:ssXXX", "yyyy-MM-dd'T'HH:mm:ss.SSSXXX")

    private fun parseDate(s: String): Long {
        for (f in DATE_FORMATS) {
            runCatching { return java.text.SimpleDateFormat(f, java.util.Locale.US).parse(s.trim())!!.time }
        }
        return 0L
    }

    /** "BBC News - Home" -> "BBC News". */
    private fun shortSource(title: String) =
        title.split(" - ", " | ", " – ").first().trim().ifBlank { "News" }

    /** Headlines plus when and from which feed URL they were fetched. */
    data class Snapshot(val items: List<Headline>, val fetchedAt: Long, val url: String)

    fun encode(s: Snapshot): String = JSONObject()
        .put("at", s.fetchedAt)
        .put("url", s.url)
        .put(
            "items",
            JSONArray().apply {
                s.items.forEach {
                    put(JSONObject().put("t", it.title).put("s", it.source).put("d", it.summary).put("l", it.link).put("p", it.published))
                }
            },
        )
        .toString()

    fun decode(s: String?): Snapshot? = s?.let {
        runCatching {
            val o = JSONObject(it)
            val arr = o.getJSONArray("items")
            val items = List(arr.length()) { i ->
                arr.getJSONObject(i).let { h ->
                    Headline(h.getString("t"), h.getString("s"), h.optString("d"), h.optString("l"), h.optLong("p"))
                }
            }
            Snapshot(items, o.getLong("at"), o.getString("url"))
        }.getOrNull()
    }
}
