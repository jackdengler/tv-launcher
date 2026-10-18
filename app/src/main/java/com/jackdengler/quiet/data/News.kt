package com.jackdengler.quiet.data

import android.util.Xml
import com.jackdengler.quiet.net.Http
import org.json.JSONArray
import org.json.JSONObject
import org.xmlpull.v1.XmlPullParser
import java.io.StringReader

data class Headline(val title: String, val source: String)

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
        val titles = ArrayList<String>()
        var inItem = false
        var depthTitleSeen = false
        while (parser.next() != XmlPullParser.END_DOCUMENT && titles.size < MAX) {
            when (parser.eventType) {
                XmlPullParser.START_TAG -> when (parser.name) {
                    "item", "entry" -> inItem = true
                    "title" -> {
                        val text = parser.nextText().trim()
                        if (inItem) {
                            titles.add(text)
                        } else if (!depthTitleSeen) {
                            feedTitle = text
                            depthTitleSeen = true
                        }
                    }
                }
                XmlPullParser.END_TAG -> if (parser.name == "item" || parser.name == "entry") inItem = false
            }
        }
        val source = sourceOverride ?: shortSource(feedTitle)
        return titles.filter { it.isNotBlank() }.map { Headline(it, source) }
    }

    /** "BBC News - Home" -> "BBC News". */
    private fun shortSource(title: String) =
        title.split(" - ", " | ", " – ").first().trim().ifBlank { "News" }

    /** Headlines plus when and from which feed URL they were fetched. */
    data class Snapshot(val items: List<Headline>, val fetchedAt: Long, val url: String)

    fun encode(s: Snapshot): String = JSONObject()
        .put("at", s.fetchedAt)
        .put("url", s.url)
        .put("items", JSONArray().apply { s.items.forEach { put(JSONObject().put("t", it.title).put("s", it.source)) } })
        .toString()

    fun decode(s: String?): Snapshot? = s?.let {
        runCatching {
            val o = JSONObject(it)
            val arr = o.getJSONArray("items")
            val items = List(arr.length()) { i -> arr.getJSONObject(i).let { h -> Headline(h.getString("t"), h.getString("s")) } }
            Snapshot(items, o.getLong("at"), o.getString("url"))
        }.getOrNull()
    }
}
