package com.jackdengler.quiet.data

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** User settings (SharedPreferences). [changes] bumps on every edit so UI can re-read. */
class Prefs(context: Context) {
    private val sp: SharedPreferences = context.getSharedPreferences("quiet", Context.MODE_PRIVATE)

    // Kept separate so cache writes never look like settings changes.
    private val cache: SharedPreferences = context.getSharedPreferences("quiet_cache", Context.MODE_PRIVATE)
    private val _changes = MutableStateFlow(0)
    val changes: StateFlow<Int> = _changes

    // Held strongly: SharedPreferences only keeps a weak reference to listeners.
    private val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ -> _changes.value++ }

    init {
        sp.registerOnSharedPreferenceChangeListener(listener)
    }

    var latitude: Double
        get() = sp.getString("lat", null)?.toDoubleOrNull() ?: 40.7128
        set(v) = sp.edit().putString("lat", v.toString()).apply()
    var longitude: Double
        get() = sp.getString("lon", null)?.toDoubleOrNull() ?: -74.0060
        set(v) = sp.edit().putString("lon", v.toString()).apply()
    var placeName: String
        get() = sp.getString("place", null) ?: "New York"
        set(v) = sp.edit().putString("place", v).apply()
    var fahrenheit: Boolean
        get() = sp.getBoolean("fahrenheit", true)
        set(v) = sp.edit().putBoolean("fahrenheit", v).apply()
    /** Headline feed URLs, stored space-separated. */
    var rssUrl: String
        get() = sp.getString("rss", null)?.takeIf { it.isNotBlank() } ?: DEFAULT_RSS
        set(v) = sp.edit().putString("rss", v.trim()).apply()
    val feeds: List<String> get() = rssUrl.split(Regex("[\\s,]+")).filter { it.startsWith("http") }

    /** Background: "live" (Santa Monica live camera, photos as fallback), "photos" (Santa Monica photos) or "mix". */
    var background: String
        get() = sp.getString("background", null) ?: if (sp.getBoolean("bg_santa_monica", true)) "live" else "mix"
        set(v) = sp.edit().putString("background", v).apply()

    /** Santa Monica photos are used for both "live" (as the fallback) and "photos". */
    val santaMonica: Boolean get() = background != "mix"

    /** YouTube live streams for the camera, tried in order (links or ids, space-separated). */
    var liveCams: String
        get() = sp.getString("live_cams", null)?.takeIf { it.isNotBlank() } ?: DEFAULT_CAMS
        set(v) = sp.edit().putString("live_cams", v.trim()).apply()

    /** Package chosen for home tile [index] by long-press, or null for the default. */
    fun slotPackage(index: Int): String? = sp.getString("slot$index", null)
    fun setSlotPackage(index: Int, pkg: String?) = sp.edit().putString("slot$index", pkg).apply()

    var onboardingSeen: Boolean
        get() = sp.getBoolean("onboarding_seen", false)
        set(v) = sp.edit().putBoolean("onboarding_seen", v).apply()

    // ESPN fantasy overlay
    var espnEnabled: Boolean
        get() = sp.getBoolean("espn_on", false)
        set(v) = sp.edit().putBoolean("espn_on", v).apply()
    var espnAlways: Boolean
        get() = sp.getBoolean("espn_always", false)
        set(v) = sp.edit().putBoolean("espn_always", v).apply()
    var espnLeague: String
        get() = sp.getString("espn_league", "") ?: ""
        set(v) = sp.edit().putString("espn_league", v.trim()).apply()
    var espnTeam: String
        get() = sp.getString("espn_team", "") ?: ""
        set(v) = sp.edit().putString("espn_team", v.trim()).apply()
    // Second league (same ESPN account, so the same cookies).
    var espnLeague2: String
        get() = sp.getString("espn_league2", "") ?: ""
        set(v) = sp.edit().putString("espn_league2", v.trim()).apply()
    var espnTeam2: String
        get() = sp.getString("espn_team2", "") ?: ""
        set(v) = sp.edit().putString("espn_team2", v.trim()).apply()

    /** Which leagues the overlay shows (both, one, or neither). */
    var espnShow1: Boolean
        get() = sp.getBoolean("espn_show1", true)
        set(v) = sp.edit().putBoolean("espn_show1", v).apply()
    var espnShow2: Boolean
        get() = sp.getBoolean("espn_show2", true)
        set(v) = sp.edit().putBoolean("espn_show2", v).apply()

    /** Overlay look: "bug" (scorebug), "card" (adds win chart), "full" (every starter). */
    var espnStyle: String
        get() = sp.getString("espn_style", "bug") ?: "bug"
        set(v) = sp.edit().putString("espn_style", v).apply()

    /** Overlay corner: top-right (default), top-left, bottom-left, bottom-right. */
    var espnCorner: String
        get() = sp.getString("espn_corner", "top-right") ?: "top-right"
        set(v) = sp.edit().putString("espn_corner", v).apply()

    /** Pop-up cards for each play involving my starters or my opponents'. */
    var espnPlays: Boolean
        get() = sp.getBoolean("espn_plays", true)
        set(v) = sp.edit().putBoolean("espn_plays", v).apply()

    /** Seconds to hold play pop-ups so they don't beat the (delayed) TV stream. */
    var espnPlayDelay: Int
        get() = sp.getInt("espn_play_delay", 45)
        set(v) = sp.edit().putInt("espn_play_delay", v).apply()

    /** Configured leagues as (number, leagueId, teamId), whether shown or not. */
    val espnLeagues: List<Triple<Int, String, String>>
        get() = listOf(Triple(1, espnLeague, espnTeam), Triple(2, espnLeague2, espnTeam2))
            .filter { it.second.isNotBlank() && it.third.isNotBlank() }

    var espnS2: String
        get() = sp.getString("espn_s2", "") ?: ""
        set(v) = sp.edit().putString("espn_s2", v.trim()).apply()
    var espnSwid: String
        get() = sp.getString("espn_swid", "") ?: ""
        set(v) = sp.edit().putString("espn_swid", v.trim()).apply()

    // UFC parlay overlay (reads the parlay app's data.json from GitHub)
    var parlayEnabled: Boolean
        get() = sp.getBoolean("parlay_on", false)
        set(v) = sp.edit().putBoolean("parlay_on", v).apply()
    var parlayAlways: Boolean
        get() = sp.getBoolean("parlay_always", false)
        set(v) = sp.edit().putBoolean("parlay_always", v).apply()
    /** GitHub token that can read private-data-storage (a read-only fine-grained token). */
    var parlayToken: String
        get() = sp.getString("parlay_token", "") ?: ""
        set(v) = sp.edit().putString("parlay_token", v.trim()).apply()
    /** Live fight and moneyline results from ESPN's UFC scoreboard. */
    var parlayEspn: Boolean
        get() = sp.getBoolean("parlay_espn", true)
        set(v) = sp.edit().putBoolean("parlay_espn", v).apply()
    /** Seconds to hold ESPN's fight updates so results don't beat the (delayed) stream. */
    var parlayDelay: Int
        get() = sp.getInt("parlay_delay", 45)
        set(v) = sp.edit().putInt("parlay_delay", v).apply()
    var parlayCorner: String
        get() = sp.getString("parlay_corner", "top-right") ?: "top-right"
        set(v) = sp.edit().putString("parlay_corner", v).apply()

    /** Small cache slots for last-known weather/news, so the first frame never waits on network. */
    fun cached(key: String): String? = cache.getString(key, null)
    fun putCached(key: String, value: String) = cache.edit().putString(key, value).apply()

    /** Identifies the weather request; a cached reading for a different key is stale. */
    val weatherKey: String get() = "$latitude,$longitude,$fahrenheit"

    companion object {
        /** Pacific Park's official 4K pier stream, then a beach-and-pier cam. */
        const val DEFAULT_CAMS = "DckQwhIzMdA v97JpT3ZA0w"

        const val DEFAULT_RSS =
            "https://feeds.content.dowjones.io/public/rss/RSSWorldNews https://variety.com/feed/"
    }
}
