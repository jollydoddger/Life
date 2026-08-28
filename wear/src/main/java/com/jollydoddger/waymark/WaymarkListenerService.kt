package com.jollydoddger.waymark

import com.google.android.gms.wearable.DataEvent
import com.google.android.gms.wearable.DataEventBuffer
import com.google.android.gms.wearable.DataMapItem
import com.google.android.gms.wearable.WearableListenerService
import com.jollydoddger.waymark.shared.Prefs.osApiKey
import com.jollydoddger.waymark.shared.Sync
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
            }
        }
    }
}
