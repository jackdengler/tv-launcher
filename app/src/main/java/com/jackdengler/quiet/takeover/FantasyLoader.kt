package com.jackdengler.quiet.takeover

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.jackdengler.quiet.data.Espn
import com.jackdengler.quiet.data.FantasyScore
import com.jackdengler.quiet.data.Nfl
import com.jackdengler.quiet.data.Prefs
import org.json.JSONArray
import java.net.HttpURLConnection
import java.net.URL
import java.util.Calendar

/**
 * Loads every configured league for the overlay and for Quiet's fantasy panel: scores, team
 * logos (cached in memory) and the win-chance history (kept per league and week).
 * Blocking; call from Dispatchers.IO.
 */
object FantasyLoader {
    class Result(val cards: List<Pair<Int, LeagueCard>>, val errors: List<String>, val games: List<com.jackdengler.quiet.data.NflGame> = emptyList())

    private val logos = HashMap<String, Bitmap?>()
    private val lastLead = HashMap<Int, Pair<String, Int>>()
    private const val MAX_HISTORY = 240

    /** [onlyShown]: the overlay skips leagues switched off; the panel shows everything. */
    fun load(prefs: Prefs, onlyShown: Boolean, now: Calendar = Calendar.getInstance()): Result {
        val games = runCatching { Nfl.scoreboard() }.getOrDefault(emptyList())
        val states = Nfl.states(games)
        val cards = ArrayList<Pair<Int, LeagueCard>>()
        val errors = ArrayList<String>()
        for ((n, league, team) in prefs.espnLeagues) {
            if (onlyShown && !(if (n == 1) prefs.espnShow1 else prefs.espnShow2)) continue
            runCatching { Espn.fetch(league, team, prefs.espnS2, prefs.espnSwid, now, states) }
                .onSuccess { score -> cards += n to card(prefs, n, league, team, score, errors) }
                .onFailure { errors += "League $n: ${it.message ?: it.javaClass.simpleName}" }
        }
        return Result(cards, errors, games)
    }

    private fun card(prefs: Prefs, n: Int, league: String, team: String, score: FantasyScore, problems: MutableList<String>): LeagueCard {
        // Win-chance history for this matchup (one point per refresh).
        val key = "wp_${league}_${team}_${score.week}"
        val history = runCatching { JSONArray(prefs.cached(key) ?: "[]") }.getOrDefault(JSONArray())
        history.put(score.winProbability)
        while (history.length() > MAX_HISTORY) history.remove(0)
        prefs.putCached(key, history.toString())
        val wp = FloatArray(history.length()) { history.optDouble(it, 0.5).toFloat() }

        // Lead change since the last refresh of this league and week.
        val leadKey = "$league/$team/${score.week}"
        val previous = lastLead[n]?.takeIf { it.first == leadKey }?.second
        lastLead[n] = leadKey to score.lead
        val change = if (previous != null && previous != score.lead && score.lead != 0) score.lead else 0

        val cookie = if (prefs.espnS2.isNotBlank() && prefs.espnSwid.isNotBlank()) "espn_s2=${prefs.espnS2}; SWID=${prefs.espnSwid}" else null
        return LeagueCard(score, wp, logo(score.me.logo, cookie, problems), logo(score.opp.logo, cookie, problems), change)
    }

    /**
     * Team picture as a bitmap, or null. Handles http links (upgraded to https, since Android
     * blocks plain http), ESPN-hosted images that need the account cookies, and SVG (ESPN's
     * default avatars), which is rendered with AndroidSVG.
     */
    private fun logo(url: String, cookie: String?, problems: MutableList<String>): Bitmap? {
        if (url.isBlank()) return null
        synchronized(logos) { if (url in logos) return logos[url] }
        val secure = when {
            url.startsWith("https://") -> url
            url.startsWith("http://") -> "https://" + url.removePrefix("http://")
            url.startsWith("//") -> "https:$url"
            else -> {
                problems += "Team picture: unsupported link ${url.take(40)}"
                return null
            }
        }
        val bmp = runCatching {
            val c = (URL(secure).openConnection() as HttpURLConnection).apply {
                connectTimeout = 10_000
                readTimeout = 10_000
                instanceFollowRedirects = true
                setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android TV) Quiet/1.0")
                if (cookie != null && secure.contains("espn")) setRequestProperty("Cookie", cookie)
            }
            try {
                if (c.responseCode !in 200..299) error("HTTP ${c.responseCode}")
                val bytes = c.inputStream.use { it.readBytes() }
                val type = c.contentType.orEmpty()
                val head = String(bytes, 0, minOf(bytes.size, 200)).trimStart()
                if (type.contains("svg") || head.startsWith("<")) renderSvg(String(bytes))
                else BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: error("not an image ($type)")
            } finally {
                c.disconnect()
            }
        }.onFailure { e ->
            val host = runCatching { URL(secure).host }.getOrDefault(secure.take(40))
            problems += "Team picture $host: ${e.message}"
        }
            .getOrNull()
            ?.let { if (it.width > 160) Bitmap.createScaledBitmap(it, 160, 160 * it.height / it.width, true) else it }
        // Only successes are cached, so a failed picture is retried on the next refresh.
        if (bmp != null) synchronized(logos) { logos[url] = bmp }
        return bmp
    }

    private fun renderSvg(text: String): Bitmap {
        val svg = com.caverock.androidsvg.SVG.getFromString(text)
        val bmp = Bitmap.createBitmap(160, 160, Bitmap.Config.ARGB_8888)
        svg.renderToCanvas(android.graphics.Canvas(bmp), android.graphics.RectF(0f, 0f, 160f, 160f))
        return bmp
    }
}
