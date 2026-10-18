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
    var rssUrl: String
        get() = sp.getString("rss", null)?.takeIf { it.isNotBlank() } ?: DEFAULT_RSS
        set(v) = sp.edit().putString("rss", v.trim()).apply()

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
    var espnS2: String
        get() = sp.getString("espn_s2", "") ?: ""
        set(v) = sp.edit().putString("espn_s2", v.trim()).apply()
    var espnSwid: String
        get() = sp.getString("espn_swid", "") ?: ""
        set(v) = sp.edit().putString("espn_swid", v.trim()).apply()

    /** Small cache slots for last-known weather/news, so the first frame never waits on network. */
    fun cached(key: String): String? = cache.getString(key, null)
    fun putCached(key: String, value: String) = cache.edit().putString(key, value).apply()

    /** Identifies the weather request; a cached reading for a different key is stale. */
    val weatherKey: String get() = "$latitude,$longitude,$fahrenheit"

    companion object {
        const val DEFAULT_RSS = "https://feeds.bbci.co.uk/news/rss.xml"
    }
}
