package com.jackdengler.quiet.data

import com.jackdengler.quiet.R
import com.jackdengler.quiet.net.Http
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale
import java.net.URLEncoder
import kotlin.math.roundToInt

data class Hour(val label: String, val temp: Int, val code: Int, val isDay: Boolean)
data class Day(val label: String, val high: Int, val low: Int, val code: Int)

data class Weather(
    val temp: Int,
    val code: Int,
    val isDay: Boolean,
    val fetchedAt: Long,
    val key: String = "",
    val hours: List<Hour> = emptyList(),
    val days: List<Day> = emptyList(),
) {
    val label: String get() = describe(code)

    /** Icon for the condition; clear and partly-cloudy skies become a moon at night. */
    fun icon(night: Boolean): Int = iconFor(code, dark = night || !isDay)

    fun encode(): String = JSONObject().put("t", temp).put("c", code).put("d", isDay).put("at", fetchedAt).put("k", key)
        .put("h", JSONArray().apply { hours.forEach { put(JSONObject().put("l", it.label).put("t", it.temp).put("c", it.code).put("d", it.isDay)) } })
        .put("w", JSONArray().apply { days.forEach { put(JSONObject().put("l", it.label).put("hi", it.high).put("lo", it.low).put("c", it.code)) } })
        .toString()

    companion object {
        fun iconFor(code: Int, dark: Boolean): Int = when (code) {
            0, 1 -> if (dark) R.drawable.ic_wx_moon else R.drawable.ic_wx_sun
            2 -> if (dark) R.drawable.ic_wx_moon else R.drawable.ic_wx_partly
            3 -> R.drawable.ic_wx_cloud
            45, 48 -> R.drawable.ic_wx_fog
            in 51..67, in 80..82 -> R.drawable.ic_wx_rain
            in 71..77, 85, 86 -> R.drawable.ic_wx_snow
            in 95..99 -> R.drawable.ic_wx_storm
            else -> R.drawable.ic_wx_cloud
        }

        fun decode(s: String?): Weather? = s?.let {
            runCatching {
                val o = JSONObject(it)
                val h = o.optJSONArray("h") ?: JSONArray()
                val w = o.optJSONArray("w") ?: JSONArray()
                Weather(
                    o.getInt("t"), o.getInt("c"), o.getBoolean("d"), o.getLong("at"), o.optString("k"),
                    List(h.length()) { i -> h.getJSONObject(i).let { x -> Hour(x.getString("l"), x.getInt("t"), x.getInt("c"), x.getBoolean("d")) } },
                    List(w.length()) { i -> w.getJSONObject(i).let { x -> Day(x.getString("l"), x.getInt("hi"), x.getInt("lo"), x.getInt("c")) } },
                )
            }.getOrNull()
        }

        fun describe(code: Int) = when (code) {
            0 -> "Clear"
            1 -> "Mostly clear"
            2 -> "Partly cloudy"
            3 -> "Cloudy"
            45, 48 -> "Fog"
            in 51..57 -> "Drizzle"
            in 61..67 -> "Rain"
            in 71..77 -> "Snow"
            in 80..82 -> "Showers"
            85, 86 -> "Snow showers"
            in 95..99 -> "Thunderstorms"
            else -> ""
        }
    }
}

data class Place(val name: String, val latitude: Double, val longitude: Double)

/** Open-Meteo (no API key). Blocking; call from Dispatchers.IO. */
object OpenMeteo {
    fun current(lat: Double, lon: Double, fahrenheit: Boolean): Weather? {
        val url = "https://api.open-meteo.com/v1/forecast?latitude=$lat&longitude=$lon" +
            "&current=temperature_2m,weather_code,is_day&timezone=auto" +
            "&hourly=temperature_2m,weather_code,is_day&daily=weather_code,temperature_2m_max,temperature_2m_min&forecast_days=4" +
            if (fahrenheit) "&temperature_unit=fahrenheit" else ""
        val body = Http.get(url).body ?: return null
        val root = JSONObject(body)
        val c = root.getJSONObject("current")
        return Weather(
            c.getDouble("temperature_2m").roundToInt(),
            c.getInt("weather_code"),
            c.optInt("is_day", 1) == 1,
            System.currentTimeMillis(),
            hours = runCatching { hours(root, c.optString("time")) }.getOrDefault(emptyList()),
            days = runCatching { days(root) }.getOrDefault(emptyList()),
        )
    }

    /** The next 12 hours, starting with the current one. Times are local ("2026-10-18T14:00"). */
    private fun hours(root: JSONObject, now: String): List<Hour> {
        val h = root.getJSONObject("hourly")
        val times = h.getJSONArray("time")
        val currentHour = now.take(13) // "yyyy-MM-ddTHH"
        val start = (0 until times.length()).firstOrNull { times.getString(it).take(13) >= currentHour } ?: return emptyList()
        val format = java.time.format.DateTimeFormatter.ofPattern("h a", Locale.getDefault())
        return (start until minOf(start + 12, times.length())).map { i ->
            Hour(
                label = if (i == start) "Now" else java.time.LocalDateTime.parse(times.getString(i)).format(format),
                temp = h.getJSONArray("temperature_2m").getDouble(i).roundToInt(),
                code = h.getJSONArray("weather_code").getInt(i),
                isDay = h.optJSONArray("is_day")?.optInt(i, 1) != 0,
            )
        }
    }

    private fun days(root: JSONObject): List<Day> {
        val d = root.getJSONObject("daily")
        val times = d.getJSONArray("time")
        return (0 until times.length()).map { i ->
            val date = java.time.LocalDate.parse(times.getString(i))
            Day(
                label = if (i == 0) "Today" else date.dayOfWeek.getDisplayName(java.time.format.TextStyle.FULL, Locale.getDefault()),
                high = d.getJSONArray("temperature_2m_max").getDouble(i).roundToInt(),
                low = d.getJSONArray("temperature_2m_min").getDouble(i).roundToInt(),
                code = d.getJSONArray("weather_code").getInt(i),
            )
        }
    }

    fun search(query: String): List<Place> {
        val url = "https://geocoding-api.open-meteo.com/v1/search?count=5&language=en&format=json&name=" +
            URLEncoder.encode(query.trim(), "UTF-8")
        val body = Http.get(url).body ?: return emptyList()
        val arr = JSONObject(body).optJSONArray("results") ?: return emptyList()
        return List(arr.length()) { i ->
            val o = arr.getJSONObject(i)
            val region = listOfNotNull(o.optString("admin1").takeIf { it.isNotBlank() }, o.optString("country_code"))
                .joinToString(", ")
            Place("${o.getString("name")}, $region", o.getDouble("latitude"), o.getDouble("longitude"))
        }
    }
}
