package com.jackdengler.quiet.data

import com.jackdengler.quiet.net.Http
import org.json.JSONObject
import java.util.Calendar
import java.util.Locale

/** The current week's matchup for the configured team. */
data class FantasyScore(
    val week: Int,
    val myName: String,
    val myPoints: Double,
    val oppName: String,
    val oppPoints: Double,
    val myProjected: Double?,
    val oppProjected: Double?,
    /** My starters whose NFL game is in progress / not started yet (null when unknown). */
    val myPlaying: Int? = null,
    val myToGo: Int? = null,
) {
    val headline get() = "$myName ${fmt(myPoints)} · ${fmt(oppPoints)} $oppName"
    val detail: String
        get() = listOfNotNull(
            "Week $week",
            if (myProjected != null && oppProjected != null) "proj ${fmt(myProjected)}–${fmt(oppProjected)}" else null,
            myPlaying?.takeIf { it > 0 }?.let { "$it playing" },
            myToGo?.takeIf { it > 0 }?.let { "$it to go" },
        ).joinToString(" · ")

    /** 1 when I lead, -1 when I trail, 0 when tied. */
    val lead: Int get() = myPoints.compareTo(oppPoints).coerceIn(-1, 1)

    private fun fmt(v: Double) = String.format(Locale.US, "%.1f", v)
}

/** ESPN's unofficial fantasy football API. Blocking; call from Dispatchers.IO. */
object Espn {
    fun fetch(
        league: String,
        team: String,
        s2: String,
        swid: String,
        now: Calendar = Calendar.getInstance(),
        gameStates: Map<Int, String> = emptyMap(),
    ): FantasyScore {
        require(league.isNotBlank() && team.isNotBlank()) { "League ID and Team ID are required" }
        val teamId = team.toIntOrNull() ?: error("Team ID must be a number")
        val url = "https://lm-api-reads.fantasy.espn.com/apis/v3/games/ffl/seasons/${season(now)}" +
            "/segments/0/leagues/$league?view=mMatchupScore&view=mTeam&view=mScoreboard&view=mMatchup"
        val headers = if (s2.isNotBlank() && swid.isNotBlank()) mapOf("Cookie" to "espn_s2=$s2; SWID=$swid") else emptyMap()
        val response = Http.get(url, headers = headers)
        val body = response.body ?: error(
            when (response.code) {
                401, 403 -> "ESPN says private league (${response.code}). Use Sign in to ESPN"
                404 -> "League not found (404). Check League ID"
                else -> "ESPN HTTP ${response.code}"
            },
        )
        return parse(JSONObject(body), teamId, gameStates) ?: error("Team $teamId has no matchup this week. Check Team ID")
    }

    /** Cache key for the overlay's last status line, shown in settings. */
    const val STATUS = "espn_status"

    /** The NFL season is named for the year it starts; Jan–Feb games belong to last year's season. */
    fun season(now: Calendar): Int = now.get(Calendar.YEAR) - if (now.get(Calendar.MONTH) < Calendar.MARCH) 1 else 0

    fun parse(root: JSONObject, teamId: Int, gameStates: Map<Int, String> = emptyMap()): FantasyScore? {
        val period = root.optJSONObject("status")?.optInt("currentMatchupPeriod")?.takeIf { it > 0 }
            ?: root.optInt("scoringPeriodId")
        val names = HashMap<Int, String>()
        root.optJSONArray("teams")?.let { teams ->
            for (i in 0 until teams.length()) {
                val t = teams.getJSONObject(i)
                val name = t.optString("name").ifBlank {
                    "${t.optString("location")} ${t.optString("nickname")}".trim()
                }.ifBlank { t.optString("abbrev") }
                names[t.getInt("id")] = name.take(MAX_NAME)
            }
        }
        val schedule = root.optJSONArray("schedule") ?: return null
        for (i in 0 until schedule.length()) {
            val m = schedule.getJSONObject(i)
            if (m.optInt("matchupPeriodId") != period) continue
            val home = m.optJSONObject("home") ?: continue
            val away = m.optJSONObject("away") ?: continue
            val (me, opp) = when (teamId) {
                home.optInt("teamId") -> home to away
                away.optInt("teamId") -> away to home
                else -> continue
            }
            return FantasyScore(
                week = period,
                myName = names[me.optInt("teamId")] ?: "Me",
                myPoints = points(me),
                oppName = names[opp.optInt("teamId")] ?: "Opponent",
                oppPoints = points(opp),
                myProjected = me.optDouble("totalProjectedPointsLive").takeUnless { it.isNaN() },
                oppProjected = opp.optDouble("totalProjectedPointsLive").takeUnless { it.isNaN() },
                myPlaying = starterStates(me, gameStates)?.count { it == "in" },
                myToGo = starterStates(me, gameStates)?.count { it == "pre" },
            )
        }
        return null
    }

    /** NFL game state of each starter's team (bench and IR excluded), or null without roster data. */
    private fun starterStates(side: JSONObject, gameStates: Map<Int, String>): List<String>? {
        if (gameStates.isEmpty()) return null
        val entries = side.optJSONObject("rosterForCurrentScoringPeriod")?.optJSONArray("entries") ?: return null
        return (0 until entries.length()).mapNotNull { i ->
            val e = entries.getJSONObject(i)
            if (e.optInt("lineupSlotId") in BENCH_SLOTS) return@mapNotNull null
            val proTeam = e.optJSONObject("playerPoolEntry")?.optJSONObject("player")?.optInt("proTeamId") ?: return@mapNotNull null
            gameStates[proTeam] ?: "post" // on bye: nothing left to play
        }
    }

    private val BENCH_SLOTS = setOf(20, 21)

    private fun points(side: JSONObject): Double =
        side.optDouble("totalPointsLive").takeUnless { it.isNaN() } ?: side.optDouble("totalPoints", 0.0)

    /**
     * NFL game windows in local time: Thu 7 pm, Sun 6 am (covers early international games on
     * the West Coast) and Mon 7 pm, each running until 1 am.
     * Hours after midnight count toward the previous day.
     */
    fun inGameWindow(now: Calendar): Boolean {
        val c = now.clone() as Calendar
        var hour = c.get(Calendar.HOUR_OF_DAY)
        if (hour < 1) {
            c.add(Calendar.DAY_OF_YEAR, -1)
            hour += 24
        }
        return when (c.get(Calendar.DAY_OF_WEEK)) {
            Calendar.THURSDAY, Calendar.MONDAY -> hour >= 19
            Calendar.SUNDAY -> hour >= 6
            else -> false
        }
    }

    private const val MAX_NAME = 18
}
