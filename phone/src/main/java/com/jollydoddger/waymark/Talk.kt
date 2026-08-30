package com.jollydoddger.waymark

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * The conversation, kept.
 *
 * Until now a reply lived in one panel over the map and was gone the moment
 * the next one arrived — there was no way to look back at what he had asked
 * ten minutes ago, or at what the assistant said it had done. A map app that
 * can be talked to needs the talking to be readable, the way loose-ends'
 * chat is.
 *
 * Plain JSON, like everything else here that is not a screen recording. It is
 * a working surface rather than an archive, so it is capped and the oldest
 * fall off the end.
 */
class Said(
    val fromHim: Boolean,
    val text: String,
    /** What the assistant's tools actually changed — kept apart from the
     *  words, because a claim in prose and a receipt from a tool are not the
     *  same kind of thing. */
    val actions: List<String> = emptyList(),
    val atMs: Long = System.currentTimeMillis(),
)

object Talk {

    private const val MAX = 200

    private fun file(c: Context) = File(c.filesDir, "talk.json")

    fun load(c: Context): List<Said> {
        val f = file(c)
        if (!f.exists()) return emptyList()
        return try {
            val arr = JSONArray(f.readText())
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                val acts = o.optJSONArray("actions")
                Said(
                    fromHim = o.optBoolean("him"),
                    text = o.optString("text"),
                    actions = if (acts == null) emptyList() else {
                        (0 until acts.length()).map { acts.optString(it) }
                    },
                    atMs = o.optLong("at"),
                )
            }
        } catch (e: Exception) {
            // A conversation is not worth crashing a map over.
            emptyList()
        }
    }

    fun add(c: Context, said: Said) {
        val all = (load(c) + said).takeLast(MAX)
        save(c, all)
    }

    fun clear(c: Context) = save(c, emptyList())

    private fun save(c: Context, all: List<Said>) {
        val arr = JSONArray()
        for (s in all) {
            arr.put(
                JSONObject().apply {
                    put("him", s.fromHim)
                    put("text", s.text)
                    put("at", s.atMs)
                    if (s.actions.isNotEmpty()) put("actions", JSONArray(s.actions))
                },
            )
        }
        // Written to one side and moved into place, so a kill mid-write
        // leaves yesterday's conversation rather than half of one.
        val f = file(c)
        val tmp = File(f.parentFile, "talk.json.tmp")
        tmp.writeText(arr.toString())
        tmp.renameTo(f)
    }
}
