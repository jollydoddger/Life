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

    /**
     * Whether the ask bar exists at all. **Off by default**: this app's job is
     * a map, an arrow and a line, and it should be exactly that out of the box.
     * The assistant is a big, slow, paid extra sitting on top — worth having
     * when it is wanted, worth being absent when it is not.
     */
    var Context.assistantEnabled: Boolean
        get() = p(this).getBoolean("assistantEnabled", false)
        set(v) = p(this).edit().putBoolean("assistantEnabled", v).apply()

    /**
     * The Anthropic key for the assistant. Phone-only and never synced: the
     * watch has no use for it, and fewer stored copies of a paid key is
     * strictly better. Never committed anywhere, as ever.
     */
    var Context.anthropicKey: String
        get() = p(this).getString("anthropicKey", "") ?: ""
        set(v) = p(this).edit().putString("anthropicKey", v.trim()).apply()

    /** The tree URI of his GPX library folder, once one is chosen. */
    var Context.libraryFolder: String
        get() = p(this).getString("libraryFolder", "") ?: ""
        set(v) = p(this).edit().putString("libraryFolder", v).apply()

    /**
     * The public-GPS-traces overlay (phone only). Off by default: it is an
     * answer to "does this path exist on the ground", not decoration.
     */
    var Context.tracesEnabled: Boolean
        get() = p(this).getBoolean("tracesEnabled", false)
        set(v) = p(this).edit().putBoolean("tracesEnabled", v).apply()

    /**
     * Public rights of way (phone only). Off by default like every overlay:
     * the OS map already draws them, and this is the loud version.
     */
    var Context.prowEnabled: Boolean
        get() = p(this).getBoolean("prowEnabled", false)
        set(v) = p(this).edit().putBoolean("prowEnabled", v).apply()

    /**
     * Every mapped path and track, not just designated rights of way — the
     * physical network OSM knows about, with no promise you may walk it.
     */
    var Context.allPathsEnabled: Boolean
        get() = p(this).getBoolean("allPathsEnabled", false)
        set(v) = p(this).edit().putBoolean("allPathsEnabled", v).apply()

    /** The RainViewer rainfall radar overlay (phone only, off by default). */
    var Context.radarEnabled: Boolean
        get() = p(this).getBoolean("radarEnabled", false)
        set(v) = p(this).edit().putBoolean("radarEnabled", v).apply()

    /**
     * Wind speed and direction as arrows across the map. Of the weather
     * layers this is the one that changes a plan: a headwind on an exposed
     * ridge is the difference between a good afternoon and a fight.
     */
    var Context.windEnabled: Boolean
        get() = p(this).getBoolean("windEnabled", false)
        set(v) = p(this).edit().putBoolean("windEnabled", v).apply()

    /** Temperature as a colour wash over the map. */
    var Context.tempEnabled: Boolean
        get() = p(this).getBoolean("tempEnabled", false)
        set(v) = p(this).edit().putBoolean("tempEnabled", v).apply()

    /** Cloud cover — grey where it is dull, gold where the sun is out. */
    var Context.cloudEnabled: Boolean
        get() = p(this).getBoolean("cloudEnabled", false)
        set(v) = p(this).edit().putBoolean("cloudEnabled", v).apply()

    /**
     * Hide the route line on the phone's map without touching the stored
     * route or the watch — for reading the map underneath it. Phone-only
     * and never synced; cleared whenever a new route is adopted, because
     * importing a thing you cannot see is a support call.
     */
    var Context.routeHidden: Boolean
        get() = p(this).getBoolean("routeHidden", false)
        set(v) = p(this).edit().putBoolean("routeHidden", v).apply()

    /** When ● was pressed, so a saved walk knows its duration. */
    var Context.recordingStartedAt: Long
        get() = p(this).getLong("recordingStartedAt", 0L)
        set(v) = p(this).edit().putLong("recordingStartedAt", v).apply()

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
