package com.dewijones92.totum.domain

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Where an item resumes when this device and YouTube both have an opinion.
 *
 * The measurement behind every case here: the app reported `caVJh4jrOxE` at 789.873s and YouTube's
 * history came back holding **13%** of a 1:44:13 video — 789/6253 = 12.6%. YouTube gives a
 * whole-number percent, so on that video one percent is 62 seconds and the remote position is only
 * ever good to about half a minute. Local is exact.
 */
class ResumeChoiceTest {

    private val hour44 = 6_253_000L // 1:44:13, the video the rule was measured against

    @Test
    fun `nothing anywhere resumes from nothing`() {
        assertEquals(ResumeChoice(null, Because.ONLY_LOCAL), resumeFrom(null, null, hour44))
    }

    /** The whole point of the feature: watched elsewhere, never opened here. */
    @Test
    fun `only YouTube has a position, so use it`() {
        assertEquals(ResumeChoice(2_400_000, Because.ONLY_REMOTE), resumeFrom(null, 2_400_000, hour44))
    }

    /** A podcast, or a video the account has never seen — YouTube has nothing to say. */
    @Test
    fun `only this device has a position, so use it`() {
        assertEquals(ResumeChoice(789_873, Because.ONLY_LOCAL), resumeFrom(789_873, null, hour44))
    }

    /** Watched a further half hour on the TV: that is genuinely newer knowledge. */
    @Test
    fun `YouTube well ahead wins`() {
        val choice = resumeFrom(localMs = 789_873, remoteMs = 2_400_000, durationMs = hour44)

        assertEquals(ResumeChoice(2_400_000, Because.REMOTE_IS_AHEAD), choice)
    }

    /**
     * THE case that stops the feature making things worse. Our own ping is what put YouTube's
     * number there, rounded down to a percent on the way — so on the device doing the watching the
     * remote is always a little behind, and taking it would throw you back every single time.
     */
    @Test
    fun `YouTube behind local never wins`() {
        val choice = resumeFrom(localMs = 789_873, remoteMs = 750_000, durationMs = hour44)

        assertEquals(ResumeChoice(789_873, Because.LOCAL_IS_AS_GOOD), choice)
    }

    /**
     * And nor does a lead smaller than the number's own resolution. One percent of this video is
     * 62s, so a 30s "lead" is rounding, not knowledge.
     */
    @Test
    fun `a lead inside one percent is noise, not knowledge`() {
        val choice = resumeFrom(localMs = 789_873, remoteMs = 789_873 + 30_000, durationMs = hour44)

        assertEquals(Because.LOCAL_IS_AS_GOOD, choice.because)
        assertEquals(789_873L, choice.positionMs)
    }

    @Test
    fun `a lead beyond one percent is real`() {
        val choice = resumeFrom(localMs = 789_873, remoteMs = 789_873 + 70_000, durationMs = hour44)

        assertEquals(Because.REMOTE_IS_AHEAD, choice.because)
    }

    /**
     * A short video's one percent is a couple of seconds, well inside the noise of when a ping
     * happened to fire — so the floor, not the percentage, decides there.
     */
    @Test
    fun `a short item uses the floor rather than its tiny one percent`() {
        val fiveMinutes = 300_000L

        assertEquals(Because.LOCAL_IS_AS_GOOD, resumeFrom(100_000, 130_000, fiveMinutes).because)
        assertEquals(Because.REMOTE_IS_AHEAD, resumeFrom(100_000, 170_000, fiveMinutes).because)
    }

    /** An unknown duration must not divide by anything or crash; the floor carries it. */
    @Test
    fun `an unknown duration still decides`() {
        assertEquals(Because.LOCAL_IS_AS_GOOD, resumeFrom(100_000, 130_000, null).because)
        assertEquals(Because.REMOTE_IS_AHEAD, resumeFrom(100_000, 200_000, null).because)
    }

    /**
     * THE bug in report 0.1.496, Dewi: *"I have tried to rewind the video back to the start but it
     * is not working"*. `vceHVwxOnhA`, 12:57 long, YouTube holding 77700ms and unable to move it
     * (the outbound half was refused, `held=123`). He rewound to the start six times and every
     * re-entry answered `REMOTE_IS_AHEAD [local=11273 youtube=77700]` — the same figure each time.
     * Having already been acted on, it is an echo of the last decision and says nothing about what
     * has happened here since.
     */
    @Test
    fun `a remote position already acted on cannot overrule a rewind`() {
        val choice = resumeFrom(
            localMs = 11_273,
            remoteMs = 209_790,
            durationMs = 777_000,
            remoteAlreadyUsedMs = 209_790,
        )

        assertEquals(ResumeChoice(11_273, Because.REMOTE_IS_OLD_NEWS), choice)
    }

    /** And with it not recorded, the old behaviour is exactly what happened in that report. */
    @Test
    fun `the same numbers with nothing recorded still take the remote`() {
        val choice = resumeFrom(localMs = 11_273, remoteMs = 209_790, durationMs = 777_000)

        assertEquals(ResumeChoice(209_790, Because.REMOTE_IS_AHEAD), choice)
    }

    /**
     * The report's real figure, 77700ms of 777000ms, was YouTube's 10% floor all along, so today it
     * never gets as far as "already acted on". The cases around this one use 27% to keep testing that.
     */
    @Test
    fun `the 0_1_496 figure itself was only the floor`() {
        val choice = resumeFrom(localMs = 11_273, remoteMs = 77_700, durationMs = 777_000)

        assertEquals(ResumeChoice(11_273, Because.REMOTE_ONLY_SAYS_STARTED), choice)
    }

    /** Watching elsewhere MOVES the number, which is the whole feature and must survive the fix. */
    @Test
    fun `a remote position that has moved on still wins`() {
        val choice = resumeFrom(
            localMs = 11_273,
            remoteMs = 2_400_000,
            durationMs = hour44,
            remoteAlreadyUsedMs = 209_790,
        )

        assertEquals(ResumeChoice(2_400_000, Because.REMOTE_IS_AHEAD), choice)
    }

    /**
     * Having no local position for an item already resumed once means the position was TAKEN AWAY
     * — marked unplayed, or played to the end. An echo of the old decision must not put it back.
     * A figure never acted on is a different thing entirely and still wins (above).
     */
    @Test
    fun `an already-used remote does not resurrect a position this device has dropped`() {
        val choice = resumeFrom(
            localMs = null,
            remoteMs = 209_790,
            durationMs = 777_000,
            remoteAlreadyUsedMs = 209_790,
        )

        assertEquals(ResumeChoice(null, Because.REMOTE_IS_OLD_NEWS), choice)
    }

    /** But a figure this device has never acted on is the cross-device case, and still wins. */
    @Test
    fun `a remote never acted on still wins when this device knows nothing`() {
        val choice = resumeFrom(localMs = null, remoteMs = 209_790, durationMs = 777_000)

        assertEquals(ResumeChoice(209_790, Because.ONLY_REMOTE), choice)
    }

    /**
     * Report 0.1.514, Dewi: *"why downloaded files have position of like 5% in to the video?"*. Five
     * videos played for a few seconds each came back from YouTube at exactly 10%, and so did every one
     * of the 12 lowest of the 17 positions in all reports so far: none has ever been under 10. YouTube
     * floors `percentDurationWatched` at 10, so 10 means "started", not a position.
     */
    @Test
    fun `YouTube's ten percent floor is not a position`() {
        val choice = resumeFrom(localMs = null, remoteMs = 226_000, durationMs = 2_260_000)

        assertEquals(ResumeChoice(null, Because.REMOTE_ONLY_SAYS_STARTED), choice)
    }

    @Test
    fun `nor does the floor beat a small position this device has`() {
        val choice = resumeFrom(localMs = 5_854, remoteMs = 226_000, durationMs = 2_260_000)

        assertEquals(ResumeChoice(5_854, Because.REMOTE_ONLY_SAYS_STARTED), choice)
    }

    /**
     * Where Dewi's phone actually IS: before the fix the floor won and was written into this device's
     * own store, so the local figure is now the floor to the millisecond. Only adoption produces that
     * exact match, so it is the floor's echo and not a place he stopped.
     */
    @Test
    fun `a floor adopted before the fix is not a position either`() {
        val choice = resumeFrom(
            localMs = 226_000,
            remoteMs = 226_000,
            durationMs = 2_260_000,
            remoteAlreadyUsedMs = 226_000,
        )

        assertEquals(ResumeChoice(null, Because.REMOTE_ONLY_SAYS_STARTED), choice)
    }

    /** Stopped at exactly a tenth here, on this device, with nothing adopted: that is real. */
    @Test
    fun `a position of this device's own at a tenth is kept`() {
        val choice = resumeFrom(localMs = 226_000, remoteMs = 226_000, durationMs = 2_260_000)

        assertEquals(ResumeChoice(226_000, Because.REMOTE_ONLY_SAYS_STARTED), choice)
    }

    /** And an adopted floor must not stand in the way when the account genuinely moves on. */
    @Test
    fun `an adopted floor gives way to real progress elsewhere`() {
        val choice = resumeFrom(
            localMs = 226_000,
            remoteMs = 610_200,
            durationMs = 2_260_000,
            remoteAlreadyUsedMs = 226_000,
        )

        assertEquals(ResumeChoice(610_200, Because.ONLY_REMOTE), choice)
    }

    /** Just above the floor is a real percent again, so the cross-device case is untouched. */
    @Test
    fun `eleven percent is a real position`() {
        val choice = resumeFrom(localMs = null, remoteMs = 248_600, durationMs = 2_260_000)

        assertEquals(ResumeChoice(248_600, Because.ONLY_REMOTE), choice)
    }

    /** Starting fresh on this device while YouTube holds a real position is the cross-device case. */
    @Test
    fun `zero here and a real position there is still the remote`() {
        val choice = resumeFrom(localMs = 0, remoteMs = 2_400_000, durationMs = hour44)

        assertEquals(Because.REMOTE_IS_AHEAD, choice.because)
        assertEquals(2_400_000L, choice.positionMs)
    }
}
