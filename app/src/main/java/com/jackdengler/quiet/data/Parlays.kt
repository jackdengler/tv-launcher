package com.jackdengler.quiet.data

import com.jackdengler.quiet.net.Http
import org.json.JSONObject
import java.text.Normalizer
import java.util.Locale

/** One leg. [result] is "win", "loss", "push" or null (open); [live] while its fight is on. */
data class ParlayLeg(val name: String, val odds: Int, val fight: String, val result: String?, val live: Boolean = false)

/** A placed parlay, with results merged in. Math mirrors the parlay app (pushes drop out). */
data class LiveParlay(val id: String, val name: String, val stake: Double, val legs: List<ParlayLeg>, val cashOut: Double?) {
    /** "cashout", "loss", "push", "win" or "pending", as the app's parlayStatus(). */
    val status: String
        get() = when {
            cashOut != null -> "cashout"
            legs.any { it.result == "loss" } -> "loss"
            legs.isNotEmpty() && legs.all { it.result == "push" } -> "push"
            legs.isNotEmpty() && legs.all { it.result == "win" || it.result == "push" } -> "win"
            else -> "pending"
        }
    val open get() = status == "pending"
    val hits get() = legs.count { it.result == "win" }

    /** What it pays if every open leg hits (or what it paid). */
    val payout: Double
        get() = when (status) {
            "cashout" -> cashOut ?: 0.0
            "loss" -> 0.0
            else -> stake * legs.filter { it.result != "push" }.fold(1.0) { acc, l -> acc * decimal(l.odds) }
        }

    companion object {
        fun decimal(odds: Int) = if (odds > 0) odds / 100.0 + 1 else 100.0 / -odds + 1
    }
}

/** One UFC bout from ESPN. [state] is "pre", "in" or "post"; [winner] is a name when decided. */
data class UfcFight(val a: String, val b: String, val state: String, val winner: String?, val detail: String)

/** Everything the overlay draws. [now] is the fight on right now that carries a leg, if any. */
data class ParlayBoard(val event: String, val parlays: List<LiveParlay>, val now: String?, val lastMarked: Long) {
    val open get() = parlays.filter { it.open }
}

/** The parlay app's saved state, read from the private GitHub repo it syncs to. */
object Parlays {
    const val STATUS = "parlay_status"
    private const val URL = "https://api.github.com/repos/jackdengler/private-data-storage/contents/data.json"
    private const val UFC_URL = "https://site.api.espn.com/apis/site/v2/sports/mma/ufc/scoreboard"

    class Fetched(val body: String?, val etag: String?, val code: Int)

    /** The raw data.json; [Fetched.body] is null when it hasn't changed since [etag]. Blocking. */
    fun fetch(token: String, etag: String?): Fetched {
        val r = Http.get(
            URL, etag,
            mapOf("Authorization" to "Bearer $token", "Accept" to "application/vnd.github.raw+json"),
        )
        return when (r.code) {
            200, 304 -> Fetched(r.body, r.etag ?: etag, r.code)
            401, 403 -> error("GitHub said ${r.code}: check the token can read private-data-storage")
            404 -> error("GitHub said 404: the token can't see private-data-storage/data.json")
            else -> error("GitHub said ${r.code}")
        }
    }

    /** ESPN's public UFC scoreboard (the current or next card). Blocking. */
    fun fights(): List<UfcFight> = parseFights(Http.get(UFC_URL).body ?: error("UFC scoreboard unavailable"))

    fun parseFights(body: String): List<UfcFight> {
        val events = JSONObject(body).optJSONArray("events") ?: return emptyList()
        val out = ArrayList<UfcFight>()
        for (i in 0 until events.length()) {
            val comps = events.optJSONObject(i)?.optJSONArray("competitions") ?: continue
            for (j in 0 until comps.length()) {
                val c = comps.optJSONObject(j) ?: continue
                val people = c.optJSONArray("competitors") ?: continue
                if (people.length() != 2) continue
                val names = (0..1).map { k ->
                    val athlete = people.optJSONObject(k)?.optJSONObject("athlete")
                    athlete?.optString("displayName")?.ifBlank { null } ?: athlete?.optString("fullName").orEmpty()
                }
                if (names.any { it.isBlank() }) continue
                val status = c.optJSONObject("status")
                val type = status?.optJSONObject("type")
                val winner = (0..1).firstOrNull { people.optJSONObject(it)?.optBoolean("winner") == true }?.let { names[it] }
                val round = status?.optInt("period") ?: 0
                val clock = status?.optString("displayClock").orEmpty()
                val state = type?.optString("state").orEmpty()
                val detail = if (state == "in" && round > 0) "R$round $clock".trim() else type?.optString("shortDetail").orEmpty()
                out += UfcFight(names[0], names[1], state, winner, detail)
            }
        }
        return out
    }

    /** True while the card is under way: a fight is on, or some have finished and some haven't. */
    fun cardLive(fights: List<UfcFight>) =
        fights.any { it.state == "in" } || (fights.any { it.state == "post" } && fights.any { it.state == "pre" })

    /**
     * Placed parlays from the app's [json], with [fights] (may be empty) filling in moneyline
     * legs the app hasn't marked yet. A result marked in the app always wins.
     */
    fun board(json: String, fights: List<UfcFight>): ParlayBoard {
        val s = JSONObject(json)
        val results = s.optJSONObject("betResults") ?: JSONObject()
        val bets = HashMap<String, JSONObject>()
        s.optJSONArray("bets")?.let { a -> for (i in 0 until a.length()) a.optJSONObject(i)?.let { bets[it.optString("id")] = it } }
        var nowFight: UfcFight? = null
        val parlays = ArrayList<LiveParlay>()
        val list = s.optJSONArray("parlays")
        for (i in 0 until (list?.length() ?: 0)) {
            val p = list!!.optJSONObject(i) ?: continue
            if (!p.optBoolean("placed")) continue
            val ids = p.optJSONArray("betIds") ?: continue
            val legs = (0 until ids.length()).mapNotNull { k ->
                val id = ids.optString(k)
                val b = bets[id] ?: return@mapNotNull null
                val name = b.optString("name")
                val group = b.optString("group")
                val fight = fights.firstOrNull { sameFight(group, it) }
                if (fight?.state == "in") nowFight = nowFight ?: fight
                val marked = results.optString(id).takeIf { it in RESULTS }
                ParlayLeg(
                    name = name,
                    odds = b.optInt("odds"),
                    fight = group,
                    result = marked ?: fight?.let { espnResult(name, it) },
                    live = marked == null && fight?.state == "in",
                )
            }
            val cash = if (p.has("cashOut") && !p.isNull("cashOut")) p.optDouble("cashOut").takeIf { !it.isNaN() } else null
            parlays += LiveParlay(p.optString("id"), p.optString("name"), p.optDouble("stake", 0.0), legs, cash)
        }
        val at = s.optJSONObject("betResultsAt")
        val lastMarked = at?.keys()?.asSequence()?.maxOfOrNull { at.optLong(it) } ?: 0L
        val event = s.optString("weekName").ifBlank { s.optString("eventName") }
        return ParlayBoard(event, parlays, nowFight?.let { "${last(it.a)} vs ${last(it.b)} · ${it.detail}" }, lastMarked)
    }

    private val RESULTS = setOf("win", "loss", "push")

    /** A decided bout settles a moneyline leg (the bet name is one fighter). Props stay open. */
    private fun espnResult(betName: String, f: UfcFight): String? {
        if (f.state != "post" || f.winner == null) return null
        val mine = side(betName, f) ?: return null
        return if (mine == f.winner) "win" else "loss"
    }

    /** The fighter [betName] names in [f], when every word of it is in exactly one of the two names. */
    private fun side(betName: String, f: UfcFight): String? {
        val want = words(betName)
        if (want.isEmpty()) return null
        val hits = listOf(f.a, f.b).filter { words(it).containsAll(want) }
        return hits.singleOrNull()
    }

    /** "Topuria vs Gaethje" names both fighters of [f] (either order). */
    private fun sameFight(group: String, f: UfcFight): Boolean {
        val sides = group.split(Regex("\\s+vs\\.?\\s+", RegexOption.IGNORE_CASE))
        if (sides.size != 2) return false
        val a = side(sides[0], f)
        val b = side(sides[1], f)
        return a != null && b != null && a != b
    }

    private fun words(s: String): Set<String> =
        Normalizer.normalize(s, Normalizer.Form.NFD).replace(Regex("\\p{Mn}+"), "").lowercase(Locale.US)
            .split(Regex("[^a-z]+")).filter { it.isNotEmpty() }.toSet()

    private fun last(name: String) = name.trim().substringAfterLast(' ')
}
