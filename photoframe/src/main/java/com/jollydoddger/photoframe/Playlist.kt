package com.jollydoddger.photoframe

/** Endless queue with history (for "previous"). Newly uploaded items jump to the front so you see them straight away. */
class Playlist(items: List<MediaItem>, private val shuffle: Boolean) {
    companion object {
        /** More "new" items than this at once is a library appearing, not an upload. */
        const val FRESH_LIMIT = 50
    }

    private var all: List<MediaItem> = sorted(items)
    private val queue = ArrayDeque<MediaItem>()
    private val history = ArrayList<MediaItem>()
    private var pos = -1

    val isEmpty get() = all.isEmpty()

    private fun sorted(items: List<MediaItem>) =
        items.sortedWith(compareBy<MediaItem>({ it.album.lowercase() }, { it.mtime }, { it.file.name }))

    fun next(): MediaItem? {
        if (pos < history.size - 1) {
            pos++
            return history[pos]
        }
        if (all.isEmpty()) return null
        if (queue.isEmpty()) refill()
        val item = queue.removeFirst()
        history.add(item)
        if (history.size > 300) history.removeAt(0)
        pos = history.size - 1
        return item
    }

    fun prev(): MediaItem? {
        if (pos > 0) {
            pos--
            return history[pos]
        }
        return history.getOrNull(pos)
    }

    private fun refill() {
        queue.addAll(if (shuffle) all.shuffled() else all)
        // Avoid showing the same picture twice in a row across a reshuffle
        if (shuffle && queue.size > 1 && queue.first() == history.lastOrNull()) queue.addLast(queue.removeFirst())
    }

    fun update(items: List<MediaItem>) {
        val known = all.mapTo(HashSet()) { it.file.path }
        val alive = items.mapTo(HashSet()) { it.file.path }
        val fresh = items.filter { it.file.path !in known }.sortedBy { it.mtime }
        all = sorted(items)
        val kept = queue.filter { it.file.path in alive }
        queue.clear()
        if (fresh.size <= FRESH_LIMIT) {
            queue.addAll(fresh)
            queue.addAll(kept)
        } else {
            // Not an upload but a whole library appearing (the USB stick mounted late, or the
            // storage moved): front-loading it would play everything in date order.
            queue.addAll(kept)
            queue.addAll(if (shuffle) fresh.shuffled() else fresh)
        }
        history.removeAll { it.file.path !in alive }
        pos = history.size - 1
    }
}
