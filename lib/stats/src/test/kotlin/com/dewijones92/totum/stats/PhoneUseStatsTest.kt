package com.dewijones92.totum.stats

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.ZoneId

class PhoneUseStatsTest {

    private val london = ZoneId.of("Europe/London")
    private val minute = 60_000L
    private val hour = 60 * minute

    private fun at(iso: String) = Instant.parse(iso).toEpochMilli()

    private fun unlock(iso: String, app: String? = null) = StatEvent.PhoneUnlock(at(iso), app)

    private fun screen(iso: String, on: Boolean) = StatEvent.PhoneScreen(at(iso), on)

    private fun raw(iso: String, kind: RawKind, pkg: String? = null) = RawUsage(at(iso), kind, pkg)

    @Test
    fun `an unlock carries the first app that came to the front after it, not the system UI`() {
        val settled = settle(
            listOf(
                raw("2026-11-13T09:00:00Z", RawKind.SCREEN_ON),
                raw("2026-11-13T09:00:01Z", RawKind.KEYGUARD_HIDDEN),
                raw("2026-11-13T09:00:01.200Z", RawKind.APP_FOREGROUND, "com.android.systemui"),
                raw("2026-11-13T09:00:01.500Z", RawKind.APP_FOREGROUND, "com.whatsapp"),
                raw("2026-11-13T09:00:03Z", RawKind.APP_FOREGROUND, "com.android.chrome"),
            ),
            from = at("2026-11-13T08:00:00Z"),
            now = at("2026-11-13T09:05:00Z"),
        )

        assertEquals(
            listOf(
                StatEvent.PhoneScreen(at("2026-11-13T09:00:00Z"), true),
                unlock("2026-11-13T09:00:01Z", "com.whatsapp")
            ),
            settled.events,
        )
    }

    @Test
    fun `an unlock still inside the first-app window is held back until its app could have been seen`() {
        val events = listOf(
            raw("2026-11-13T09:00:00Z", RawKind.KEYGUARD_HIDDEN),
            raw("2026-11-13T09:00:02Z", RawKind.APP_FOREGROUND, "com.whatsapp"),
        )
        val early = settle(events, from = at("2026-11-13T08:00:00Z"), now = at("2026-11-13T09:00:03Z"))
        assertEquals(emptyList<StatEvent>(), early.events)
        assertEquals(at("2026-11-13T09:00:03Z") - FIRST_APP_WINDOW_MS, early.settledUntil)

        val later = settle(events, from = early.settledUntil, now = at("2026-11-13T09:01:00Z"))
        assertEquals(listOf<StatEvent>(unlock("2026-11-13T09:00:00Z", "com.whatsapp")), later.events)
    }

    @Test
    fun `settling in two halves records every event exactly once`() {
        val events = listOf(
            raw("2026-11-13T09:00:00Z", RawKind.SCREEN_ON),
            raw("2026-11-13T09:00:01Z", RawKind.KEYGUARD_HIDDEN),
            raw("2026-11-13T09:10:00Z", RawKind.SCREEN_OFF),
            raw("2026-11-13T09:30:00Z", RawKind.SCREEN_ON),
            raw("2026-11-13T09:30:01Z", RawKind.KEYGUARD_HIDDEN),
        )
        val first = settle(events, from = at("2026-11-13T08:00:00Z"), now = at("2026-11-13T09:20:00Z"))
        val second = settle(events, from = first.settledUntil, now = at("2026-11-13T10:00:00Z"))

        assertEquals(3, first.events.size)
        assertEquals(2, second.events.size)
        assertEquals(5, (first.events + second.events).distinct().size)
    }

    @Test
    fun `nothing settles when the window has not moved`() {
        val settled = settle(
            listOf(raw("2026-11-13T09:00:00Z", RawKind.KEYGUARD_HIDDEN)),
            from = at("2026-11-13T09:00:00Z"),
            now = at("2026-11-13T09:00:01Z"),
        )
        assertEquals(emptyList<StatEvent>(), settled.events)
        assertEquals(at("2026-11-13T09:00:00Z"), settled.settledUntil)
    }

    @Test
    fun `screen sessions pair each on with the next off`() {
        val result = screenSessions(
            listOf(
                screen("2026-11-13T09:00:00Z", true),
                screen("2026-11-13T09:04:00Z", false),
                screen("2026-11-13T10:00:00Z", true),
                screen("2026-11-13T10:01:00Z", false),
            ),
            now = at("2026-11-13T11:00:00Z"),
            screenOnNow = false,
        )
        assertEquals(listOf(4 * minute, minute), result.sessions.map { it.toMs - it.fromMs })
        assertEquals(0, result.unpaired)
    }

    @Test
    fun `a screen that is on now runs to now, and one that is not on now leaves an unpaired on`() {
        val events = listOf(screen("2026-11-13T09:00:00Z", true))
        val now = at("2026-11-13T09:07:00Z")

        assertEquals(
            7 * minute,
            screenSessions(events, now, screenOnNow = true).sessions.single().let { it.toMs - it.fromMs }
        )
        val dropped = screenSessions(events, now, screenOnNow = false)
        assertEquals(emptyList<ScreenSession>(), dropped.sessions)
        assertEquals(1, dropped.unpaired)
    }

    @Test
    fun `an on with no off before the next on, and an off with no on, are counted and add no time`() {
        val result = screenSessions(
            listOf(
                screen("2026-11-13T08:00:00Z", false),
                screen("2026-11-13T09:00:00Z", true),
                screen("2026-11-13T09:30:00Z", true),
                screen("2026-11-13T09:32:00Z", false),
            ),
            now = at("2026-11-13T10:00:00Z"),
            screenOnNow = false,
        )
        assertEquals(listOf(2 * minute), result.sessions.map { it.toMs - it.fromMs })
        assertEquals(2, result.unpaired)
    }

    @Test
    fun `today counts only today's unlocks in the phone's own time zone, not UTC`() {
        val events = listOf(
            unlock("2026-07-01T22:30:00Z"),
            unlock("2026-07-01T23:30:00Z"),
            unlock("2026-07-02T08:00:00Z"),
        )
        val today = phoneToday(events, Instant.parse("2026-07-02T10:00:00Z"), london, screenOnNow = false)

        assertEquals(2, today.unlocks)
    }

    @Test
    fun `the longest gap is between two unlocks on the same day, and the night before the first does not count`() {
        val events = listOf(
            unlock("2026-11-12T22:00:00Z"),
            unlock("2026-11-13T07:00:00Z"),
            unlock("2026-11-13T07:20:00Z"),
            unlock("2026-11-13T10:20:00Z"),
        )
        val today = phoneToday(events, Instant.parse("2026-11-13T10:30:00Z"), london, screenOnNow = false)

        assertEquals(3 * hour, today.longestGapMs)
        assertEquals(10 * minute, today.sinceLastUnlockMs)
    }

    @Test
    fun `a gap still running counts as the longest when it is the longest`() {
        val events = listOf(unlock("2026-11-13T07:00:00Z"), unlock("2026-11-13T07:10:00Z"))
        val today = phoneToday(events, Instant.parse("2026-11-13T09:10:00Z"), london, screenOnNow = false)

        assertEquals(2 * hour, today.longestGapMs)
    }

    @Test
    fun `no unlocks today means no gap and no time since`() {
        val today =
            phoneToday(listOf(unlock("2026-11-12T10:00:00Z")), Instant.parse("2026-11-13T10:00:00Z"), london, false)

        assertEquals(0, today.unlocks)
        assertNull(today.longestGapMs)
        assertNull(today.sinceLastUnlockMs)
    }

    @Test
    fun `one unlock today has only the running gap`() {
        val today =
            phoneToday(listOf(unlock("2026-11-13T07:00:00Z")), Instant.parse("2026-11-13T08:00:00Z"), london, false)

        assertEquals(hour, today.longestGapMs)
    }

    @Test
    fun `unlocks and screen time are bucketed by hour, a session being split where it crosses an hour`() {
        val events = listOf(
            unlock("2026-11-13T09:55:00Z"),
            unlock("2026-11-13T10:10:00Z"),
            screen("2026-11-13T09:55:00Z", true),
            screen("2026-11-13T10:15:00Z", false),
        )
        val today = phoneToday(events, Instant.parse("2026-11-13T12:00:00Z"), london, screenOnNow = false)

        assertEquals(24, today.perHour.size)
        assertEquals(listOf(1, 1), listOf(9, 10).map { today.perHour[it].plays })
        assertEquals(5 * minute, today.perHour[9].heardMs)
        assertEquals(15 * minute, today.perHour[10].heardMs)
        assertEquals(20 * minute, today.screenMs)
    }

    @Test
    fun `the day the clocks go forward keeps 24 bars, and a session across the missing hour counts real time`() {
        val events = listOf(
            screen("2026-03-29T00:50:00Z", true),
            screen("2026-03-29T01:10:00Z", false),
        )
        val today = phoneToday(events, Instant.parse("2026-03-29T12:00:00Z"), london, screenOnNow = false)

        assertEquals(24, today.perHour.size)
        assertEquals(20 * minute, today.screenMs)
        assertEquals(10 * minute, today.perHour[0].heardMs)
        assertEquals(10 * minute, today.perHour[2].heardMs)
        assertEquals(0L, today.perHour[1].heardMs)
    }

    @Test
    fun `the day the clocks go back keeps one day's unlocks together and counts the extra hour's screen time`() {
        val events = listOf(
            unlock("2026-10-24T23:30:00Z"),
            unlock("2026-10-25T01:30:00Z"),
            unlock("2026-10-25T10:00:00Z"),
            screen("2026-10-24T23:30:00Z", true),
            screen("2026-10-25T02:30:00Z", false),
        )
        val today = phoneToday(events, Instant.parse("2026-10-25T12:00:00Z"), london, screenOnNow = false)

        assertEquals(3, today.unlocks)
        assertEquals(3 * hour, today.screenMs)
    }

    @Test
    fun `a session over midnight is split between the two days`() {
        val events = listOf(
            screen("2026-11-12T23:30:00Z", true),
            screen("2026-11-13T00:20:00Z", false),
        )
        val trend =
            phoneTrend(
                events,
                days = 7,
                now = Instant.parse("2026-11-13T12:00:00Z"),
                zone = london,
                screenOnNow = false
            )

        assertEquals(30 * minute, trend.bars[5].heardMs)
        assertEquals(20 * minute, trend.bars[6].heardMs)
        assertEquals(50 * minute, trend.screenMs)
    }

    @Test
    fun `the trend has one bar per day, ending today, with totals and averages`() {
        val events = listOf(
            unlock("2026-11-13T08:00:00Z"),
            unlock("2026-11-13T09:00:00Z"),
            unlock("2026-11-11T09:00:00Z"),
            unlock("2026-11-03T09:00:00Z"),
        )
        val week = phoneTrend(events, 7, Instant.parse("2026-11-13T12:00:00Z"), london, false)
        val month = phoneTrend(events, 30, Instant.parse("2026-11-13T12:00:00Z"), london, false)

        assertEquals(7, week.bars.size)
        assertEquals("13", week.bars.last().label)
        assertEquals(listOf(0, 0, 0, 0, 1, 0, 2), week.bars.map { it.plays })
        assertEquals(3, week.unlocks)
        assertEquals(3 / 7.0, week.averageUnlocksPerDay, 0.0001)
        assertEquals(30, month.bars.size)
        assertEquals(4, month.unlocks)
    }

    @Test
    fun `the apps opened first after an unlock are ranked by how many times and carry the time that followed`() {
        val events = listOf(
            unlock("2026-11-13T08:00:00Z", "com.whatsapp"),
            unlock("2026-11-13T09:00:00Z", "com.whatsapp"),
            unlock("2026-11-13T10:00:00Z", "com.android.chrome"),
            unlock("2026-11-13T11:00:00Z"),
            screen("2026-11-13T08:00:00Z", true),
            screen("2026-11-13T08:05:00Z", false),
            screen("2026-11-13T09:00:00Z", true),
            screen("2026-11-13T09:01:00Z", false),
        )
        val rows = unlockRows(events, now = at("2026-11-13T12:00:00Z"), screenOnNow = false)
        val apps = firstApps(rows, since = at("2026-11-13T00:00:00Z"))

        assertEquals(listOf("com.whatsapp", "com.android.chrome"), apps.map { it.key })
        assertEquals(listOf(2, 1), apps.map { it.plays })
        assertEquals(6 * minute, apps.first().heardMs)
    }

    @Test
    fun `an unlock row says how long the screen stayed on after it`() {
        val events = listOf(
            screen("2026-11-13T08:00:00Z", true),
            unlock("2026-11-13T08:00:02Z"),
            screen("2026-11-13T08:03:00Z", false),
            unlock("2026-11-13T09:00:00Z"),
        )
        val rows = unlockRows(events, now = at("2026-11-13T12:00:00Z"), screenOnNow = false)

        assertEquals(listOf(178_000L, null), rows.map { it.screenMs })
    }

    @Test
    fun `a bar opens the unlocks behind it, an hour bar the hour and a day bar the day`() {
        val events = listOf(
            unlock("2026-11-13T09:05:00Z"),
            unlock("2026-11-13T09:50:00Z"),
            unlock("2026-11-13T10:05:00Z"),
            unlock("2026-11-12T09:05:00Z"),
        )
        val now = Instant.parse("2026-11-13T12:00:00Z")
        val rows = unlockRows(events, now.toEpochMilli(), screenOnNow = false)
        val today = phoneToday(events, now, london, false)
        val week = phoneTrend(events, 7, now, london, false)

        assertEquals(2, rows.inBar(today.perHour[9], london).size)
        assertEquals(1, rows.inBar(today.perHour[10], london).size)
        assertEquals(3, rows.inBar(week.bars.last(), london).size)
        assertEquals(1, rows.inBar(week.bars[5], london).size)
    }

    @Test
    fun `nudge totals count shown, continued and left, and what was shown but never answered`() {
        val events = listOf(
            StatEvent.UnlockNudge(at("2026-11-13T09:00:00Z"), UnlockNudgeWhat.SHOWN, 3, 14),
            StatEvent.UnlockNudge(at("2026-11-13T09:00:08Z"), UnlockNudgeWhat.CONTINUED),
            StatEvent.UnlockNudge(at("2026-11-13T10:00:00Z"), UnlockNudgeWhat.SHOWN, 5, 15),
            StatEvent.UnlockNudge(at("2026-11-13T10:00:03Z"), UnlockNudgeWhat.LEFT),
            StatEvent.UnlockNudge(at("2026-11-13T11:00:00Z"), UnlockNudgeWhat.SHOWN, 2, 16),
            StatEvent.UnlockNudge(at("2026-11-01T11:00:00Z"), UnlockNudgeWhat.SHOWN, 2, 3),
        )
        val totals = unlockNudgeTotals(events, since = at("2026-11-13T00:00:00Z"))

        assertEquals(UnlockNudgeTotals(shown = 3, continued = 1, left = 1, unanswered = 1), totals)
    }

    @Test
    fun `phone events round trip through the log and an old build skips them without failing`() {
        val events = listOf(
            unlock("2026-11-13T09:00:00Z", "com.whatsapp"),
            unlock("2026-11-13T09:01:00Z"),
            screen("2026-11-13T09:00:00Z", true),
            StatEvent.UnlockNudge(at("2026-11-13T09:00:00Z"), UnlockNudgeWhat.SHOWN, 3, 14),
        )
        val decoded = StatsCodec.read(events.map(StatsCodec::line).asSequence())

        assertEquals(events, decoded.events)
        assertEquals(0, decoded.unreadable)
        assertTrue(!StatsCodec.line(events[1]).contains("firstApp"))
    }
}
