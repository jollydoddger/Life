package com.jollydoddger.waymark

import com.google.android.gms.wearable.DataEvent
import com.google.android.gms.wearable.DataEventBuffer
import com.google.android.gms.wearable.DataMapItem
import com.google.android.gms.wearable.WearableListenerService
import com.jollydoddger.waymark.shared.Prefs.arrowColour
import com.jollydoddger.waymark.shared.Prefs.osApiKey
import com.jollydoddger.waymark.shared.Prefs.recording
import com.jollydoddger.waymark.shared.Prefs.routeColour
import com.jollydoddger.waymark.shared.Prefs.trailColour
import com.jollydoddger.waymark.shared.Prefs.wantRecording
import com.jollydoddger.waymark.shared.Sync
import com.jollydoddger.waymark.shared.TrackingService
import kotlinx.coroutines.runBlocking

/**
 * The watch's letterbox. Android starts this whenever the phone puts a
 * /waymark item on the Data Layer, app open or not — which is what makes
 * "import on the phone, it's simply there when the watch wakes" true.
 */
class WaymarkListenerService : WearableListenerService() {

    override fun onDataChanged(events: DataEventBuffer) {
        for (event in events) {
            if (event.type != DataEvent.TYPE_CHANGED) continue
            val item = event.dataItem
            when (item.uri.path) {
                Sync.PATH_ROUTE -> runBlocking {
                    Sync.applyRouteItem(this@WaymarkListenerService, DataMapItem.fromDataItem(item))
                }
                Sync.PATH_KEY -> {
                    val key = DataMapItem.fromDataItem(item).dataMap.getString("key")
                    if (!key.isNullOrEmpty()) osApiKey = key
                }
                Sync.PATH_STYLE -> {
                    val d = DataMapItem.fromDataItem(item).dataMap
                    routeColour = d.getInt("route")
                    arrowColour = d.getInt("arrow")
                    trailColour = d.getInt("trail")
                }
                Sync.PATH_RECORD -> {
                    // Recording is only ever *started* from the open app: a
                    // foreground service cannot be launched from the
                    // background on modern Android, and a watch that quietly
                    // began recording while nobody looked would be worse than
                    // one that waits. Stopping from here is always allowed,
                    // and is the half that matters — a Stop on the phone must
                    // never leave the watch recording on his wrist.
                    val on = DataMapItem.fromDataItem(item).dataMap.getBoolean("on")
                    wantRecording = on
                    if (!on && recording) TrackingService.stop(this@WaymarkListenerService)
                }
            }
        }
    }
}
