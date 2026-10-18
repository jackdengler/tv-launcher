package com.jackdengler.quiet.photos

import kotlin.math.PI
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin

/** Time of day as seen in a photo. */
enum class Scene(val tag: String) { DAWN("dawn"), DAY("day"), GOLDEN("golden"), NIGHT("night") }

/** Sky conditions as seen in a photo. */
enum class Sky(val tag: String) { CLEAR("clear"), CLOUDY("cloudy"), FOG("fog"), RAIN("rain") }

object Sun {
    /** Santa Monica Pier: the live scene follows the sun there. */
    const val SANTA_MONICA_LAT = 34.0094
    const val SANTA_MONICA_LON = -118.4973

    /** Solar elevation in degrees (low-precision almanac formula; ±0.5° is plenty here). */
    fun elevation(lat: Double, lon: Double, millis: Long): Double {
        val d = millis / 86_400_000.0 + 2440587.5 - 2451545.0
        val g = rad(357.529 + 0.98560028 * d)
        val q = 280.459 + 0.98564736 * d
        val l = rad(q + 1.915 * sin(g) + 0.020 * sin(2 * g))
        val e = rad(23.439 - 0.00000036 * d)
        val ra = atan2(cos(e) * sin(l), cos(l))
        val dec = asin(sin(e) * sin(l))
        val gmstHours = ((18.697374558 + 24.06570982441908 * d) % 24 + 24) % 24
        val ha = rad(gmstHours * 15 + lon) - ra
        val la = rad(lat)
        return deg(asin(sin(la) * sin(dec) + cos(la) * cos(dec) * cos(ha)))
    }

    fun scene(lat: Double, lon: Double, millis: Long): Scene {
        val now = elevation(lat, lon, millis)
        val rising = elevation(lat, lon, millis + 10 * 60_000) > now
        return when {
            now < -6 -> Scene.NIGHT
            rising && now < 10 -> Scene.DAWN
            !rising && now < 12 -> Scene.GOLDEN
            else -> Scene.DAY
        }
    }

    /** Open-Meteo / WMO weather code to the sky we want to see. */
    fun sky(weatherCode: Int?): Sky = when (weatherCode) {
        null, 0, 1 -> Sky.CLEAR
        2, 3 -> Sky.CLOUDY
        45, 48 -> Sky.FOG
        in 51..67, in 80..82, in 95..99 -> Sky.RAIN
        else -> Sky.CLOUDY
    }

    private fun rad(d: Double) = d * PI / 180
    private fun deg(r: Double) = r * 180 / PI
}
