package com.jollydoddger.photoframe

import android.os.Handler
import android.os.Looper
import java.util.concurrent.CopyOnWriteArrayList

/** Shared state between the service (brain) and the activity (screen). Callbacks run on the main thread. */
object FrameState {
    interface Listener {
        fun onAwakeChanged(awake: Boolean) {}
        fun onLibraryChanged() {}
        fun onCommand(cmd: String) {}
    }

    private val main = Handler(Looper.getMainLooper())
    private val listeners = CopyOnWriteArrayList<Listener>()

    @Volatile var awake = true
        private set
    @Volatile var sleepReason = ""
        private set
    @Volatile var lastUserActivity = System.currentTimeMillis()
        private set
    @Volatile var activityVisible = false
    /** phone ip -> last time it answered (ms) */
    @Volatile var presence: Map<String, Long> = emptyMap()

    fun post(r: () -> Unit) { main.post(r) }

    fun add(l: Listener) { listeners.addIfAbsent(l) }
    fun remove(l: Listener) { listeners.remove(l) }

    fun touch() { lastUserActivity = System.currentTimeMillis() }

    fun setAwake(value: Boolean, reason: String) {
        sleepReason = if (value) "" else reason
        if (awake == value) return
        awake = value
        post { listeners.forEach { it.onAwakeChanged(value) } }
    }

    fun libraryChanged() = post { listeners.forEach { it.onLibraryChanged() } }

    fun command(cmd: String) = post { listeners.forEach { it.onCommand(cmd) } }
}
