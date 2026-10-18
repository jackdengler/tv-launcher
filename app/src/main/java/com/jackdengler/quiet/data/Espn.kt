package com.jackdengler.quiet.data

import com.jackdengler.quiet.net.Http
import org.json.JSONObject
import java.util.Calendar
import java.util.Locale

/** One starter. [state] is the NFL game state: "pre", "in", "post" (bye counts as "post"). */
data class FantasyPlayer(val name: String, val slot: String, val points: Double, val projected: Double?, val state: String)

data class FantasyTeam(
    val id: Int,
    val name: String,
    val abbrev: String,
    val logo: String,
    val points: Double,
    val projected: Double?,
    val starters: List<FantasyPlayer>,
) {
    val playing get() = starters.count { it.state == "in" }
    val toGo get() = starters.count { it.state == "pre" }

    /** Expected final score: ESPN's live projection, else points plus what's left to play. */
    val expected: Double
        get() = projected ?: (points + starters.filter { it.state != "post" }.sumOf { p ->
            ((p.projected ?: 0.0) - if (p.state == "in") p.points else 0.0).coerceAtLeast(0.0)
        })

    /** Variance of the remaining scoring (about 7.5 pts per starter yet to play, 5 per live one). */
    val variance: Double get() = starters.sumOf { if (it.state == "pre") 56.0 else if (it.state == "in") 25.0 else 0.0 }
}

/** The current week's matchup for one league. */
data class FantasyScore(val leagueName: String, val week: Int, val me: FantasyTeam, val opp: FantasyTeam) {
    val myPoints get() = me.points
    val oppPoints get() = opp.points

    val headline get() = "${me.name} ${fmt(me.points)} · ${fmt(opp.points)} ${opp.name}"
    val detail: String
        get() = listOfNotNull(
            "Week $week",
            if (me.projected != null && opp.projected != null) "proj ${fmt(me.projected)}–${fmt(opp.projected)}" else null,
            me.playing.takeIf { it > 0 }?.let { "$it playing" },
            me.toGo.takeIf { it > 0 }?.let { "$it to go" },
        ).joinToString(" · ")

    /** 1 when I lead, -1 when I trail, 0 when tied. */
    val lead: Int get() = me.points.compareTo(opp.points).coerceIn(-1, 1)

    /** My chance to win (0..1): normal model on expected finals and remaining variance. */
    val winProbability: Double
        get() {
            val diff = me.expected - opp.expected
            val sd = kotlin.math.sqrt(me.variance + opp.variance)
            if (sd < 0.5) return if (diff > 0) 1.0 else if (diff < 0) 0.0 else 0.5
            return normalCdf(diff / sd)
        }

    companion object {
        fun fmt(v: Double) = String.format(Locale.US, "%.1f", v)

        /** Abramowitz–Stegun erf approximation; plenty for a win meter. */
        private fun normalCdf(x: Double): Double {
            val z = kotlin.math.abs(x) / kotlin.math.sqrt(2.0)
            val t = 1.0 / (1.0 + 0.3275911 * z)
            val erf = 1.0 - (((((1.061405429 * t - 1.453152027) * t) + 1.421413741) * t - 0.284496736) * t + 0.254829592) * t *
                kotlin.math.exp(-z * z)
            return 0.5 * (1.0 + if (x >= 0) erf else -erf)
        }
    }
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
            "/segments/0/leagues/$league?view=mMatchupScore&view=mTeam&view=mScoreboard&view=mMatchup&view=mSettings"
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
        val scoringPeriod = root.optInt("scoringPeriodId")
        val teams = HashMap<Int, JSONObject>()
        root.optJSONArray("teams")?.let { arr -> for (i in 0 until arr.length()) arr.getJSONObject(i).let { teams[it.getInt("id")] = it } }
        val leagueName = root.optJSONObject("settings")?.optString("name").orEmpty()
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
                leagueName = leagueName,
                week = period,
                me = team(me, teams, gameStates, scoringPeriod),
                opp = team(opp, teams, gameStates, scoringPeriod),
            )
        }
        return null
    }

    private fun team(side: JSONObject, teams: Map<Int, JSONObject>, states: Map<Int, String>, scoringPeriod: Int): FantasyTeam {
        val id = side.optInt("teamId")
        val t = teams[id]
        val name = t?.optString("name").orEmpty().ifBlank {
            "${t?.optString("location").orEmpty()} ${t?.optString("nickname").orEmpty()}".trim()
        }.ifBlank { t?.optString("abbrev").orEmpty() }.ifBlank { "Team $id" }
        return FantasyTeam(
            id = id,
            name = name.take(MAX_NAME),
            abbrev = t?.optString("abbrev").orEmpty().ifBlank { name.take(4).uppercase() },
            logo = t?.optString("logo").orEmpty(),
            points = points(side),
            projected = side.optDouble("totalProjectedPointsLive").takeUnless { it.isNaN() },
            starters = starters(side, states, scoringPeriod),
        )
    }

    private fun starters(side: JSONObject, states: Map<Int, String>, scoringPeriod: Int): List<FantasyPlayer> {
        val entries = side.optJSONObject("rosterForCurrentScoringPeriod")?.optJSONArray("entries") ?: return emptyList()
        return (0 until entries.length()).mapNotNull { i ->
            val e = entries.getJSONObject(i)
            val slot = e.optInt("lineupSlotId")
            if (slot in BENCH_SLOTS) return@mapNotNull null
            val pool = e.optJSONObject("playerPoolEntry") ?: return@mapNotNull null
            val player = pool.optJSONObject("player") ?: return@mapNotNull null
            val stats = player.optJSONArray("stats")
            var projected: Double? = null
            if (stats != null) {
                for (k in 0 until stats.length()) {
                    val st = stats.getJSONObject(k)
                    if (st.optInt("statSourceId") == 1 && st.optInt("scoringPeriodId") == scoringPeriod) {
                        projected = st.optDouble("appliedTotal").takeUnless { it.isNaN() }
                    }
                }
            }
            FantasyPlayer(
                name = shortName(player.optString("fullName")),
                slot = SLOT_NAMES[slot] ?: "FLX",
                points = pool.optDouble("appliedStatTotal").takeUnless { it.isNaN() } ?: 0.0,
                projected = projected,
                state = states[player.optInt("proTeamId")] ?: if (states.isEmpty()) "pre" else "post",
            )
        }.sortedBy { SLOT_ORDER.indexOf(it.slot).let { i -> if (i < 0) 99 else i } }
    }

    /** "Josh Allen" -> "J. Allen"; team defenses keep their name. */
    private fun shortName(full: String): String {
        val parts = full.trim().split(" ")
        return if (parts.size < 2 || full.contains("D/ST")) full else "${parts[0].first()}. ${parts.drop(1).joinToString(" ")}"
    }

    private val SLOT_NAMES = mapOf(
        0 to "QB", 2 to "RB", 3 to "RB/WR", 4 to "WR", 5 to "WR/TE", 6 to "TE", 7 to "OP",
        16 to "D/ST", 17 to "K", 23 to "FLX",
    )
    private val SLOT_ORDER = listOf("QB", "RB", "RB/WR", "WR", "WR/TE", "TE", "FLX", "OP", "D/ST", "K")

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
