package com.jackdengler.quiet.data

import com.jackdengler.quiet.net.Http
import org.json.JSONObject

/** One NFL game from ESPN's public scoreboard. [state] is "pre", "in" or "post". */
data class NflGame(
    val away: String,
    val awayScore: Int,
    val home: String,
    val homeScore: Int,
    val state: String,
    val detail: String,
    val teamIds: Set<Int>,
) {
    /** "NYG 17 – 14 DAL · Q3 5:12" */
    val line get() = "$away $awayScore – $homeScore $home · $detail"
}

/** ESPN's public NFL scoreboard (no key, no login). Blocking; call from Dispatchers.IO. */
object Nfl {
    private const val URL = "https://site.api.espn.com/apis/site/v2/sports/football/nfl/scoreboard"

    fun scoreboard(): List<NflGame> {
        val body = Http.get(URL).body ?: error("NFL scoreboard unavailable")
        val events = JSONObject(body).optJSONArray("events") ?: return emptyList()
        return (0 until events.length()).mapNotNull { i ->
            val e = events.getJSONObject(i)
            val comp = e.optJSONArray("competitions")?.optJSONObject(0) ?: return@mapNotNull null
            val status = (comp.optJSONObject("status") ?: e.optJSONObject("status"))?.optJSONObject("type")
            val teams = comp.optJSONArray("competitors") ?: return@mapNotNull null
            var home: JSONObject? = null
            var away: JSONObject? = null
            for (j in 0 until teams.length()) {
                val c = teams.getJSONObject(j)
                if (c.optString("homeAway") == "home") home = c else away = c
            }
            if (home == null || away == null) return@mapNotNull null
            fun abbr(c: JSONObject) = c.optJSONObject("team")?.optString("abbreviation").orEmpty()
            fun id(c: JSONObject) = c.optJSONObject("team")?.optString("id")?.toIntOrNull()
            NflGame(
                away = abbr(away), awayScore = away.optString("score").toIntOrNull() ?: 0,
                home = abbr(home), homeScore = home.optString("score").toIntOrNull() ?: 0,
                state = status?.optString("state").orEmpty(),
                detail = status?.optString("shortDetail").orEmpty(),
                teamIds = setOfNotNull(id(home), id(away)),
            )
        }
    }

    /** ESPN pro team id → game state ("pre"/"in"/"post"). Teams on bye are absent. */
    fun states(games: List<NflGame>): Map<Int, String> =
        games.flatMap { g -> g.teamIds.map { it to g.state } }.toMap()
}
