package com.dewijones92.totum.exsurge

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.DayOfWeek
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime

class ExsurgeSupportTest {

    private val zone = ZoneId.of("Europe/London")
    private fun at(day: Int, hour: Int, minute: Int = 0, month: Int = 10): Instant =
        ZonedDateTime.of(2026, month, day, hour, minute, 0, 0, zone).toInstant()

    private val settings = ExsurgeSettings(enabled = true)

    @Test
    fun `active hours are weekdays nine till six by default`() {
        assertFalse(settings.isActiveAt(at(5, 8, 59), zone))
        assertTrue(settings.isActiveAt(at(5, 9, 0), zone))
        assertTrue(settings.isActiveAt(at(5, 17, 59), zone))
        assertFalse(settings.isActiveAt(at(5, 18, 0), zone))
        assertFalse(settings.isActiveAt(at(10, 12), zone))
    }

    @Test
    fun `an empty or inverted window is never active and never resumes`() {
        val inverted = settings.copy(startMinuteOfDay = 600, endMinuteOfDay = 540)
        assertFalse(inverted.isActiveAt(at(5, 9, 30), zone))
        assertNull(inverted.nextActiveStart(at(5, 9, 30), zone))
        assertNull(settings.copy(activeDays = emptySet()).nextActiveStart(at(5, 9), zone))
    }

    @Test
    fun `the next start skips the weekend`() {
        assertEquals(at(12, 9), settings.nextActiveStart(at(9, 18, 30), zone))
    }

    @Test
    fun `the next start survives the clocks going back`() {
        val saturday = ZonedDateTime.of(2026, 10, 24, 12, 0, 0, 0, zone).toInstant()
        val monday = settings.nextActiveStart(saturday, zone)!!
        assertEquals(9, monday.atZone(zone).hour)
        assertEquals(Duration.ofHours(46), Duration.between(saturday, monday))
    }

    @Test
    fun `the window end is today at six, or now when already past it`() {
        assertEquals(at(5, 18), settings.activeEndAfter(at(5, 10), zone))
        assertEquals(at(5, 19), settings.activeEndAfter(at(5, 19), zone))
    }

    @Test
    fun `a window running to midnight ends at the last instant of the day`() {
        val late = settings.copy(endMinuteOfDay = 24 * 60)
        assertTrue(late.isActiveAt(at(5, 23, 59), zone))
        assertTrue(late.activeEndAfter(at(5, 23), zone).isAfter(at(5, 23, 59)))
    }

    @Test
    fun `validation clamps every number into its range`() {
        val wild = ExsurgeSettings(
            sittingMinutes = 1, breakMinutes = 99, stepsToRise = -4, walkResetSteps = 99999,
            snoozeMinutes = 0, maxSnoozes = 50, voiceVolumePercent = 400, destinationPackage = "  ",
            startMinuteOfDay = -10, endMinuteOfDay = 99999,
        ).validated()
        assertEquals(10, wild.sittingMinutes)
        assertEquals(15, wild.breakMinutes)
        assertEquals(0, wild.stepsToRise)
        assertEquals(1000, wild.walkResetSteps)
        assertEquals(1, wild.snoozeMinutes)
        assertEquals(5, wild.maxSnoozes)
        assertEquals(100, wild.voiceVolumePercent)
        assertEquals(LOQUAX_PACKAGE, wild.destinationPackage)
        assertEquals(0, wild.startMinuteOfDay)
        assertEquals(24 * 60, wild.endMinuteOfDay)
    }

    @Test
    fun `the step window counts steps within five minutes`() {
        val window = StepWindow()
        val t = at(5, 10)
        assertEquals(0, window.record(t, 1000))
        assertEquals(60, window.record(t.plusSeconds(120), 1060))
        assertEquals(150, window.record(t.plusSeconds(240), 1150))
        assertEquals(90, window.record(t.plusSeconds(480), 1150))
    }

    @Test
    fun `a sparse reading still counts the steps since the one before`() {
        val window = StepWindow()
        val t = at(5, 10)
        window.record(t, 100)
        assertEquals(120, window.record(t.plusSeconds(360), 220))
    }

    @Test
    fun `the step window treats a counter reset as zero steps, not a negative`() {
        val window = StepWindow()
        val t = at(5, 10)
        window.record(t, 5000)
        assertEquals(0, window.record(t.plusSeconds(30), 2))
        assertEquals(40, window.record(t.plusSeconds(60), 42))
        window.clear()
        assertEquals(0, window.record(t.plusSeconds(90), 10))
    }

    private fun outcome(day: Int, kind: OutcomeKind, id: Long = day.toLong()) = BreakOutcome(
        summonsId = id,
        summonedAt = at(day, 10),
        resolvedAt = at(day, 10, 6),
        kind = kind,
        snoozes = 0,
        breakStartedAt = if (kind == OutcomeKind.COMPLETED) at(day, 10, 1) else null,
        steps = if (kind == OutcomeKind.COMPLETED) 300 else 0,
        stepsProven = kind == OutcomeKind.COMPLETED,
    )

    @Test
    fun `today's tally counts each kind, steps and minutes standing`() {
        val list = listOf(
            outcome(5, OutcomeKind.COMPLETED),
            outcome(5, OutcomeKind.SKIPPED, 2),
            outcome(5, OutcomeKind.MISSED, 3),
        )
        val stats = ExsurgeStats.of(list, at(5, 12), zone)
        assertEquals(DayTally(completed = 1, skipped = 1, missed = 1, steps = 300, minutesStanding = 5), stats.today)
        assertEquals(3, stats.today.summons)
    }

    @Test
    fun `the streak counts clean days and skips days with no summons`() {
        val list = listOf(
            outcome(1, OutcomeKind.MISSED),
            outcome(2, OutcomeKind.COMPLETED),
            outcome(5, OutcomeKind.COMPLETED),
            outcome(6, OutcomeKind.COMPLETED),
        )
        assertEquals(3, ExsurgeStats.of(list, at(6, 12), zone).streakDays)
        assertEquals(3, ExsurgeStats.of(list, at(7, 9), zone).streakDays)
    }

    @Test
    fun `a skip today breaks the streak at once`() {
        val list = listOf(
            outcome(5, OutcomeKind.COMPLETED),
            outcome(6, OutcomeKind.COMPLETED),
            outcome(6, OutcomeKind.SKIPPED, 99),
        )
        assertEquals(0, ExsurgeStats.of(list, at(6, 12), zone).streakDays)
    }

    @Test
    fun `no history means no streak and the first rank`() {
        val stats = ExsurgeStats.of(emptyList(), at(5, 12), zone)
        assertEquals(0, stats.streakDays)
        assertEquals(Rank.TIRO, stats.rank)
        assertEquals(10, stats.laurelsToNextRank)
    }

    @Test
    fun `ranks follow the cursus honorum`() {
        assertEquals(Rank.TIRO, Rank.forLaurels(9))
        assertEquals(Rank.LEGIONARIUS, Rank.forLaurels(10))
        assertEquals(Rank.CENTURIO, Rank.forLaurels(50))
        assertEquals(Rank.IMPERATOR, Rank.forLaurels(5000))
        assertNull(Rank.IMPERATOR.next)
    }

    @Test
    fun `the tenth laurel is a promotion and the eleventh is not`() {
        val nine = (1..9).map { outcome(5, OutcomeKind.COMPLETED, it.toLong()) }
        val ten = nine + outcome(5, OutcomeKind.COMPLETED, 10)
        assertEquals(Rank.LEGIONARIUS, ExsurgeStats.promoted(nine, ten))
        assertNull(ExsurgeStats.promoted(ten, ten + outcome(5, OutcomeKind.COMPLETED, 11)))
        assertNull(ExsurgeStats.promoted(ten, ten + outcome(5, OutcomeKind.SKIPPED, 12)))
    }

    @Test
    fun `memory, settings and outcomes survive a round trip`() {
        val states = listOf(
            ExsurgeState.Off,
            ExsurgeState.Dormant(null),
            ExsurgeState.Paused(at(5, 11)),
            ExsurgeState.Sitting(at(5, 10), OutcomeKind.SKIPPED),
            ExsurgeState.Summoned(Summons(3, at(5, 10), 1), 2, at(5, 10), at(5, 10, 1)),
            ExsurgeState.Snoozed(Summons(3, at(5, 10), 1), at(5, 10, 5)),
            ExsurgeState.Rising(Summons(3, at(5, 10)), at(5, 10), 77, 4),
            ExsurgeState.OnBreak(Summons(3, at(5, 10)), at(5, 10), null, 0, false, true),
        )
        states.forEach { state ->
            val memory = ExsurgeMemory(state, 4, at(5, 10).atZone(zone).toLocalDate())
            assertEquals(memory, ExsurgeCodec.decodeMemory(ExsurgeCodec.encodeMemory(memory)))
        }
        val custom = settings.copy(activeDays = setOf(DayOfWeek.SATURDAY), sittingMinutes = 45)
        assertEquals(custom, ExsurgeCodec.decodeSettings(ExsurgeCodec.encodeSettings(custom)))
        val list = listOf(outcome(5, OutcomeKind.COMPLETED), outcome(6, OutcomeKind.MISSED))
        assertEquals(list, ExsurgeCodec.decodeOutcomes(ExsurgeCodec.encodeOutcomes(list)))
    }

    @Test
    fun `stored settings from an older build fill in new fields with defaults`() {
        val stored = """{"enabled":true,"someOldField":3}"""
        assertEquals(ExsurgeSettings(enabled = true), ExsurgeCodec.decodeSettings(stored))
    }

    @Test
    fun `moods follow the state`() {
        val now = at(5, 10)
        assertEquals(Mood.SLEEPING, moodOf(ExsurgeState.Off, now))
        assertEquals(Mood.CONTENT, moodOf(ExsurgeState.Sitting(now), now))
        assertEquals(Mood.FREEING, moodOf(ExsurgeState.Sitting(now, OutcomeKind.COMPLETED), now.plusSeconds(10)))
        assertEquals(Mood.CONTENT, moodOf(ExsurgeState.Sitting(now, OutcomeKind.COMPLETED), now.plusSeconds(90)))
        assertEquals(Mood.WOUNDED, moodOf(ExsurgeState.Sitting(now, OutcomeKind.SKIPPED), now.plusSeconds(900)))
        assertEquals(Mood.SUMMONING, moodOf(ExsurgeState.Snoozed(Summons(1, now), now), now))
        assertEquals(Mood.CHEERING, moodOf(ExsurgeState.Rising(Summons(1, now), now), now))
        assertEquals(Mood.COUNTING, moodOf(ExsurgeState.OnBreak(Summons(1, now), now, null, 0, false), now))
    }

    @Test
    fun `the banner line says when the next summons is and how long you have sat`() {
        val context = ExsurgeContext(settings, zone, stepsAvailable = true)
        val now = at(5, 10, 17)
        assertEquals(
            BannerLine.Sitting(summonsAt = at(5, 10, 30), satMinutes = 17, limitMinutes = 30),
            bannerLineOf(ExsurgeState.Sitting(at(5, 10)), now, context),
        )
        val summoned = ExsurgeState.Summoned(Summons(1, now, 1), 2, now, now)
        assertEquals(BannerLine.Summoned(call = 2, snoozesLeft = 1), bannerLineOf(summoned, now, context))
        val rising = ExsurgeState.Rising(Summons(1, now), now, 0, 3)
        assertEquals(BannerLine.Rising(3, 20), bannerLineOf(rising, now, context))
        assertEquals(BannerLine.Rising(3, 0), bannerLineOf(rising, now, context.copy(stepsAvailable = false)))
        val onBreak = ExsurgeState.OnBreak(Summons(1, now), now, 0, 40, true)
        assertEquals(BannerLine.OnBreak(at(5, 10, 22), 40), bannerLineOf(onBreak, now, context))
        assertEquals(BannerLine.Sleeping(null), bannerLineOf(ExsurgeState.Dormant(null), now, context))
        assertEquals(BannerLine.Off, bannerLineOf(ExsurgeState.Off, now, context))
    }

    @Test
    fun `an unproven break that needed steps earns no laurel and breaks the streak`() {
        val dodged = outcome(6, OutcomeKind.COMPLETED).copy(stepsProven = false, stepsRequired = true)
        val stats = ExsurgeStats.of(listOf(outcome(5, OutcomeKind.COMPLETED), dodged), at(6, 12), zone)
        assertEquals(1, stats.laurels)
        assertEquals(0, stats.streakDays)
    }

    @Test
    fun `an unproven break with no sensor still counts`() {
        val noSensor = outcome(6, OutcomeKind.COMPLETED).copy(stepsProven = false, stepsRequired = false)
        assertEquals(1, ExsurgeStats.of(listOf(noSensor), at(6, 12), zone).laurels)
    }

    @Test
    fun `a promotion counts the archived laurels too`() {
        val one = listOf(outcome(5, OutcomeKind.COMPLETED))
        assertEquals(Rank.LEGIONARIUS, ExsurgeStats.promoted(emptyList(), one, archivedLaurels = 9))
        assertNull(ExsurgeStats.promoted(emptyList(), one, archivedLaurels = 20))
    }

    @Test
    fun `laurels archived out of the outcome log still count`() {
        val stats = ExsurgeStats.of(listOf(outcome(5, OutcomeKind.COMPLETED)), at(5, 12), zone, archivedLaurels = 49)
        assertEquals(50, stats.laurels)
        assertEquals(Rank.CENTURIO, stats.rank)
    }

    @Test
    fun `the banner promises no summons that active hours will end before`() {
        val context = ExsurgeContext(settings, zone, stepsAvailable = true)
        val line = bannerLineOf(ExsurgeState.Sitting(at(5, 17, 45)), at(5, 17, 50), context)
        assertNull((line as BannerLine.Sitting).summonsAt)
    }
}
