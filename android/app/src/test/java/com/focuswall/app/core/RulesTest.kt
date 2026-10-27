package com.focuswall.app.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneOffset

class RulesTest {
    private val c = Defaults.config()
    private fun t(s: String) = LocalDateTime.parse(s)
    private fun ms(t: LocalDateTime) = t.toInstant(ZoneOffset.UTC).toEpochMilli()

    @Test
    fun nightScheduleCrossesMidnight() {
        val s = c.schedules.first()
        assertFalse(Rules.scheduleActive(s, t("2026-10-27T20:59")))
        assertTrue(Rules.scheduleActive(s, t("2026-10-27T21:00")))
        assertTrue(Rules.scheduleActive(s, t("2026-10-28T04:59")))
        assertFalse(Rules.scheduleActive(s, t("2026-10-28T05:00")))
    }

    @Test
    fun nightAllowsOnlyDialerAndSber() {
        val now = t("2026-10-27T23:00")
        val p = Rules.appPolicy(c, null, now, ms(now))
        assertEquals(PolicyMode.ALLOW, p.mode)
        assertFalse(Rules.isAppBlocked("ru.sberbankmobile", p))
        assertFalse(Rules.isAppBlocked("com.google.android.dialer", p))
        assertTrue(Rules.isAppBlocked("com.google.android.youtube", p))
    }

    @Test
    fun dayIsFreeUnlessSession() {
        val now = t("2026-10-27T12:00")
        assertEquals(PolicyMode.OFF, Rules.appPolicy(c, null, now, ms(now)).mode)
        val session = Session("1", listOf("social"), ms(now), ms(now) + 60_000, false, "phone")
        val sites = Rules.sitePolicy(c, session, now, ms(now))
        assertTrue(Rules.isHostBlocked("m.vk.com", sites))
        assertFalse(Rules.isHostBlocked("wikipedia.org", sites))
    }

    @Test
    fun hardScheduleCannotBeLoosenedAtNight() {
        val night = t("2026-10-27T23:00")
        val disabled = c.copy(schedules = c.schedules.map { it.copy(enabled = false) })
        assertNotNull(Rules.hardModeViolation(c, disabled, null, night, ms(night)))
        val day = t("2026-10-27T12:00")
        assertNull(Rules.hardModeViolation(c, disabled, null, day, ms(day)))
    }

    @Test
    fun jsonRoundTrip() {
        val back = Json.config(Json.toJson(c))
        assertEquals(c, back)
    }

    @Test
    fun blockedUntilFiveAm() {
        val until = Rules.blockedUntil(c, null, t("2026-10-27T23:00"), ZoneOffset.UTC)!!
        assertEquals(ms(t("2026-10-28T05:00")), until)
    }
}
