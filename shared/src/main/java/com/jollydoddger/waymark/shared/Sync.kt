package com.jollydoddger.waymark.shared

import android.content.Context
import com.google.android.gms.wearable.Asset
import com.google.android.gms.wearable.DataMapItem
import com.google.android.gms.wearable.PutDataMapRequest
import com.google.android.gms.wearable.Wearable
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

    /** His colours, so the wrist and the pocket draw the same map. */
    suspend fun sendStyle(ctx: Context, route: Int, arrow: Int, trail: Int) {
        val req = PutDataMapRequest.create(PATH_STYLE).apply {
            dataMap.putInt("route", route)
            dataMap.putInt("arrow", arrow)
            dataMap.putInt("trail", trail)
            dataMap.putLong("stamp", System.currentTimeMillis())
        }.asPutDataRequest().setUrgent()
        Wearable.getDataClient(ctx).putDataItem(req).await()
    }

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
