package com.jollydoddger.waymark

import android.app.Activity
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.jollydoddger.waymark.shared.Bng
import com.jollydoddger.waymark.shared.En
import com.jollydoddger.waymark.shared.Sun
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import kotlin.math.roundToInt

/**
 * The forecast for wherever the map is, hour by hour and in full.
 *
 * The overlay and this page answer different questions and neither is the
 * other. An overlay is for "where is the rain and which way is it moving" —
 * a spatial question, and the only kind a map can answer. "What will it be
 * doing at four" is a *column of numbers*, and painting numbers across a
 * hillside has never made them easier to read.
 *
 * Centred on the map's centre rather than his GPS, deliberately: he asked
 * for the weather "wherever my map is", which is what you want when the
 * walk you are thinking about is an hour's drive away.
 */
class WeatherActivity : Activity() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private lateinit var body: LinearLayout
    private lateinit var status: TextView

    /** "Forecast for …" — the grid reference straight away, the place name
     *  when Nominatim answers. A page that says only SH 31 78 is a page that
     *  makes him work out where he asked about. */
    private var placeLine: TextView? = null

    companion object {
        const val EXTRA_E = "e"
        const val EXTRA_N = "n"

        /** WMO weather codes, in the words a person would use. */
        fun describe(code: Int): String = when (code) {
            0 -> "Clear"
            1 -> "Mainly clear"
            2 -> "Part cloud"
            3 -> "Overcast"
            45, 48 -> "Fog"
            51 -> "Light drizzle"
            53 -> "Drizzle"
            55 -> "Heavy drizzle"
            56, 57 -> "Freezing drizzle"
            61 -> "Light rain"
            63 -> "Rain"
            65 -> "Heavy rain"
            66, 67 -> "Freezing rain"
            71 -> "Light snow"
            73 -> "Snow"
            75 -> "Heavy snow"
            77 -> "Snow grains"
            80 -> "Light showers"
            81 -> "Showers"
            82 -> "Heavy showers"
            85, 86 -> "Snow showers"
            95 -> "Thunderstorm"
            96, 99 -> "Thunder and hail"
            else -> "—"
        }

        /** An arrow pointing the way the wind is *going*, which is the way
         *  it will push you — the direction reported is where it comes
         *  from, and drawing that arrow gets it backwards. */
        fun arrow(fromDeg: Double): String {
            val going = (fromDeg + 180) % 360
            val i = (((going + 22.5) % 360) / 45).toInt()
            return listOf("↑", "↗", "→", "↘", "↓", "↙", "←", "↖")[i.coerceIn(0, 7)]
        }
    }

    override fun onCreate(saved: Bundle?) {
        super.onCreate(saved)
        val e = intent.getDoubleExtra(EXTRA_E, Double.NaN)
        val n = intent.getDoubleExtra(EXTRA_N, Double.NaN)

        status = TextView(this).apply {
            textSize = 14f
            setTextColor(Color.argb(220, 200, 210, 202))
            setPadding(dp(18), dp(16), dp(18), dp(16))
            text = "Fetching the forecast…"
        }
        body = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, 0, 0, dp(28))
            addView(status)
        }
        setContentView(
            ScrollView(this).apply {
                setBackgroundColor(Color.rgb(18, 21, 19))
                addView(body)
                setOnApplyWindowInsetsListener { v, insets ->
                    v.setPadding(0, insets.systemWindowInsetTop, 0, insets.systemWindowInsetBottom)
                    insets
                }
            },
        )

        if (e.isNaN() || n.isNaN()) {
            status.text = "No map position was passed in."
            return
        }
        load(En(e, n))
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun load(at: En) {
        val (lat, lon) = Bng.toWgs84(at)
        scope.launch {
            val got = withContext(Dispatchers.IO) { runCatching { fetch(lat, lon) } }
            got.onFailure { e ->
                // The light is computed on the phone, so a dead connection
                // still leaves the half that decides whether a walk is safe.
                status.text = "Couldn't get the forecast: " +
                    Assistant.explain(e) +
                    "\n\nSunrise and sunset below are worked out on the phone and " +
                    "need no signal."
                addView(lightOnly(lat, lon))
            }
            got.onSuccess { json ->
                runCatching { render(json, at, lat, lon) }.onFailure {
                    status.text = "The forecast came back in a shape this page did not " +
                        "expect (${it.message ?: it.javaClass.simpleName})."
                }
            }
        }
    }

    /**
     * Put a name to the place, the same way saving a walk does. Never waited
     * on: the forecast is already on screen, and a village name arriving a
     * second later is a bonus, not a dependency.
     */
    private fun namePlace(lat: Double, lon: Double, ref: String) {
        scope.launch {
            val named = withContext(Dispatchers.IO) {
                runCatching {
                    val json = Net.get(
                        "https://nominatim.openstreetmap.org/reverse?format=json&zoom=13" +
                            "&lat=%.5f&lon=%.5f".format(java.util.Locale.UK, lat, lon),
                        timeoutMs = 6_000,
                    )
                    JSONObject(json).optString("display_name")
                        .split(",").take(2).joinToString(",").trim()
                }.getOrNull().orEmpty()
            }
            if (named.isNotBlank()) {
                placeLine?.text = "$named · $ref"
            }
        }
    }

    private fun fetch(lat: Double, lon: Double): JSONObject = JSONObject(
        Net.get(
            "https://api.open-meteo.com/v1/forecast?latitude=%.4f&longitude=%.4f"
                .format(java.util.Locale.UK, lat, lon) +
                "&hourly=temperature_2m,apparent_temperature,relative_humidity_2m," +
                "precipitation_probability,precipitation,weather_code,cloud_cover," +
                "visibility,wind_speed_10m,wind_direction_10m,wind_gusts_10m," +
                "surface_pressure,uv_index,freezing_level_height" +
                "&daily=weather_code,temperature_2m_max,temperature_2m_min," +
                "precipitation_sum,precipitation_probability_max,wind_speed_10m_max," +
                "wind_gusts_10m_max,uv_index_max,sunrise,sunset" +
                "&forecast_days=7&wind_speed_unit=mph&timezone=auto",
            timeoutMs = 25_000,
        ),
    )

    // --- drawing ------------------------------------------------------------

    private fun addView(v: View) = body.addView(v)

    private fun heading(text: String, big: Boolean = false) = TextView(this).apply {
        this.text = text
        textSize = if (big) 20f else 15f
        setTypeface(typeface, Typeface.BOLD)
        setTextColor(Color.WHITE)
        setBackgroundColor(if (big) Color.TRANSPARENT else Color.argb(255, 28, 54, 40))
        setPadding(dp(18), dp(if (big) 18 else 10), dp(18), dp(if (big) 6 else 10))
    }

    private fun quiet(text: String) = TextView(this).apply {
        this.text = text
        textSize = 13f
        setTextColor(Color.argb(210, 172, 182, 174))
        setPadding(dp(18), dp(2), dp(18), dp(10))
    }

    private fun lightOnly(lat: Double, lon: Double): View {
        val now = System.currentTimeMillis()
        val hhmm = java.text.SimpleDateFormat("HH:mm", java.util.Locale.UK)
        val rise = Sun.sunrise(now, lat, lon)?.let { hhmm.format(java.util.Date(it)) } ?: "—"
        val set = Sun.sunset(now, lat, lon)?.let { hhmm.format(java.util.Date(it)) } ?: "—"
        val dusk = Sun.civilDusk(now, lat, lon)?.let { hhmm.format(java.util.Date(it)) } ?: "—"
        return quiet("Sunrise $rise · sunset $set · useful light gone by $dusk")
    }

    /** One hour: time, what it is doing, temperature, rain, wind. */
    private fun hourRow(
        time: String, code: Int, temp: Double, feels: Double,
        prob: Int, rain: Double, wind: Double, gust: Double, dir: Double,
        night: Boolean,
    ): View = LinearLayout(this).apply {
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(18), dp(7), dp(18), dp(7))
        // Night hours are dimmed rather than hidden: he might well be out in
        // them, and a row that is missing cannot be read at all.
        setBackgroundColor(if (night) Color.argb(255, 22, 25, 23) else Color.TRANSPARENT)

        fun cell(text: String, weight: Float, size: Float = 14f, colour: Int = Color.WHITE, bold: Boolean = false) =
            TextView(this@WeatherActivity).apply {
                this.text = text
                textSize = size
                setTextColor(colour)
                if (bold) setTypeface(typeface, Typeface.BOLD)
            } to LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, weight)

        cell(time, 1.1f, 15f, Color.argb(255, 200, 210, 202), bold = true).let { addView(it.first, it.second) }
        // The picture before the numbers: running an eye down this column
        // shows a shower building without reading a figure.
        addView(
            ImageView(this@WeatherActivity).apply {
                setImageDrawable(WeatherIcon(code, resources.displayMetrics.density, rain))
            },
            LinearLayout.LayoutParams(dp(26), dp(26)).apply { rightMargin = dp(6) },
        )
        cell("${temp.roundToInt()}°", 0.8f, 17f, tempColour(temp), bold = true)
            .let { addView(it.first, it.second) }
        // Feels-like only when it genuinely differs — wind chill is the
        // number that matters on a ridge, and repeating the air temperature
        // beside itself teaches him to stop reading the column.
        val feelsText = if (kotlin.math.abs(feels - temp) >= 2) "(${feels.roundToInt()}°)" else ""
        cell(feelsText, 0.8f, 12f, Color.argb(200, 170, 180, 172)).let { addView(it.first, it.second) }
        val rainText = when {
            rain >= 0.05 -> "%.1fmm %d%%".format(rain, prob)
            prob >= 20 -> "$prob%"
            else -> "—"
        }
        cell(rainText, 1.5f, 13f, rainColour(rain, prob)).let { addView(it.first, it.second) }
        val gustText = if (gust > wind + 5) " g${gust.roundToInt()}" else ""
        cell(
            "${arrow(dir)} ${wind.roundToInt()}$gustText", 1.5f, 13f,
            if (gust >= 35) Color.rgb(240, 170, 90) else Color.argb(235, 210, 220, 212),
        ).let { addView(it.first, it.second) }
        cell(describe(code), 1.7f, 12f, Color.argb(215, 185, 195, 187))
            .let { addView(it.first, it.second) }
    }

    private fun tempColour(c: Double): Int = when {
        c <= 0 -> Color.rgb(150, 200, 255)
        c <= 5 -> Color.rgb(180, 215, 245)
        c <= 12 -> Color.WHITE
        c <= 18 -> Color.rgb(200, 240, 190)
        c <= 24 -> Color.rgb(250, 220, 140)
        else -> Color.rgb(250, 170, 120)
    }

    private fun rainColour(mm: Double, prob: Int): Int = when {
        mm >= 1.0 -> Color.rgb(120, 190, 255)
        mm >= 0.2 -> Color.rgb(150, 205, 250)
        prob >= 50 -> Color.rgb(180, 200, 215)
        else -> Color.argb(180, 165, 175, 167)
    }

    private fun render(o: JSONObject, at: En, lat: Double, lon: Double) {
        body.removeAllViews()
        val hourly = o.getJSONObject("hourly")
        val times = hourly.getJSONArray("time")
        val temp = hourly.getJSONArray("temperature_2m")
        val feels = hourly.optJSONArray("apparent_temperature")
        val prob = hourly.optJSONArray("precipitation_probability")
        val rain = hourly.getJSONArray("precipitation")
        val code = hourly.optJSONArray("weather_code")
        val cloud = hourly.optJSONArray("cloud_cover")
        val vis = hourly.optJSONArray("visibility")
        val wind = hourly.getJSONArray("wind_speed_10m")
        val dir = hourly.optJSONArray("wind_direction_10m")
        val gust = hourly.optJSONArray("wind_gusts_10m")
        val freeze = hourly.optJSONArray("freezing_level_height")

        val iso = java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm", java.util.Locale.UK)
        val hhmm = java.text.SimpleDateFormat("HH:mm", java.util.Locale.UK)
        val dayFmt = java.text.SimpleDateFormat("EEEE d MMMM", java.util.Locale.UK)
        val now = System.currentTimeMillis()

        // --- where, and what it is doing right now --------------------------
        val ref = Bng.gridRef(at, 3) ?: "off the National Grid"
        addView(heading("Forecast for this map", big = true))
        val place = quiet("$ref · %.4f, %.4f".format(java.util.Locale.UK, lat, lon))
        placeLine = place
        addView(place)
        namePlace(lat, lon, ref)

        var nowIdx = -1
        for (i in 0 until times.length()) {
            val t = iso.parse(times.getString(i))?.time ?: continue
            if (t <= now + 30 * 60_000L) nowIdx = i else break
        }
        if (nowIdx >= 0) {
            val v = vis?.optDouble(nowIdx, Double.NaN) ?: Double.NaN
            val fog = !v.isNaN() && v < 1000
            addView(
                LinearLayout(this).apply {
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding(dp(18), dp(6), dp(18), dp(2))
                    addView(
                        ImageView(this@WeatherActivity).apply {
                            setImageDrawable(
                                WeatherIcon(
                                    code?.optInt(nowIdx) ?: -1,
                                    resources.displayMetrics.density,
                                    rain.optDouble(nowIdx, 0.0),
                                ),
                            )
                        },
                        LinearLayout.LayoutParams(dp(56), dp(56)).apply { rightMargin = dp(12) },
                    )
                    addView(
                        TextView(this@WeatherActivity).apply {
                            text = "${temp.getDouble(nowIdx).roundToInt()}°  " +
                                describe(code?.optInt(nowIdx) ?: -1)
                            textSize = 30f
                            setTypeface(typeface, Typeface.BOLD)
                            setTextColor(Color.WHITE)
                        },
                    )
                },
            )
            val bits = ArrayList<String>()
            feels?.let { bits.add("feels ${it.getDouble(nowIdx).roundToInt()}°") }
            bits.add(
                "wind ${wind.getDouble(nowIdx).roundToInt()} mph " +
                    Sun.compass(dir?.optDouble(nowIdx, 0.0) ?: 0.0),
            )
            gust?.let { bits.add("gusting ${it.getDouble(nowIdx).roundToInt()}") }
            cloud?.let { bits.add("${it.optInt(nowIdx)}% cloud") }
            if (!v.isNaN()) {
                bits.add(if (fog) "FOG — visibility ${v.roundToInt()} m" else "visibility ${(v / 1000).roundToInt()} km")
            }
            freeze?.optDouble(nowIdx, Double.NaN)?.takeIf { !it.isNaN() && it < 1200 }
                ?.let { bits.add("freezing level ${it.roundToInt()} m") }
            addView(quiet(bits.joinToString(" · ")))
        }
        addView(lightOnly(lat, lon))

        // --- the days -------------------------------------------------------
        val daily = o.optJSONObject("daily")
        val dayTimes = daily?.optJSONArray("time")
        val dMax = daily?.optJSONArray("temperature_2m_max")
        val dMin = daily?.optJSONArray("temperature_2m_min")
        val dRain = daily?.optJSONArray("precipitation_sum")
        val dProb = daily?.optJSONArray("precipitation_probability_max")
        val dWind = daily?.optJSONArray("wind_speed_10m_max")
        val dGust = daily?.optJSONArray("wind_gusts_10m_max")
        val dRise = daily?.optJSONArray("sunrise")
        val dSet = daily?.optJSONArray("sunset")

        var lastDay = ""
        for (i in 0 until times.length()) {
            val t = iso.parse(times.getString(i))?.time ?: continue
            // Yesterday's hours are not a forecast and only push today down
            // the screen.
            if (t < now - 60 * 60_000L) continue
            val day = times.getString(i).substringBefore('T')
            if (day != lastDay) {
                lastDay = day
                val d = dayTimes?.let { arr ->
                    (0 until arr.length()).firstOrNull { arr.getString(it) == day }
                } ?: -1
                val label = dayFmt.format(java.util.Date(t))
                addView(heading(label))
                if (d >= 0) {
                    val parts = ArrayList<String>()
                    if (dMax != null && dMin != null) {
                        parts.add("${dMin.getDouble(d).roundToInt()}–${dMax.getDouble(d).roundToInt()}°")
                    }
                    dRain?.let { r ->
                        val mm = r.optDouble(d, 0.0)
                        parts.add(
                            if (mm >= 0.1) "%.1f mm rain".format(mm)
                            else "dry" + (dProb?.let { " (${it.optInt(d)}% chance)" } ?: ""),
                        )
                    }
                    dWind?.let { w ->
                        val g = dGust?.optDouble(d, 0.0) ?: 0.0
                        parts.add(
                            "wind to ${w.getDouble(d).roundToInt()} mph" +
                                if (g > 0) ", gusts ${g.roundToInt()}" else "",
                        )
                    }
                    if (dRise != null && dSet != null) {
                        val r = iso.parse(dRise.getString(d))?.time
                        val s = iso.parse(dSet.getString(d))?.time
                        if (r != null && s != null) {
                            parts.add("${hhmm.format(java.util.Date(r))}–${hhmm.format(java.util.Date(s))}")
                        }
                    }
                    addView(quiet(parts.joinToString(" · ")))
                }
            }
            val hour = java.util.Calendar.getInstance().apply { timeInMillis = t }
                .get(java.util.Calendar.HOUR_OF_DAY)
            addView(
                hourRow(
                    time = hhmm.format(java.util.Date(t)),
                    code = code?.optInt(i) ?: -1,
                    temp = temp.getDouble(i),
                    feels = feels?.optDouble(i, temp.getDouble(i)) ?: temp.getDouble(i),
                    prob = prob?.optInt(i) ?: 0,
                    rain = rain.optDouble(i, 0.0),
                    wind = wind.getDouble(i),
                    gust = gust?.optDouble(i, 0.0) ?: 0.0,
                    dir = dir?.optDouble(i, 0.0) ?: 0.0,
                    night = hour < 6 || hour >= 21,
                ),
            )
        }
        addView(
            quiet(
                "Open-Meteo, hourly. °C, mph, mm; the arrow points the way the wind " +
                    "is going.\n\nHourly is as fine as UK forecast data goes — the " +
                    "15-minute figures some apps show here are interpolated between " +
                    "these same hours, not extra detail. For rain in the next half " +
                    "hour the radar on the map is the real thing, and it steps in " +
                    "ten-minute frames.",
            ),
        )
    }
}
