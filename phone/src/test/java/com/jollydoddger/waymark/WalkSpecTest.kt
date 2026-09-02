package com.jollydoddger.waymark

import com.jollydoddger.waymark.shared.En
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * The specifier's judgement calls, at a desk rather than on a hillside.
 *
 * Two of them can be wrong quietly, which is why they are here: a walk's
 * *shape* is measured off its line and nothing else (plenty of routes named
 * "circular" are not), and an ask in hours becomes an ask in metres through
 * his own pace. Get either wrong and the picker fills with walks he did not
 * ask for, with nothing anywhere saying why.
 */
class WalkSpecTest {

    private fun ring(radiusM: Double, points: Int = 64): List<En> =
        (0..points).map { i ->
            val a = i * 2 * PI / points
            En(1000 + radiusM * sin(a), 1000 + radiusM * cos(a))
        }

    private fun line(lengthM: Double, points: Int = 20): List<En> =
        (0..points).map { i -> En(1000 + lengthM * i / points, 1000.0) }

    private fun outAndBack(lengthM: Double): List<En> {
        val one = line(lengthM / 2)
        return one + one.reversed().drop(1)
    }

    @Test
    fun `a circle is circular, a line is one way, and there-and-back is neither`() {
        assertEquals(Form.CIRCULAR, Specifier.formOf(ring(500.0)))
        assertEquals(Form.LINEAR, Specifier.formOf(line(4_000.0)))
        assertEquals(Form.OUT_AND_BACK, Specifier.formOf(outAndBack(6_000.0)))
    }

    @Test
    fun `a wandering there-and-back is still a there-and-back`() {
        // The test that killed the cheap version of this. A path that
        // wiggles reaches barely a third of its length from the start, so
        // "how far does it reach" filed it as a circuit — and the brief then
        // told him there was no turning point on a walk that is nothing but
        // a turning point.
        val out = (0..60).map { En(1000 + it * 50.0, 1000 + 120 * sin(it / 3.0)) }
        val there = out + out.reversed().drop(1)
        assertEquals(Form.OUT_AND_BACK, Specifier.formOf(there))
    }

    @Test
    fun `a long thin loop is still a loop`() {
        // Two lanes a couple of hundred metres apart, out along one and back
        // down the other: it reaches a long way from the start but it is a
        // circuit, and calling it a there-and-back would be wrong about the
        // one thing he asked about.
        val out = (0..40).map { En(1000 + it * 100.0, 1000.0) }
        val back = (40 downTo 0).map { En(1000 + it * 100.0, 1200.0) }
        val loop = out + back + listOf(out.first())
        assertEquals(Form.CIRCULAR, Specifier.formOf(loop))
    }

    @Test
    fun `hours become metres through his pace, not a book's`() {
        val spec = WalkSpec(byTime = true, from = 2.0, to = 3.0)
        // A brisk 10 min/km walker covers more in two hours than a 15 does.
        val (fastMin, fastMax) = spec.rangeMetres(10.0)
        val (slowMin, slowMax) = spec.rangeMetres(15.0)
        assertEquals(12_000.0, fastMin, 1.0)
        assertEquals(18_000.0, fastMax, 1.0)
        assertTrue("a slower walker is asking for a shorter route", slowMax < fastMax)
        assertEquals(8_000.0, slowMin, 1.0)
    }

    @Test
    fun `miles are converted, and nothing downstream ever sees one`() {
        // "Don't get miles and km mixed up, allow for both and always
        // convert to km." One conversion, in one place.
        val (lo, hi) = WalkSpec(from = 5.0, to = 15.0, miles = true).rangeMetres(12.0)
        assertEquals(8_046.7, lo, 1.0)
        assertEquals(24_140.2, hi, 1.0)
        val (kmLo, kmHi) = WalkSpec(from = 5.0, to = 15.0, miles = false).rangeMetres(12.0)
        assertEquals(5_000.0, kmLo, 0.1)
        assertEquals(15_000.0, kmHi, 0.1)
        assertTrue("a mile is longer than a kilometre", lo > kmLo && hi > kmHi)
    }

    @Test
    fun `the unit is named in the label, so the form cannot lie about it`() {
        assertTrue("miles" in WalkSpec(from = 5.0, to = 15.0, miles = true).label())
        assertTrue("km" in WalkSpec(from = 5.0, to = 15.0, miles = false).label())
    }

    @Test
    fun `an hours ask ignores the mile flag entirely`() {
        val a = WalkSpec(byTime = true, from = 2.0, to = 3.0, miles = true).rangeMetres(12.0)
        val b = WalkSpec(byTime = true, from = 2.0, to = 3.0, miles = false).rangeMetres(12.0)
        assertEquals(a.first, b.first, 0.1)
        assertEquals(a.second, b.second, 0.1)
    }

    @Test
    fun `kilometres are taken at face value and the ends may arrive swapped`() {
        val (min, max) = WalkSpec(from = 9.0, to = 4.0).rangeMetres(12.0)
        assertEquals(4_000.0, min, 0.1)
        assertEquals(9_000.0, max, 0.1)
    }

    private fun walk(name: String, pts: List<En>, closest: Double = 0.0) =
        RouteFinder.FoundWalk(name, "OSM", listOf(pts), closest, Geom.length(pts))

    @Test
    fun `a circular ask never comes back with a one-way path`() {
        val found = listOf(
            walk("a loop", ring(800.0)),
            walk("a coast path", line(5_000.0)),
        )
        val out = Specifier.shortlist(found, WalkSpec(shape = Shape.CIRCULAR), 0.0, 50_000.0)
        assertEquals(1, out.size)
        assertEquals("a loop", out.first().name)
    }

    @Test
    fun `a one-way path answers a there-and-back ask at double its length`() {
        // The whole point: nobody publishes out-and-backs, because half of
        // one is already published. A 4 km path is an 8 km answer.
        val found = listOf(walk("a coast path", line(4_000.0)))
        val out = Specifier.shortlist(
            found, WalkSpec(shape = Shape.OUT_AND_BACK), 7_000.0, 9_000.0,
        )
        assertEquals(1, out.size)
        assertEquals(8_000.0, out.first().lengthM, 50.0)
        assertTrue("and it says so in the name", out.first().name.endsWith("there and back"))
        assertEquals(Form.OUT_AND_BACK, Specifier.formOf(out.first()))
        // Its GPX describes one leg; re-parsing it on adoption would hand
        // back half the walk, so the link is deliberately dropped.
        assertEquals(null, out.first().uri)
    }

    @Test
    fun `the doubled walk is only offered when its doubled length fits`() {
        val found = listOf(walk("a coast path", line(4_000.0)))
        val out = Specifier.shortlist(
            found, WalkSpec(shape = Shape.OUT_AND_BACK), 3_000.0, 5_000.0,
        )
        assertTrue("4 km doubled is 8 km, which is not 3 to 5", out.isEmpty())
    }

    @Test
    fun `don't-mind takes any shape, in range`() {
        val found = listOf(
            walk("a loop", ring(800.0)),
            walk("a coast path", line(5_000.0)),
            walk("a marathon", line(42_000.0)),
        )
        val out = Specifier.shortlist(found, WalkSpec(shape = Shape.ANY), 1_000.0, 20_000.0)
        assertEquals(2, out.size)
    }

    @Test
    fun `the form survives a round trip through storage`() {
        val spec = WalkSpec(
            Shape.OUT_AND_BACK, byTime = true, from = 1.5, to = 3.0,
            dayOffset = 4, origin = Origin.SCREEN,
        )
        assertEquals(spec, WalkSpec.fromJson(spec.toJson()))
        // And nonsense in the file is a fresh form, never a crash on the way
        // to a walk.
        assertEquals(WalkSpec(), WalkSpec.fromJson("not json"))
        assertEquals(WalkSpec(), WalkSpec.fromJson(null))
    }

    @Test
    fun `nothing matching still offers what is really there, labelled`() {
        // Snowdonia's route relations are national trails: forty kilometres
        // and straight, so a "circular, 5-10 km" ask matches none of them
        // and the picker came back empty — which reads as "there is nothing
        // here", in one of the busiest walking areas in Britain.
        val found = listOf(
            walk("Snowdonia Slate Trail", line(43_000.0), closest = 900.0),
            walk("Cambrian Way", line(38_000.0), closest = 2_400.0),
        )
        assertTrue(
            "none of them match the ask, which is why this exists",
            Specifier.shortlist(found, WalkSpec(shape = Shape.CIRCULAR), 5_000.0, 10_000.0).isEmpty(),
        )
        val out = Specifier.nearMisses(found)
        assertEquals(2, out.size)
        assertEquals("nearest first", "Snowdonia Slate Trail (one way)", out.first().name)
        // The length is left alone: dressing 43 km up as an 8 km walk would
        // be inventing a route nobody surveyed.
        assertEquals(43_000.0, out.first().lengthM, 100.0)
    }

    @Test
    fun `how many to plan is remembered, bounded, and defaults for old forms`() {
        val spec = WalkSpec(planned = 5)
        assertEquals(5, WalkSpec.fromJson(spec.toJson()).planned)
        // Zero is a real answer — the planner off — and must survive.
        assertEquals(0, WalkSpec.fromJson(WalkSpec(planned = 0).toJson()).planned)
        // A form saved before the count existed asked for the default.
        assertEquals(WalkSpec.DEFAULT_PLANNED, WalkSpec.fromJson("""{"shape":"ANY"}""").planned)
        // And a hand-edited file cannot ask for fifty.
        assertEquals(WalkSpec.MAX_PLANNED, WalkSpec.fromJson("""{"planned":50}""").planned)
        assertEquals(0, WalkSpec.fromJson("""{"planned":-3}""").planned)
    }

    @Test
    fun `where it starts from is remembered too`() {
        // The field the first version of the form forgot. A spec written
        // before it existed must still load, as "where I am".
        val old = """{"shape":"CIRCULAR","byTime":false,"from":5,"to":10,"dayOffset":0}"""
        assertEquals(Origin.HERE, WalkSpec.fromJson(old).origin)
        assertEquals(
            Origin.TAP,
            WalkSpec.fromJson(WalkSpec(origin = Origin.TAP).toJson()).origin,
        )
    }
}
