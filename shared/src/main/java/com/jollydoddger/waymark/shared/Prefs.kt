package com.jollydoddger.waymark.shared

import android.content.Context
import android.content.SharedPreferences

/**
 * The app's two pieces of standing state. The OS Data Hub API key is entered
 * on the phone and reaches the watch over the Data Layer — it is never
 * committed anywhere (same rule as every other key in this household's apps).
 * The reverse flag is per-device on purpose: flipping the route's arrows on
 * the wrist shouldn't silently flip them on the phone mid-walk.
 */
object Prefs {
    private const val FILE = "waymark"

    /** Two minutes lit: long enough to read a map, short enough to matter. */
    const val DEFAULT_SCREEN_TIMEOUT_SEC = 120

    /**
     * Offered on the phone; 0 means never let go.
     *
     * This is how long Waymark *holds* the watch screen awake, not when the
     * screen goes off — an app cannot switch a display off without
     * device-admin powers. Once the hold expires the watch's own screen
     * timeout applies, so the shortest settings amount to "stop interfering
     * and let the watch behave normally", which is also the state in which it
     * genuinely sleeps rather than being held awake.
     */
    val SCREEN_TIMEOUTS: List<Pair<String, Int>> = listOf(
        "3s" to 3,
        "10s" to 10,
        "30s" to 30,
        "1 min" to 60,
        "2 min" to 120,
        "5 min" to 300,
        "Never" to 0,
    )

    private fun p(ctx: Context): SharedPreferences =
        ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    var Context.osApiKey: String
        get() = p(this).getString("osApiKey", "") ?: ""
        set(v) = p(this).edit().putString("osApiKey", v.trim()).apply()

    var Context.routeReversed: Boolean
        get() = p(this).getBoolean("routeReversed", false)
        set(v) = p(this).edit().putBoolean("routeReversed", v).apply()

    // Chosen on the phone, mirrored to the watch: it is one app on two devices.
    var Context.routeColour: Int
        get() = p(this).getInt("routeColour", Colours.DEFAULT_ROUTE)
        set(v) = p(this).edit().putInt("routeColour", v).apply()

    var Context.arrowColour: Int
        get() = p(this).getInt("arrowColour", Colours.DEFAULT_ARROW)
        set(v) = p(this).edit().putInt("arrowColour", v).apply()

    var Context.trailColour: Int
        get() = p(this).getInt("trailColour", Colours.DEFAULT_TRAIL)
        set(v) = p(this).edit().putInt("trailColour", v).apply()

    /**
     * How long the watch holds its screen on before letting it sleep. Only the
     * watch honours it — the phone is left to its own system timeout. Set on
     * the phone and synced, because the watch has no room for settings.
     */
    var Context.screenTimeoutSec: Int
        get() = p(this).getInt("screenTimeoutSec", DEFAULT_SCREEN_TIMEOUT_SEC)
        set(v) = p(this).edit().putInt("screenTimeoutSec", v).apply()

    /**
     * Whether a walk is being recorded. Kept here rather than in the service
     * so the UI can draw the right button before the service has started, and
     * so a restart after the process dies knows what it was doing.
     */
    var Context.recording: Boolean
        get() = p(this).getBoolean("recording", false)
        set(v) = p(this).edit().putBoolean("recording", v).apply()

    /**
     * What he has *asked* for, as opposed to what is running. The two differ
     * on the watch: Android forbids starting a foreground service from the
     * background, so a Start pressed on the phone cannot wake a closed watch
     * app. It records the wish here instead, and the watch honours it the
     * moment it is next opened — which is the only time its map is any use.
     */
    var Context.wantRecording: Boolean
        get() = p(this).getBoolean("wantRecording", false)
        set(v) = p(this).edit().putBoolean("wantRecording", v).apply()
}
