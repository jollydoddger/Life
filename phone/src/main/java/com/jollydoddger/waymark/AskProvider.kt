package com.jollydoddger.waymark

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.location.LocationManager
import android.net.Uri
import android.os.Binder
import android.os.Bundle
import android.util.Log
import com.jollydoddger.waymark.shared.Bng
import com.jollydoddger.waymark.shared.En
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

/**
 * The way in from Loose Ends: her question, answered by *this* app's
 * assistant, without either of them leaving the screen he is on.
 *
 * *"I'd love to be able to have the same assistant be in Waymark as here, and
 * I can say to her from anywhere: top ten walks in this area."* The knowledge
 * that answers that question is all here — the walking sites, the index, the
 * path-network planner, the OS map — and none of it is in Loose Ends. Copying
 * it across would be two divergent copies of a walk finder within the month.
 * Teaching Loose Ends to *ask* costs a provider.
 *
 * So: she keeps one voice and one memory over there, and when the question is
 * about walking she hands it here, gets a real answer back with real walks in
 * it, and reads it out. From the side button, on any screen, with Waymark
 * never opened.
 *
 * **Asynchronous on purpose.** Finding a walk is a web search, two page
 * fetches and a route plan — a minute and a half at the far end. Answering
 * that inside one `call()` would hold a Binder thread for the duration and
 * give the far side no way to say what it was doing. [ask] takes the question
 * and hands back a ticket; [POLL] returns the working line while it runs and
 * the answer when it lands. The far side polls, so the strip on his phone can
 * say "searching walking routes…" in Waymark's own words.
 *
 * **Who may ask: Loose Ends, and only Loose Ends.** A provider can resolve the
 * calling UID; a broadcast cannot. This one runs an assistant with his API key
 * and his location, which is a thing to hand out to exactly one caller.
 */
class AskProvider : ContentProvider() {

    /** One question in flight, from ticket to answer. */
    private class Job {
        @Volatile var doing: String = "thinking…"
        @Volatile var text: String? = null
        @Volatile var cancelled = false
        val at = System.currentTimeMillis()
    }

    override fun onCreate(): Boolean = true

    override fun call(method: String, arg: String?, extras: Bundle?): Bundle? {
        if (!callerIsLooseEnds()) return null
        val context = context ?: return null
        return when (method) {
            ASK -> start(context, arg.orEmpty(), extras)
            POLL -> poll(arg.orEmpty())
            CANCEL -> {
                jobs[arg.orEmpty()]?.cancelled = true
                Bundle()
            }
            else -> null
        }
    }

    private fun start(context: Context, question: String, extras: Bundle?): Bundle {
        val out = Bundle()
        if (question.isBlank()) {
            out.putString(KEY_TEXT, "No question came through.")
            out.putBoolean(KEY_DONE, true)
            return out
        }
        sweep()

        val ticket = UUID.randomUUID().toString()
        val job = Job()
        jobs[ticket] = job

        // Where he is, if either side knows. Loose Ends may pass a fix it
        // already holds — it watches location for errands — which saves
        // waking the GPS for a question asked from an armchair. Failing
        // that, whatever the system last recorded. Never a new fix: a
        // question is not a reason to start a GPS session, and the tools
        // that need one say so honestly when there is none.
        val given = extras?.takeIf { it.containsKey(KEY_LAT) && it.containsKey(KEY_LON) }
            ?.let { Bng.fromWgs84(it.getDouble(KEY_LAT), it.getDouble(KEY_LON)) }
        val fix = given ?: lastKnown(context)

        worker.execute {
            val reply = runCatching {
                val tools = GeoTools(
                    context,
                    { fix },
                    // Honest rather than flattering: a fix from the system's
                    // cache is of unknown age, and `where_am_i` is built to
                    // admit that. Saying zero would make a stale fix read as
                    // this second's.
                    { if (given != null) 0L else Long.MAX_VALUE },
                    { note -> job.doing = note },
                    { job.cancelled },
                )
                val assistant = Assistant(context, tools)
                assistant.onActivity = { note -> job.doing = note }
                assistant.ask(question) { job.cancelled }.text
            }.getOrElse { e ->
                Log.w(TAG, "asked and could not answer", e)
                "Waymark could not answer that one: ${e.message ?: e::class.java.simpleName}."
            }
            job.text = reply
        }

        out.putString(KEY_TICKET, ticket)
        out.putBoolean(KEY_DONE, false)
        return out
    }

    private fun poll(ticket: String): Bundle {
        val out = Bundle()
        val job = jobs[ticket]
        if (job == null) {
            // A ticket this process has never heard of, which after a restart
            // is every ticket. Said as an outcome rather than left as a poll
            // that never finishes.
            out.putBoolean(KEY_DONE, true)
            out.putString(KEY_TEXT, "Waymark restarted before it finished that one.")
            return out
        }
        val text = job.text
        out.putBoolean(KEY_DONE, text != null)
        out.putString(KEY_DOING, job.doing)
        if (text != null) {
            out.putString(KEY_TEXT, text)
            jobs.remove(ticket)
        }
        return out
    }

    /** The system's last recorded fix, from any provider, without asking for a new one. */
    private fun lastKnown(context: Context): En? = runCatching {
        val manager = context.getSystemService(LocationManager::class.java) ?: return null
        manager.getProviders(true)
            .mapNotNull { runCatching { manager.getLastKnownLocation(it) }.getOrNull() }
            .maxByOrNull { it.time }
            ?.let { Bng.fromWgs84(it.latitude, it.longitude) }
    }.getOrNull()

    /** Answers nobody came back for. Bounded so a dropped poll is not a leak. */
    private fun sweep() {
        val cutoff = System.currentTimeMillis() - TTL_MS
        jobs.entries.removeAll { it.value.at < cutoff }
    }

    private fun callerIsLooseEnds(): Boolean {
        val context = context ?: return false
        val packages = runCatching {
            context.packageManager.getPackagesForUid(Binder.getCallingUid())
        }.getOrNull() ?: return false
        return packages.any { it == CALLER }
    }

    // Read-only, and not even that: everything comes through call().
    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?,
    ): Cursor? = null

    override fun getType(uri: Uri): String? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0
    override fun update(
        uri: Uri,
        values: ContentValues?,
        selection: String?,
        selectionArgs: Array<out String>?,
    ): Int = 0

    companion object {
        private const val TAG = "AskProvider"

        const val AUTHORITY = "com.jollydoddger.waymark.ask"
        const val CALLER = "com.jollydoddger.looseends"

        const val ASK = "ask"
        const val POLL = "poll"
        const val CANCEL = "cancel"

        const val KEY_TICKET = "ticket"
        const val KEY_DONE = "done"
        const val KEY_TEXT = "text"
        const val KEY_DOING = "doing"
        const val KEY_LAT = "lat"
        const val KEY_LON = "lon"

        /** An answer nobody collected inside ten minutes was not wanted. */
        private const val TTL_MS = 10 * 60_000L

        /**
         * One at a time. Two route plans at once would fight over the same
         * key, the same rate limits and the same path-network server, and
         * nobody asks two walking questions in the same breath.
         */
        private val worker = Executors.newSingleThreadExecutor { r ->
            Thread(r, "waymark-ask").apply { isDaemon = true }
        }

        private val jobs = ConcurrentHashMap<String, Job>()
    }
}
