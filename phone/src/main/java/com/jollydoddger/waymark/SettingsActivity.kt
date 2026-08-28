package com.jollydoddger.waymark

import android.app.Activity
import android.os.Bundle
import android.text.InputType
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.jollydoddger.waymark.shared.Bng
import com.jollydoddger.waymark.shared.Prefs.osApiKey
import com.jollydoddger.waymark.shared.Sync
import com.jollydoddger.waymark.shared.TileGrid
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL

/**
 * One setting: the OS Data Hub API key. The test fetches a real Explorer-zoom
 * tile, because a key on the wrong plan works fine at every zoom except the
 * ones this app exists for — a 403 there must come back as words about the
 * plan, not look like a bug on a hillside.
 */
class SettingsActivity : Activity() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val d = resources.displayMetrics.density
        fun dp(v: Int) = (v * d).toInt()

        val intro = TextView(this).apply {
            textSize = 15f
            text = "Waymark draws Ordnance Survey Leisure maps (the Explorer / Landranger " +
                "paper styles). It needs your own OS Data Hub API key from osdatahub.os.uk — " +
                "a Maps API project key, on the Premium (pay-as-you-go) plan. The free plan " +
                "stops at the 1:50k-ish zooms; Explorer 1:25k detail is Premium only.\n\n" +
                "The key lives on this phone (and is passed to your watch), nowhere else."
        }

        val keyBox = EditText(this).apply {
            hint = "OS Maps API key"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            setText(osApiKey)
        }

        val result = TextView(this).apply { textSize = 15f }

        val save = Button(this).apply {
            text = "Save"
            setOnClickListener {
                osApiKey = keyBox.text.toString()
                result.text = "Saved. Sending to watch…"
                scope.launch {
                    try {
                        Sync.sendKey(this@SettingsActivity, osApiKey)
                        result.text = "Saved, and on its way to the watch."
                    } catch (e: Exception) {
                        result.text = "Saved on the phone. Watch not reachable just now — " +
                            "it will get the key with the next route import."
                    }
                }
            }
        }

        val test = Button(this).apply {
            text = "Test key"
            setOnClickListener {
                val key = keyBox.text.toString().trim()
                result.text = "Fetching an Explorer-zoom tile…"
                scope.launch { result.text = testKey(key) }
            }
        }

        val row = LinearLayout(this).apply {
            addView(save, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            addView(test, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        }

        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(28), dp(20), dp(20))
            addView(intro)
            addView(keyBox, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = dp(16) })
            addView(row)
            addView(result, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = dp(12) })
        }
        setContentView(ScrollView(this).apply { addView(col) })
    }

    private suspend fun testKey(key: String): String = withContext(Dispatchers.IO) {
        if (key.isEmpty()) return@withContext "No key entered."
        // A z9 (1:25k Explorer) tile over Snowdonia — the zoom that separates
        // the plans.
        val en = Bng.fromWgs84(53.068, -4.076)
        val url = TileGrid.url(TileGrid.MAX_Z, TileGrid.tileX(en.e, TileGrid.MAX_Z), TileGrid.tileY(en.n, TileGrid.MAX_Z), key)
        try {
            val conn = URL(url).openConnection() as HttpURLConnection
            conn.connectTimeout = 10_000
            conn.readTimeout = 15_000
            try {
                when (val code = conn.responseCode) {
                    200 -> "Key works — Explorer 1:25k detail is available. ✓"
                    401 -> "The key was rejected outright (HTTP 401) — check it was copied whole."
                    403 -> "The key is real but refused at Explorer detail (HTTP 403) — " +
                        "your OS Data Hub project is probably on the free plan. " +
                        "Switch it to Premium (pay-as-you-go) at osdatahub.os.uk."
                    429 -> "The key works but is being rate-limited (HTTP 429) — try again shortly."
                    else -> "Unexpected answer from OS (HTTP $code)."
                }
            } finally {
                conn.disconnect()
            }
        } catch (e: Exception) {
            "Couldn't reach the OS servers: ${e.message ?: "no connection"}"
        }
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }
}
