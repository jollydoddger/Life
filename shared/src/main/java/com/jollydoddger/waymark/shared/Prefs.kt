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
