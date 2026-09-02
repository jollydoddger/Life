package com.jollydoddger.waymark

import com.google.android.gms.wearable.DataEvent
import com.google.android.gms.wearable.DataEventBuffer
import com.google.android.gms.wearable.DataMapItem
import com.google.android.gms.wearable.WearableListenerService
import com.jollydoddger.waymark.shared.Sync
import com.jollydoddger.waymark.shared.WeatherNote
import kotlinx.coroutines.runBlocking

/**
 * The watch's letterbox. Android starts this whenever the phone puts a
 * /waymark item on the Data Layer, app open or not — which is what makes
 * "import on the phone, it's simply there when the watch wakes" true.
 *
 * It is only half the story, though: a delivery missed while the watch is
 * asleep or out of range never comes again, so MainActivity also *pulls* the
 * current items when it opens. Both routes call the same appliers in Sync, so
 * they cannot drift apart.
 */
class WaymarkListenerService : WearableListenerService() {

    override fun onDataChanged(events: DataEventBuffer) {
        for (event in events) {
            if (event.type != DataEvent.TYPE_CHANGED) continue
            val item = event.dataItem
            val data = DataMapItem.fromDataItem(item).dataMap
            when (item.uri.path) {
                Sync.PATH_ROUTE -> runBlocking {
                    Sync.applyRouteItem(this@WaymarkListenerService, DataMapItem.fromDataItem(item))
                }
                Sync.PATH_KEY -> Sync.applyKey(this, data)
                Sync.PATH_STYLE -> Sync.applyStyle(this, data)
                Sync.PATH_RECORD -> Sync.applyRecordWish(this, data)
                Sync.PATH_POIS -> Sync.applyPois(this, data)
                // The phone's own notification is local-only, so this is
                // the buzz the wrist gets — and only for a headline, never
                // for a routine re-read that found the same sky.
                Sync.PATH_WEATHER -> Sync.applyWeather(this, data)?.let { (title, text) ->
                    WeatherNote.show(this, title, text, localOnly = false)
                }
            }
        }
    }
}
