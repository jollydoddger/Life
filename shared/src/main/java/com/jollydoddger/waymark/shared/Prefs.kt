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
}
