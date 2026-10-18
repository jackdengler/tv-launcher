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

    fun fetch(url: String): List<Headline> {
        val body = Http.get(url).body ?: return emptyList()
        return parse(body)
    }

    fun parse(xml: String): List<Headline> {
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
        val source = shortSource(feedTitle)
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
