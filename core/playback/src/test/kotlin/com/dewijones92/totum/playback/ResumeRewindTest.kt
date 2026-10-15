package com.dewijones92.totum.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ResumeRewindTest {

    private var clock = 0L
    private val rewind = ResumeRewind { clock }
    private val talk = ResumeFacts(talk = true, live = false, enabled = true)

    private fun pauseFor(ms: Long, item: String = "a", at: Long = 600_000) {
        rewind.onPaused(item, at)
        clock += ms
    }

    @Test
    fun `the rewind grows with the pause, from 2 s to 20 s after an hour`() {
        assertEquals(0L, rewindAfterPauseMs(2_000))
        assertEquals(2_000L, rewindAfterPauseMs(3_000))
        assertEquals(20_000L, rewindAfterPauseMs(3_600_000))
        assertEquals(20_000L, rewindAfterPauseMs(10 * 3_600_000L))
        assertTrue(rewindAfterPauseMs(600_000) in 3_000L..6_000L)
    }

    @Test
    fun `talk resumed after an hour starts 20 s earlier`() {
        pauseFor(3_600_000)
        assertEquals(580_000L, rewind.onResumed("a", 600_000, talk))
    }

    @Test
    fun `a quick pause is not rewound`() {
        pauseFor(1_000)
        assertNull(rewind.onResumed("a", 600_000, talk))
    }

    @Test
    fun `music, a live stream, a seek while paused or the setting off are left alone`() {
        pauseFor(600_000)
        assertNull(rewind.onResumed("a", 600_000, talk.copy(talk = false)))
        pauseFor(600_000)
        assertNull(rewind.onResumed("a", 600_000, talk.copy(live = true)))
        pauseFor(600_000)
        assertNull(rewind.onResumed("a", 900_000, talk))
        pauseFor(600_000)
        assertNull(rewind.onResumed("a", 600_000, talk.copy(enabled = false)))
    }

    @Test
    fun `a different item playing on resume is not rewound`() {
        pauseFor(600_000, item = "a")
        assertNull(rewind.onResumed("b", 600_000, talk))
    }

    @Test
    fun `the same item re-fetched while paused keeps its pause, another item drops it`() {
        pauseFor(3_600_000)
        rewind.replacedBy("a")
        assertEquals(580_000L, rewind.onResumed("a", 600_000, talk))
        pauseFor(3_600_000)
        rewind.replacedBy("b")
        assertNull(rewind.onResumed("a", 600_000, talk))
    }

    @Test
    fun `never before the start`() {
        pauseFor(3_600_000, at = 5_000)
        assertEquals(0L, rewind.onResumed("a", 5_000, talk))
    }

    @Test
    fun `a saved position from yesterday starts 20 s earlier, music does not`() {
        assertEquals(580_000L, rewoundStart("a", 600_000, 86_400_000, talk, "resuming saved"))
        assertEquals(600_000L, rewoundStart("a", 600_000, 86_400_000, talk.copy(talk = false), "resuming saved"))
        assertEquals(0L, rewoundStart("a", 0, 86_400_000, talk, "resuming saved"))
    }
}
