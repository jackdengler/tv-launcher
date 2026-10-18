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
    class Result(val cards: List<Pair<Int, LeagueCard>>, val errors: List<String>)

    private val logos = HashMap<String, Bitmap?>()
    private val lastLead = HashMap<Int, Pair<String, Int>>()
    private const val MAX_HISTORY = 240

    /** [onlyShown]: the overlay skips leagues switched off; the panel shows everything. */
    fun load(prefs: Prefs, onlyShown: Boolean, now: Calendar = Calendar.getInstance()): Result {
        val states = runCatching { Nfl.states(Nfl.scoreboard()) }.getOrDefault(emptyMap())
        val cards = ArrayList<Pair<Int, LeagueCard>>()
        val errors = ArrayList<String>()
        for ((n, league, team) in prefs.espnLeagues) {
            if (onlyShown && !(if (n == 1) prefs.espnShow1 else prefs.espnShow2)) continue
            runCatching { Espn.fetch(league, team, prefs.espnS2, prefs.espnSwid, now, states) }
                .onSuccess { score -> cards += n to card(prefs, n, league, team, score) }
                .onFailure { errors += "League $n: ${it.message ?: it.javaClass.simpleName}" }
        }
        return Result(cards, errors)
    }

    private fun card(prefs: Prefs, n: Int, league: String, team: String, score: FantasyScore): LeagueCard {
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

        return LeagueCard(score, wp, logo(score.me.logo), logo(score.opp.logo), change)
    }

    /** Team logo, or null when missing or not a bitmap (ESPN's default logos are SVG). */
    private fun logo(url: String): Bitmap? {
        if (url.isBlank()) return null
        synchronized(logos) { if (url in logos) return logos[url] }
        val bmp = runCatching {
            val c = (URL(url).openConnection() as HttpURLConnection).apply {
                connectTimeout = 10_000
                readTimeout = 10_000
                instanceFollowRedirects = true
            }
            try {
                if (c.responseCode !in 200..299) null else c.inputStream.use { BitmapFactory.decodeStream(it) }
            } finally {
                c.disconnect()
            }
        }.getOrNull()?.let { if (it.width > 160) Bitmap.createScaledBitmap(it, 160, 160 * it.height / it.width, true) else it }
        synchronized(logos) { logos[url] = bmp }
        return bmp
    }
}
