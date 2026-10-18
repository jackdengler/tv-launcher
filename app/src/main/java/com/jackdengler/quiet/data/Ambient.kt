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

    val headline: Headline?
        get() = news?.items?.takeIf { it.isNotEmpty() }?.let { it[index % it.size] }

    suspend fun run() = coroutineScope {
        delay(FIRST_FETCH_DELAY_MS)
        launch {
            refreshLoop(WEATHER_MS, { weather?.takeIf { it.key == prefs.weatherKey }?.fetchedAt }) {
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
    }
}
