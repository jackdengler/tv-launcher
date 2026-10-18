package com.jackdengler.quiet.data

import com.jackdengler.quiet.net.Http
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder
import java.util.Calendar

/** A fantasy football team on the signed-in ESPN account. */
data class LeagueChoice(val leagueId: String, val teamId: String, val leagueName: String, val teamName: String)

/**
 * Finds every fantasy football team on the signed-in account (ESPN's "fan" profile, which the
 * ESPN app uses for its league list). Needs the espn_s2 and SWID cookies. Blocking.
 */
object EspnLeagues {
    private const val FOOTBALL = 1

    fun find(s2: String, swid: String, now: Calendar = Calendar.getInstance()): List<LeagueChoice> {
        require(s2.isNotBlank() && swid.isNotBlank()) { "Sign in to ESPN first" }
        val url = "https://fan.api.espn.com/apis/v2/fans/" + URLEncoder.encode(swid, "UTF-8") +
            "?displayHiddenPrefs=true&context=fantasy&useCookieAuth=true&source=fantasyapp-ios&featureFlags=challengeEntries"
        val r = Http.get(url, headers = mapOf("Cookie" to "espn_s2=$s2; SWID=$swid"))
        val body = r.body ?: error("ESPN account lookup failed (${r.code})")
        val season = Espn.season(now)
        val found = LinkedHashMap<String, LeagueChoice>()
        // Walk the whole document for fantasy entries rather than trusting one exact shape.
        fun visit(node: Any?) {
            when (node) {
                is JSONObject -> {
                    entry(node, season)?.let { found.putIfAbsent("${it.leagueId}/${it.teamId}", it) }
                    node.keys().forEach { visit(node.opt(it)) }
                }
                is JSONArray -> for (i in 0 until node.length()) visit(node.opt(i))
            }
        }
        visit(JSONObject(body))
        return found.values.toList()
    }

    private fun entry(o: JSONObject, season: Int): LeagueChoice? {
        if (!o.has("entryId") || !o.has("groups")) return null
        if (o.optInt("gameId", FOOTBALL) != FOOTBALL) return null
        if (o.has("seasonId") && o.optInt("seasonId") != season) return null
        val group = o.optJSONArray("groups")?.optJSONObject(0) ?: return null
        val leagueId = group.opt("groupId")?.toString().orEmpty()
        val teamId = o.opt("entryId")?.toString().orEmpty()
        if (leagueId.isBlank() || teamId.isBlank()) return null
        val team = "${o.optString("entryLocation")} ${o.optString("entryNickname")}".trim()
            .ifBlank { o.optString("entryAbbrev") }.ifBlank { "Team $teamId" }
        return LeagueChoice(leagueId, teamId, group.optString("groupName").ifBlank { "League $leagueId" }, team)
    }

    /** Puts the first two found teams into League 1 and League 2 (only empty slots by default). */
    fun assign(prefs: Prefs, choices: List<LeagueChoice>, overwrite: Boolean = false): Int {
        var n = 0
        val queue = ArrayDeque(choices.filterNot { c ->
            (c.leagueId == prefs.espnLeague && c.teamId == prefs.espnTeam) || (c.leagueId == prefs.espnLeague2 && c.teamId == prefs.espnTeam2)
        })
        if ((overwrite || prefs.espnLeague.isBlank()) && queue.isNotEmpty()) {
            val c = queue.removeFirst(); prefs.espnLeague = c.leagueId; prefs.espnTeam = c.teamId; n++
        }
        if ((overwrite || prefs.espnLeague2.isBlank()) && queue.isNotEmpty()) {
            val c = queue.removeFirst(); prefs.espnLeague2 = c.leagueId; prefs.espnTeam2 = c.teamId; n++
        }
        return n
    }
}
