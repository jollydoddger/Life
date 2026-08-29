package com.jollydoddger.waymark.shared

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Points the assistant found — toilets, cafés, bins — dropped onto the map as
 * markers on both devices. One current set, replaced by each new search and
 * clearable with a tap: search results are an answer, not an archive.
 */
data class Poi(val name: String, val kind: String, val at: En)

object Pois {
    /** kind → the glyph its map disc wears. */
    fun glyph(kind: String): String = when (kind) {
        "toilets" -> "🚻"
        "cafe" -> "☕"
        "pub" -> "🍺"
        "bin" -> "🗑"
        "water" -> "💧"
        "parking" -> "🅿"
        "defibrillator" -> "⚡"
        "bus_stop" -> "🚌"
        "bench" -> "🪑"
        else -> "📍"
    }

    fun toJson(pois: List<Poi>): String {
        val arr = JSONArray()
        pois.forEach {
            arr.put(JSONObject().put("name", it.name).put("kind", it.kind).put("e", it.at.e).put("n", it.at.n))
        }
        return arr.toString()
    }

    fun fromJson(json: String): List<Poi> = try {
        val arr = JSONArray(json)
        (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            Poi(o.optString("name", ""), o.optString("kind", ""), En(o.getDouble("e"), o.getDouble("n")))
        }
    } catch (e: Exception) {
        emptyList()
    }
}

object PoiStore {
    private fun file(ctx: Context) = File(ctx.filesDir, "pois.json")

    fun save(ctx: Context, pois: List<Poi>) {
        val tmp = File(ctx.filesDir, "pois.json.tmp")
        tmp.writeText(Pois.toJson(pois))
        if (!tmp.renameTo(file(ctx))) {
            file(ctx).delete()
            tmp.renameTo(file(ctx))
        }
    }

    fun load(ctx: Context): List<Poi> {
        val f = file(ctx)
        if (!f.exists()) return emptyList()
        return try { Pois.fromJson(f.readText()) } catch (e: Exception) { emptyList() }
    }
}
