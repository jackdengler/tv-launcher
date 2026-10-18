package com.jackdengler.quiet.data

import com.jackdengler.quiet.net.Http
import org.json.JSONObject
import java.time.Instant
import kotlin.math.abs

/** One play from ESPN's public game feed. [wallclock] is when it happened (epoch ms). */
data class Play(val id: String, val text: String, val wallclock: Long)

/** A pop-up for one of the fantasy starters involved in a play. */
data class PlayPop(
    val playId: String,
    val player: String,
    /** "15-yd reception", "TOUCHDOWN · 22-yd rush", … */
    val what: String,
    val points: Double?,
    /** My starter (gold) or my opponent's (slate). */
    val mine: Boolean,
    /** Touchdowns, turnovers and field goals get the big treatment. */
    val big: Boolean,
    val happenedAt: Long,
)

/** A starter we watch for in plays. */
data class Watched(val fullName: String, val proTeam: Int, val mine: Boolean, val scoring: Map<Int, Double>)

/** ESPN's public NFL game summary, read for its play-by-play. Blocking; call from Dispatchers.IO. */
object Plays {
    private const val SUMMARY = "https://site.api.espn.com/apis/site/v2/sports/football/nfl/summary?event="

    fun recent(eventId: String): List<Play> {
        val body = Http.get(SUMMARY + eventId).body ?: return emptyList()
        val drives = JSONObject(body).optJSONObject("drives") ?: return emptyList()
        val out = ArrayList<Play>()
        fun addDrive(d: JSONObject?) {
            val plays = d?.optJSONArray("plays") ?: return
            for (i in 0 until plays.length()) {
                val p = plays.getJSONObject(i)
                val text = p.optString("text")
                if (text.isBlank()) continue
                val time = runCatching { Instant.parse(p.optString("wallclock")).toEpochMilli() }.getOrDefault(0L)
                out += Play(p.optString("id"), text, time)
            }
        }
        drives.optJSONArray("previous")?.let { prev -> for (i in 0 until prev.length()) addDrive(prev.optJSONObject(i)) }
        addDrive(drives.optJSONObject("current"))
        return out.distinctBy { it.id }
    }

    /**
     * Matches a play's text ("L.Jackson pass short right to Z.Flowers to BAL 45 for 15 yards")
     * against the watched starters and describes what each one did.
     */
    fun match(play: Play, watched: List<Watched>): List<PlayPop> {
        val t = play.text
        if (t.contains("No Play", ignoreCase = true) || t.contains("Timeout", ignoreCase = true)) return emptyList()
        val yards = YARDS.find(t)?.groupValues?.get(1)?.toIntOrNull()
            ?: if (t.contains("for no gain")) 0 else null
        val td = t.contains("TOUCHDOWN", ignoreCase = true)
        val pops = ArrayList<PlayPop>()
        for (w in watched) {
            val token = espnName(w.fullName) ?: continue
            val at = t.indexOf(token)
            if (at < 0) continue
            val before = t.substring(0, at)
            val after = t.substring(at + token.length)
            val (what, stats) = when {
                // Receiver: "... pass ... to Z.Flowers ... for 15 yards"
                before.contains(" pass ") && before.trimEnd().endsWith(" to") && !t.contains("incomplete") ->
                    describe("reception", yards, td) to listOf(53 to 1.0, 42 to (yards ?: 0).toDouble(), 43 to if (td) 1.0 else 0.0)
                // Passer
                after.startsWith(" pass") && t.contains("INTERCEPTED") -> "Interception thrown" to listOf(20 to 1.0)
                after.startsWith(" pass") && !t.contains("incomplete") ->
                    describe("pass", yards, td) to listOf(3 to (yards ?: 0).toDouble(), 4 to if (td) 1.0 else 0.0)
                after.startsWith(" pass") -> continue
                // Kicker
                after.contains("field goal is GOOD") -> "${FG.find(after)?.groupValues?.get(1) ?: ""}-yd field goal".trimStart('-') to fieldGoal(after)
                after.contains("extra point is GOOD") -> "Extra point" to listOf(86 to 1.0)
                after.contains("field goal is No Good") -> "Missed field goal" to emptyList()
                // Rusher: name first, then a run direction
                before.isBlank() || before.endsWith(") ") || before.endsWith(". ") ->
                    if (RUSH.containsMatchIn(after)) describe("rush", yards, td) to listOf(24 to (yards ?: 0).toDouble(), 25 to if (td) 1.0 else 0.0)
                    else continue
                else -> continue
            }
            val fumbled = t.contains("FUMBLES") && after.contains("FUMBLES")
            val stat = if (fumbled) stats + (72 to 1.0) else stats
            val points = stat.sumOf { (id, n) -> (w.scoring[id] ?: DEFAULTS[id] ?: 0.0) * n }
            pops += PlayPop(
                playId = play.id,
                player = w.fullName,
                what = if (fumbled) "$what · fumble" else what,
                points = points.takeIf { abs(it) >= 0.05 },
                mine = w.mine,
                big = td || what.startsWith("Interception") || what.contains("field goal") || fumbled,
                happenedAt = play.wallclock,
            )
        }
        return pops
    }

    private fun describe(kind: String, yards: Int?, td: Boolean): String {
        val base = when {
            yards == null -> kind.replaceFirstChar { it.uppercase() }
            kind == "pass" -> "$yards-yd pass"
            else -> "$yards-yd $kind"
        }
        return if (td) "TOUCHDOWN · $base" else base
    }

    private fun fieldGoal(after: String): List<Pair<Int, Double>> {
        val d = FG.find(after)?.groupValues?.get(1)?.toIntOrNull() ?: return listOf(83 to 1.0)
        return listOf((if (d >= 50) 74 else if (d >= 40) 77 else 80) to 1.0)
    }

    /** "Zay Flowers" → "Z.Flowers"; "Amon-Ra St. Brown" → "A.St. Brown" (ESPN play text style). */
    fun espnName(full: String): String? {
        val parts = full.trim().split(" ").filter { it.isNotBlank() && it !in SUFFIXES }
        if (parts.size < 2 || full.contains("D/ST")) return null
        return "${parts[0].first()}.${parts.drop(1).joinToString(" ")}"
    }

    private val SUFFIXES = setOf("Jr.", "Sr.", "II", "III", "IV", "V")
    private val YARDS = Regex("""for (-?\d+) yards?""")
    private val FG = Regex("""(\d+) yard field goal""")
    private val RUSH = Regex("""^\s*(up the middle|left|right|scrambles|kneels|to [A-Z]{2,3} \d+)""")

    /** Fallback scoring (half-PPR-ish) when the league's settings don't list a stat. */
    private val DEFAULTS = mapOf(
        53 to 0.5, 42 to 0.1, 43 to 6.0, 3 to 0.04, 4 to 4.0, 20 to -2.0, 24 to 0.1, 25 to 6.0,
        72 to -2.0, 74 to 5.0, 77 to 4.0, 80 to 3.0, 83 to 3.0, 86 to 1.0,
    )
}
