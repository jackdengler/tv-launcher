package com.jackdengler.quiet.data

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Calendar

/**
 * Weather and headlines for the home screen. Starts from the last cached values (no network on
 * the startup path) and refreshes only while [run] is active, i.e. while Quiet is visible.
 */
@Stable
class Ambient(private val prefs: Prefs) {
    var weather by mutableStateOf(Weather.decode(prefs.cached(WEATHER)))
        private set
    private var news by mutableStateOf(News.decode(prefs.cached(NEWS)))
    private var index by mutableIntStateOf(0)

    /** Live (and, during game windows, final) NFL games, shown in the pill between headlines. */
    private var scores by mutableStateOf<List<NflGame>>(emptyList())

    /** Every current headline (for the news panel). */
    val headlines: List<Headline> get() = news?.items.orEmpty()

    val headline: Headline?
        get() {
            val items = news?.items.orEmpty()
            val games = scores.map { Headline(it.line, "NFL") }
            val rotation = if (games.isEmpty()) items else interleave(games, items)
            return rotation.takeIf { it.isNotEmpty() }?.let { it[index % it.size] }
        }

    private fun interleave(a: List<Headline>, b: List<Headline>): List<Headline> =
        (0 until maxOf(a.size, b.size)).flatMap { i -> listOfNotNull(a.getOrNull(i), b.getOrNull(i)) }

    suspend fun run() = coroutineScope {
        delay(FIRST_FETCH_DELAY_MS)
        launch {
            refreshLoop(WEATHER_MS, { weather?.takeIf { it.key == prefs.weatherKey && it.hours.isNotEmpty() }?.fetchedAt }) {
                val key = prefs.weatherKey
                OpenMeteo.current(prefs.latitude, prefs.longitude, prefs.fahrenheit)?.copy(key = key)?.also {
                    weather = it
                    prefs.putCached(WEATHER, it.encode())
                } != null
            }
        }
        launch {
            refreshLoop(NEWS_MS, { news?.takeIf { it.url == prefs.rssUrl }?.fetchedAt }) {
                val url = prefs.rssUrl
                val items = News.fetchAll(prefs.feeds)
                if (items.isNotEmpty()) {
                    val snapshot = News.Snapshot(items, System.currentTimeMillis(), url)
                    news = snapshot
                    index = 0
                    prefs.putCached(NEWS, News.encode(snapshot))
                }
                items.isNotEmpty()
            }
        }
        launch {
            // Scores only during game windows or while a game is live; nothing otherwise.
            while (true) {
                val inWindow = Espn.inGameWindow(Calendar.getInstance())
                if (inWindow || scores.any { it.state == "in" }) {
                    val games = withContext(Dispatchers.IO) { runCatching { Nfl.scoreboard() }.getOrNull() }
                    if (games != null) scores = games.filter { it.state == "in" || (inWindow && it.state == "post") }
                } else {
                    scores = emptyList()
                }
                delay(if (scores.any { it.state == "in" }) SCORES_LIVE_MS else SCORES_IDLE_MS)
            }
        }
        launch {
            while (true) {
                delay(HEADLINE_ROTATE_MS)
                index++
            }
        }
    }

    /** Fetches when the cached value is missing or older than [period]; retries failures after a minute. */
    private suspend fun refreshLoop(period: Long, lastFetch: () -> Long?, fetch: () -> Boolean) {
        while (true) {
            val last = lastFetch() ?: 0L
            val age = System.currentTimeMillis() - last
            if (age >= period) {
                val ok = withContext(Dispatchers.IO) { runCatching(fetch).getOrDefault(false) }
                delay(if (ok) period else RETRY_MS)
            } else {
                delay(period - age)
            }
        }
    }

    private companion object {
        const val WEATHER = "weather"
        const val NEWS = "news"
        const val FIRST_FETCH_DELAY_MS = 1_500L
        const val WEATHER_MS = 30 * 60_000L
        const val NEWS_MS = 15 * 60_000L
        const val HEADLINE_ROTATE_MS = 60_000L
        const val RETRY_MS = 60_000L
        const val SCORES_LIVE_MS = 2 * 60_000L
        const val SCORES_IDLE_MS = 10 * 60_000L
    }
}
