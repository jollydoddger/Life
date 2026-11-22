package com.jollydoddger.photoframe

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList

data class Phone(val name: String, val ip: String)

data class FrameSettings(
    val mode: String = "slideshow",          // slideshow | wall | mixed
    val intervalSec: Int = 12,
    val transition: String = "kenburns",     // kenburns | fade | none
    val fit: String = "blur",                // blur | fit | fill
    val shuffle: Boolean = true,
    val showClock: Boolean = true,
    val includeVideos: Boolean = true,
    val videoMaxSec: Int = 60,
    val videoSound: Boolean = false,
    val albums: List<String> = emptyList(),  // empty = all albums
    val wallLayout: String = "mosaic",       // mosaic | grid | grid12
    val mixedMinutes: Int = 5,
    val scheduleEnabled: Boolean = false,
    val onTime: String = "07:00",
    val offTime: String = "23:00",
    val presenceEnabled: Boolean = false,
    val phones: List<Phone> = emptyList(),
    val awayGraceMin: Int = 20,
    val sleepAction: String = "black",       // black | sleep
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("mode", mode)
        put("intervalSec", intervalSec)
        put("transition", transition)
        put("fit", fit)
        put("shuffle", shuffle)
        put("showClock", showClock)
        put("includeVideos", includeVideos)
        put("videoMaxSec", videoMaxSec)
        put("videoSound", videoSound)
        put("albums", JSONArray(albums))
        put("wallLayout", wallLayout)
        put("mixedMinutes", mixedMinutes)
        put("scheduleEnabled", scheduleEnabled)
        put("onTime", onTime)
        put("offTime", offTime)
        put("presenceEnabled", presenceEnabled)
        put("phones", JSONArray().apply {
            phones.forEach { put(JSONObject().put("name", it.name).put("ip", it.ip)) }
        })
        put("awayGraceMin", awayGraceMin)
        put("sleepAction", sleepAction)
    }

    companion object {
        private fun oneOf(v: String, allowed: Set<String>, def: String) = if (v in allowed) v else def

        /** Builds settings from [o], keeping values from [base] for any missing keys. */
        fun fromJson(o: JSONObject, base: FrameSettings = FrameSettings()): FrameSettings {
            val albums = o.optJSONArray("albums")?.let { a -> List(a.length()) { a.optString(it) } } ?: base.albums
            val phones = o.optJSONArray("phones")?.let { a ->
                List(a.length()) { i ->
                    val p = a.optJSONObject(i) ?: JSONObject()
                    Phone(p.optString("name", "Phone").trim(), p.optString("ip", "").trim())
                }.filter { it.ip.isNotBlank() }
            } ?: base.phones
            return FrameSettings(
                mode = oneOf(o.optString("mode", base.mode), setOf("slideshow", "wall", "mixed"), base.mode),
                intervalSec = o.optInt("intervalSec", base.intervalSec).coerceIn(3, 3600),
                transition = oneOf(o.optString("transition", base.transition), setOf("kenburns", "fade", "none"), base.transition),
                fit = oneOf(o.optString("fit", base.fit), setOf("blur", "fit", "fill"), base.fit),
                shuffle = o.optBoolean("shuffle", base.shuffle),
                showClock = o.optBoolean("showClock", base.showClock),
                includeVideos = o.optBoolean("includeVideos", base.includeVideos),
                videoMaxSec = o.optInt("videoMaxSec", base.videoMaxSec).coerceIn(5, 3600),
                videoSound = o.optBoolean("videoSound", base.videoSound),
                albums = albums,
                wallLayout = oneOf(o.optString("wallLayout", base.wallLayout), setOf("mosaic", "grid", "grid12"), base.wallLayout),
                mixedMinutes = o.optInt("mixedMinutes", base.mixedMinutes).coerceIn(1, 240),
                scheduleEnabled = o.optBoolean("scheduleEnabled", base.scheduleEnabled),
                onTime = o.optString("onTime", base.onTime),
                offTime = o.optString("offTime", base.offTime),
                presenceEnabled = o.optBoolean("presenceEnabled", base.presenceEnabled),
                phones = phones,
                awayGraceMin = o.optInt("awayGraceMin", base.awayGraceMin).coerceIn(1, 720),
                sleepAction = oneOf(o.optString("sleepAction", base.sleepAction), setOf("black", "sleep"), base.sleepAction),
            )
        }
    }
}

object SettingsStore {
    @Volatile
    var current = FrameSettings()
        private set

    private var file: File? = null
    private val listeners = CopyOnWriteArrayList<(FrameSettings) -> Unit>()

    @Synchronized
    fun init(ctx: Context) {
        if (file != null) return
        val f = File(ctx.applicationContext.filesDir, "settings.json")
        file = f
        if (f.exists()) {
            runCatching { current = FrameSettings.fromJson(JSONObject(f.readText())) }
        }
    }

    @Synchronized
    fun update(patch: JSONObject) {
        current = FrameSettings.fromJson(patch, current)
        runCatching { file?.writeText(current.toJson().toString(2)) }
        val snapshot = current
        FrameState.post { listeners.forEach { it(snapshot) } }
    }

    fun addListener(l: (FrameSettings) -> Unit) { listeners.add(l) }
    fun removeListener(l: (FrameSettings) -> Unit) { listeners.remove(l) }
}
