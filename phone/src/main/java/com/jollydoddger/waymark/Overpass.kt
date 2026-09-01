package com.jollydoddger.waymark

import android.util.JsonReader
import android.util.JsonToken

/**
 * Overpass replies, walked through rather than built up.
 *
 * This exists because of a crash: `OutOfMemoryError … at
 * java.lang.Double.valueOf`, on a phone whose heap tops out at 256 MB.
 * Every `out geom` query in this app used to be read with
 * `JSONObject(Net.overpass(query))`, which holds the reply four times over
 * before any of it is used — as the raw bytes, as a String, as a tree of
 * `JSONObject`s, and finally as the small thing that was actually wanted.
 * The tree is the expensive one: a coordinate is twenty-four bytes of text
 * and roughly two hundred bytes of object — a `JSONObject`, its map, two
 * entries, and two **boxed Doubles**, which is the exact frame the crash
 * died in.
 *
 * Read as a stream, the same reply costs the coordinates themselves and
 * nothing else. That is the difference between a walkable path network on
 * a dense area and a dead process.
 *
 * Deliberately *not* general: it reads the four things this app has ever
 * wanted from Overpass — an element's type, its tags, its geometry, and a
 * relation's members' geometry — and skips everything else without
 * materialising it.
 */
object Overpass {

    /** A geometry with no points. Shared; never written to. */
    private val EMPTY = DoubleArray(0)

    /**
     * One element, carrying only what this app reads.
     *
     * [geometry] is flat `lat, lon, lat, lon…` rather than a list of
     * points on purpose — a `List<En>` of a hundred thousand coordinates is
     * a hundred thousand objects, which is most of the way back to the
     * problem this class was written to solve. Callers project it as they
     * consume it.
     */
    class Element(
        val type: String,
        val tags: Map<String, String>,
        val geometry: DoubleArray,
        /** Each member way's own run, for a relation queried with `out geom`. */
        val members: List<DoubleArray>,
    )

    /**
     * A gap in a geometry, as a coordinate pair.
     *
     * Overpass replaces a node clipped out by `out geom(bbox)` with a
     * literal `null`, and that null is information: the way genuinely
     * leaves the box there and comes back somewhere else. Dropping the
     * nulls and carrying on — which the tree-parsing code did in two of the
     * three places it appeared — welds the two halves together with a
     * straight line across whatever the clip removed, and the router then
     * happily plans along an edge that is not a path at all.
     */
    fun isBreak(lat: Double, lon: Double): Boolean = lat.isNaN() || lon.isNaN()

    /**
     * A flat geometry split at its gaps, as flat runs. Pure, so the one
     * piece of this file that carries a decision can be tested.
     */
    fun runs(geometry: DoubleArray, minPoints: Int = 2): List<DoubleArray> {
        val out = ArrayList<DoubleArray>()
        var start = 0
        var i = 0
        fun flush(endExclusive: Int) {
            if ((endExclusive - start) / 2 >= minPoints) {
                out.add(geometry.copyOfRange(start, endExclusive))
            }
        }
        while (i + 1 < geometry.size) {
            if (isBreak(geometry[i], geometry[i + 1])) {
                flush(i)
                start = i + 2
            }
            i += 2
        }
        flush(geometry.size - (geometry.size % 2))
        return out
    }

    /**
     * Run [query] and hand each element to [onElement] as it arrives.
     *
     * The [Element] is not retained after the call returns, so a caller
     * that wants one must copy what it needs out of it — which every
     * caller does anyway, since none of them want Overpass's shape.
     */
    fun forEach(query: String, timeoutMs: Int = 70_000, onElement: (Element) -> Unit) {
        Net.overpassStream(query, timeoutMs) { input ->
            JsonReader(input.reader().buffered()).use { r ->
                r.isLenient = true
                r.beginObject()
                while (r.hasNext()) {
                    if (r.nextName() == "elements") {
                        r.beginArray()
                        while (r.hasNext()) onElement(readElement(r))
                        r.endArray()
                    } else {
                        r.skipValue()
                    }
                }
                r.endObject()
            }
        }
    }

    private fun readElement(r: JsonReader): Element {
        var type = ""
        var tags: Map<String, String> = emptyMap()
        var geometry = EMPTY
        var members: List<DoubleArray> = emptyList()
        r.beginObject()
        while (r.hasNext()) {
            when (r.nextName()) {
                "type" -> type = r.nextString()
                "tags" -> tags = readTags(r)
                "geometry" -> geometry = readGeometry(r)
                "members" -> members = readMembers(r)
                else -> r.skipValue()
            }
        }
        r.endObject()
        return Element(type, tags, geometry, members)
    }

    private fun readTags(r: JsonReader): Map<String, String> {
        val out = HashMap<String, String>(8)
        r.beginObject()
        while (r.hasNext()) {
            val key = r.nextName()
            // OSM tag values are strings, but a mapper who typed a bare
            // number should not take the whole reply down with them.
            when (r.peek()) {
                JsonToken.STRING, JsonToken.NUMBER -> out[key] = r.nextString()
                else -> r.skipValue()
            }
        }
        r.endObject()
        return out
    }

    private fun readGeometry(r: JsonReader): DoubleArray {
        var buf = DoubleArray(64)
        var n = 0
        fun put(a: Double, b: Double) {
            if (n + 2 > buf.size) buf = buf.copyOf(buf.size * 2)
            buf[n++] = a
            buf[n++] = b
        }
        r.beginArray()
        while (r.hasNext()) {
            if (r.peek() == JsonToken.NULL) {
                r.skipValue()
                put(Double.NaN, Double.NaN)
                continue
            }
            var lat = Double.NaN
            var lon = Double.NaN
            r.beginObject()
            while (r.hasNext()) {
                when (r.nextName()) {
                    "lat" -> lat = r.nextDouble()
                    "lon" -> lon = r.nextDouble()
                    else -> r.skipValue()
                }
            }
            r.endObject()
            put(lat, lon)
        }
        r.endArray()
        return if (n == buf.size) buf else buf.copyOf(n)
    }

    private fun readMembers(r: JsonReader): List<DoubleArray> {
        val out = ArrayList<DoubleArray>()
        r.beginArray()
        while (r.hasNext()) {
            var type = ""
            var geometry = EMPTY
            r.beginObject()
            while (r.hasNext()) {
                when (r.nextName()) {
                    "type" -> type = r.nextString()
                    "geometry" -> geometry = readGeometry(r)
                    else -> r.skipValue()
                }
            }
            r.endObject()
            if (type == "way" && geometry.isNotEmpty()) out.add(geometry)
        }
        r.endArray()
        return out
    }
}
