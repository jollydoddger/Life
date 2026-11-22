package com.jollydoddger.photoframe

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test

class SettingsTest {
    @Test fun roundTrip() {
        val s = FrameSettings(mode = "wall", phones = listOf(Phone("John", "192.168.1.50")), albums = listOf("Holidays"))
        assertEquals(s, FrameSettings.fromJson(s.toJson()))
    }

    @Test fun patchKeepsTheRest() {
        val base = FrameSettings(intervalSec = 30, phones = listOf(Phone("A", "10.0.0.2")))
        val s = FrameSettings.fromJson(JSONObject("""{"mode":"mixed"}"""), base)
        assertEquals("mixed", s.mode)
        assertEquals(30, s.intervalSec)
        assertEquals(base.phones, s.phones)
    }

    @Test fun junkIsClampedOrIgnored() {
        val s = FrameSettings.fromJson(JSONObject("""{"intervalSec":0,"mode":"disco","awayGraceMin":99999,
            "phones":[{"name":" P ","ip":" 10.0.0.9 "},{"name":"no ip"}]}"""))
        assertEquals(3, s.intervalSec)
        assertEquals("slideshow", s.mode)
        assertEquals(720, s.awayGraceMin)
        assertEquals(listOf(Phone("P", "10.0.0.9")), s.phones)
    }
}
