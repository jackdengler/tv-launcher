package com.jackdengler.quiet.data

import com.jackdengler.quiet.R
import com.jackdengler.quiet.net.Http
import org.json.JSONObject
import java.net.URLEncoder
import kotlin.math.roundToInt

data class Weather(val temp: Int, val code: Int, val isDay: Boolean, val fetchedAt: Long, val key: String = "") {
    val label: String get() = describe(code)

    /** Icon for the condition; clear and partly-cloudy skies become a moon at night. */
    fun icon(night: Boolean): Int {
        val dark = night || !isDay
        return when (code) {
            0, 1 -> if (dark) R.drawable.ic_wx_moon else R.drawable.ic_wx_sun
            2 -> if (dark) R.drawable.ic_wx_moon else R.drawable.ic_wx_partly
            3 -> R.drawable.ic_wx_cloud
            45, 48 -> R.drawable.ic_wx_fog
            in 51..67, in 80..82 -> R.drawable.ic_wx_rain
            in 71..77, 85, 86 -> R.drawable.ic_wx_snow
            in 95..99 -> R.drawable.ic_wx_storm
            else -> R.drawable.ic_wx_cloud
        }
    }

    fun encode() = JSONObject().put("t", temp).put("c", code).put("d", isDay).put("at", fetchedAt).put("k", key).toString()

    companion object {
        fun decode(s: String?): Weather? = s?.let {
            runCatching {
                val o = JSONObject(it)
                Weather(o.getInt("t"), o.getInt("c"), o.getBoolean("d"), o.getLong("at"), o.optString("k"))
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
            if (fahrenheit) "&temperature_unit=fahrenheit" else ""
        val body = Http.get(url).body ?: return null
        val c = JSONObject(body).getJSONObject("current")
        return Weather(
            c.getDouble("temperature_2m").roundToInt(),
            c.getInt("weather_code"),
            c.optInt("is_day", 1) == 1,
            System.currentTimeMillis(),
        )
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
