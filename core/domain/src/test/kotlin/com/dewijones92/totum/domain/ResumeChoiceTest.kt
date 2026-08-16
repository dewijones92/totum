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

    /** Starting fresh on this device while YouTube holds a real position is the cross-device case. */
    @Test
    fun `zero here and a real position there is still the remote`() {
        val choice = resumeFrom(localMs = 0, remoteMs = 2_400_000, durationMs = hour44)

        assertEquals(Because.REMOTE_IS_AHEAD, choice.because)
        assertEquals(2_400_000L, choice.positionMs)
    }
}
