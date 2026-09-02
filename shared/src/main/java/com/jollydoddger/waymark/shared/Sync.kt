package com.jollydoddger.waymark.shared

import android.content.Context
import com.google.android.gms.wearable.Asset
import com.google.android.gms.wearable.DataMap
import com.google.android.gms.wearable.DataMapItem
import com.google.android.gms.wearable.PutDataMapRequest
import com.google.android.gms.wearable.Wearable
import com.jollydoddger.waymark.shared.Prefs.arrowColour
import com.jollydoddger.waymark.shared.Prefs.osApiKey
import com.jollydoddger.waymark.shared.Prefs.recording
import com.jollydoddger.waymark.shared.Prefs.watchGpsWarm
import com.jollydoddger.waymark.shared.Prefs.routeColour
import com.jollydoddger.waymark.shared.Prefs.routeReversed
import com.jollydoddger.waymark.shared.Prefs.screenTimeoutSec
import com.jollydoddger.waymark.shared.Prefs.trailColour
import com.jollydoddger.waymark.shared.Prefs.wantRecording
import com.jollydoddger.waymark.shared.Prefs.weatherLine
import com.jollydoddger.waymark.shared.Prefs.weatherLineAt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * The pairing. On import the phone puts one DataItem: the route JSON plus a
 * zip of the corridor tiles it just prefetched, as an Asset. DataItems
 * persist and deliver whenever the watch next connects, which is exactly the
 * requirement: import on the phone, raise the wrist later, it's there. The
 * tiles ride along because the watch re-downloading the same corridor over a
 * Bluetooth proxy would be slow and would bill every tile twice.
 *
 * The API key travels the same way on its own path, so the watch can also
 * fetch live tiles when it has a network.
 */
object Sync {
    const val PATH_ROUTE = "/waymark/route"
    const val PATH_KEY = "/waymark/key"
    const val PATH_STYLE = "/waymark/style"
    const val PATH_RECORD = "/waymark/record"
    const val PATH_POIS = "/waymark/pois"
    const val PATH_WEATHER = "/waymark/weather"

    private val TILE_ENTRY = Regex("""\d{1,2}/\d{1,7}/\d{1,7}\.png""")

    suspend fun sendRoute(ctx: Context, route: Route, store: TileStore) = withContext(Dispatchers.IO) {
        val zip = ByteArrayOutputStream()
        ZipOutputStream(zip).use { out ->
            for (t in Corridor.tilesFor(route)) {
                val f = store.fileFor(t.z, t.x, t.y)
                if (!f.exists()) continue
                out.putNextEntry(ZipEntry("${t.z}/${t.x}/${t.y}.png"))
                f.inputStream().use { it.copyTo(out) }
                out.closeEntry()
            }
        }
        val req = PutDataMapRequest.create(PATH_ROUTE).apply {
            dataMap.putString("json", RouteStore.toJson(route))
            dataMap.putAsset("tiles", Asset.createFromBytes(zip.toByteArray()))
            // Re-importing the same walk must still deliver: identical
            // DataItems are deduplicated, a fresh stamp never is.
            dataMap.putLong("stamp", System.currentTimeMillis())
        }.asPutDataRequest().setUrgent()
        Wearable.getDataClient(ctx).putDataItem(req).await()
    }

    suspend fun sendKey(ctx: Context, key: String) {
        val req = PutDataMapRequest.create(PATH_KEY).apply {
            dataMap.putString("key", key)
            dataMap.putLong("stamp", System.currentTimeMillis())
        }.asPutDataRequest().setUrgent()
        Wearable.getDataClient(ctx).putDataItem(req).await()
    }

    /**
     * Everything about how the map looks and behaves, set on the phone.
     * Reversal rides along because the watch no longer has a ⇄ button of its
     * own — without this, flipping the arrows on the phone would leave the
     * wrist pointing the other way for ever.
     */
    suspend fun sendStyle(
        ctx: Context,
        route: Int,
        arrow: Int,
        trail: Int,
        reversed: Boolean,
        screenTimeoutSec: Int,
        gpsWarm: Boolean,
    ) {
        val req = PutDataMapRequest.create(PATH_STYLE).apply {
            dataMap.putInt("route", route)
            dataMap.putInt("arrow", arrow)
            dataMap.putInt("trail", trail)
            dataMap.putBoolean("reversed", reversed)
            dataMap.putInt("timeout", screenTimeoutSec)
            dataMap.putBoolean("gpsWarm", gpsWarm)
            dataMap.putLong("stamp", System.currentTimeMillis())
        }.asPutDataRequest().setUrgent()
        Wearable.getDataClient(ctx).putDataItem(req).await()
    }

    /** Convenience: send whatever the phone currently holds. */
    suspend fun sendStyle(ctx: Context) = sendStyle(
        ctx, ctx.routeColour, ctx.arrowColour, ctx.trailColour, ctx.routeReversed,
        ctx.screenTimeoutSec, ctx.watchGpsWarm,
    )

    /**
     * Start/stop recording on the other device too, so one press covers both.
     * Each device then records from its own GPS — which is what makes the
     * trail right on whichever screen he happens to look at, and means no
     * geometry has to cross the link while he walks.
     */
    suspend fun sendRecording(ctx: Context, on: Boolean) {
        val req = PutDataMapRequest.create(PATH_RECORD).apply {
            dataMap.putBoolean("on", on)
            dataMap.putLong("stamp", System.currentTimeMillis())
        }.asPutDataRequest().setUrgent()
        Wearable.getDataClient(ctx).putDataItem(req).await()
    }

    // --- receiving -----------------------------------------------------------
    //
    // One set of appliers, used by both the change-event path and the pull
    // below. Two copies of this logic would drift, and a colour that arrives
    // down one route but not the other is exactly the bug this round fixes.

    fun applyKey(ctx: Context, data: DataMap) {
        val key = data.getString("key")
        if (!key.isNullOrEmpty()) ctx.osApiKey = key
    }

    fun applyStyle(ctx: Context, data: DataMap) {
        ctx.routeColour = data.getInt("route", Colours.DEFAULT_ROUTE)
        ctx.arrowColour = data.getInt("arrow", Colours.DEFAULT_ARROW)
        ctx.trailColour = data.getInt("trail", Colours.DEFAULT_TRAIL)
        ctx.routeReversed = data.getBoolean("reversed", false)
        ctx.screenTimeoutSec = data.getInt("timeout", Prefs.DEFAULT_SCREEN_TIMEOUT_SEC)
        ctx.watchGpsWarm = data.getBoolean("gpsWarm", true)
    }

    /**
     * Records what the phone asked for. Starting is left to the open app — a
     * foreground service cannot be launched from the background on modern
     * Android — but a *stop* is honoured immediately wherever it arrives,
     * because a Stop on the phone must never leave the watch recording.
     */
    fun applyRecordWish(ctx: Context, data: DataMap) {
        val on = data.getBoolean("on", false)
        ctx.wantRecording = on
        if (!on && ctx.recording) TrackingService.stop(ctx)
    }

    /** The assistant's found places, so the wrist shows the same markers. */
    suspend fun sendPois(ctx: Context, pois: List<Poi>) {
        val req = PutDataMapRequest.create(PATH_POIS).apply {
            dataMap.putString("json", Pois.toJson(pois))
            dataMap.putLong("stamp", System.currentTimeMillis())
        }.asPutDataRequest().setUrgent()
        Wearable.getDataClient(ctx).putDataItem(req).await()
    }

    fun applyPois(ctx: Context, data: DataMap) {
        PoiStore.save(ctx, Pois.fromJson(data.getString("json") ?: "[]"))
    }

    /**
     * The hours ahead, in words, for the wrist. [title] is set only when
     * something changed that is worth a buzz — "Rain from 14:00" — and the
     * watch raises its own notification for it; a re-read that found the
     * same forecast travels with no title and only refreshes the line on
     * the watch's map. The phone's own notification is local-only, so
     * this is the one the wrist gets.
     */
    suspend fun sendWeather(ctx: Context, line: String, title: String?, text: String?) {
        val req = PutDataMapRequest.create(PATH_WEATHER).apply {
            dataMap.putString("line", line)
            dataMap.putString("title", title ?: "")
            dataMap.putString("text", text ?: "")
            dataMap.putLong("stamp", System.currentTimeMillis())
        }.asPutDataRequest().setUrgent()
        Wearable.getDataClient(ctx).putDataItem(req).await()
    }

    /** Stores the line; returns the headline to raise, or null. A stamp
     *  already seen raises nothing — the pull on open re-delivers items
     *  the listener already handled. */
    fun applyWeather(ctx: Context, data: DataMap): Pair<String, String>? {
        val stamp = data.getLong("stamp", 0L)
        val seen = ctx.weatherLineAt
        ctx.weatherLine = data.getString("line") ?: ""
        ctx.weatherLineAt = stamp
        val title = data.getString("title") ?: ""
        if (title.isBlank() || stamp == seen) return null
        return title to (data.getString("text") ?: "")
    }

    /**
     * Ask the Data Layer what the current settings are, rather than waiting to
     * be told.
     *
     * Change events are a one-shot delivery: if the watch was asleep, out of
     * range, or its background listener was throttled when the phone sent, the
     * old value simply stays for ever, because nothing ever asks again. Data
     * items persist, so reading them on open is the reliable half of the pair.
     * Routes are left out deliberately — they carry a tile-zip Asset, are much
     * bigger, and their own delivery path already works.
     */
    suspend fun pullAll(ctx: Context): Boolean = withContext(Dispatchers.IO) {
        val buffer = Wearable.getDataClient(ctx).dataItems.await()
        try {
            var applied = false
            for (item in buffer) {
                val data = DataMapItem.fromDataItem(item).dataMap
                when (item.uri.path) {
                    PATH_KEY -> { applyKey(ctx, data); applied = true }
                    PATH_STYLE -> { applyStyle(ctx, data); applied = true }
                    PATH_RECORD -> { applyRecordWish(ctx, data); applied = true }
                    PATH_POIS -> { applyPois(ctx, data); applied = true }
                    PATH_WEATHER -> { applyWeather(ctx, data); applied = true }
                }
            }
            applied
        } finally {
            // A DataItemBuffer holds native memory until released.
            buffer.release()
        }
    }

    /** Watch side: unpack a received route item — the JSON and the tile zip. */
    suspend fun applyRouteItem(ctx: Context, item: DataMapItem): Route? = withContext(Dispatchers.IO) {
        val map = item.dataMap
        val route = map.getString("json")?.let { RouteStore.fromJson(it) } ?: return@withContext null
        RouteStore.save(ctx, route)

        val asset = map.getAsset("tiles")
        if (asset != null) {
            val resp = Wearable.getDataClient(ctx).getFdForAsset(asset).await()
            val tilesDir = File(ctx.filesDir, "tiles")
            ZipInputStream(resp.inputStream).use { zin ->
                var entry = zin.nextEntry
                while (entry != null) {
                    // Names are data from the paired phone, but a zip path is
                    // a zip path: only exact z/x/y.png shapes are written.
                    if (!entry.isDirectory && TILE_ENTRY.matches(entry.name)) {
                        val f = File(tilesDir, entry.name)
                        f.parentFile?.mkdirs()
                        val tmp = File(f.parentFile, f.name + ".tmp")
                        tmp.outputStream().use { zin.copyTo(it) }
                        tmp.renameTo(f)
                    }
                    entry = zin.nextEntry
                }
            }
        }
        route
    }
}
