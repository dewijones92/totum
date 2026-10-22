package com.dewijones92.totum.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ResumeRewindTest {

    private var clock = 0L
    private val rewind = ResumeRewind { clock }
    private val talk = ResumeFacts(talk = true, live = false, enabled = true)

    private fun moment(
        item: String = "a",
        at: Long = 600_000,
        playWhenReady: Boolean = true,
        ended: Boolean = false,
        focusLost: Boolean = false,
    ) = PlayerMoment(item, at, playWhenReady, ended, focusLost)

    private fun pause(item: String = "a", at: Long = 600_000, endOfItem: Boolean = false) =
        rewind.onChange(moment(item, at, playWhenReady = false), endOfItem, talk)

    private fun resume(item: String = "a", at: Long = 600_000, facts: ResumeFacts = talk) =
        rewind.onChange(moment(item, at), endOfItem = false, facts)

    @Test
    fun `the rewind grows with the pause, from 2 s to 20 s after an hour`() {
        assertEquals(0L, rewindAfterPauseMs(2_000))
        assertEquals(2_000L, rewindAfterPauseMs(3_000))
        assertEquals(20_000L, rewindAfterPauseMs(3_600_000))
        assertEquals(20_000L, rewindAfterPauseMs(10 * 3_600_000L))
        assertTrue(rewindAfterPauseMs(600_000) in 3_000L..6_000L)
    }

    @Test
    fun `talk resumed after an hour starts 20 s earlier, a quick pause does not move`() {
        pause()
        clock += 3_600_000
        assertEquals(580_000L, resume())
        pause()
        clock += 1_000
        assertNull(resume())
    }

    @Test
    fun `music, a live stream, a seek while paused, another item or the setting off are left alone`() {
        listOf(talk.copy(talk = false), talk.copy(live = true), talk.copy(enabled = false)).forEach { facts ->
            pause()
            clock += 600_000
            assertNull(resume(facts = facts))
        }
        pause()
        clock += 600_000
        assertNull(resume(at = 900_000))
        pause(item = "a")
        clock += 600_000
        assertNull(resume(item = "b"))
    }

    @Test
    fun `a phone call (audio focus lost while still meaning to play) rewinds when it gives the sound back`() {
        rewind.onChange(moment(focusLost = true), endOfItem = false, talk)
        clock += 600_000
        val back = rewind.onChange(moment(focusLost = false), endOfItem = false, talk)
        assertTrue(back != null && back < 600_000)
    }

    @Test
    fun `the player stopping at an item's end is not a pause, and nothing is rewound once it has ended`() {
        pause(endOfItem = true)
        clock += 600_000
        assertNull(resume())
        pause()
        clock += 600_000
        assertNull(rewind.onChange(moment(ended = true), endOfItem = false, talk))
        assertNull(resume())
    }

    @Test
    fun `a forgotten pause (a fresh start chose its own place) is not rewound twice`() {
        pause()
        clock += 3_600_000
        rewind.forget()
        assertNull(resume(at = 580_000))
    }

    @Test
    fun `the same item re-fetched while paused keeps its pause, another item drops it`() {
        pause()
        clock += 3_600_000
        rewind.replacedBy("a")
        assertEquals(580_000L, resume())
        pause()
        clock += 3_600_000
        rewind.replacedBy("b")
        assertNull(resume())
    }

    @Test
    fun `never before the start`() {
        pause(at = 5_000)
        clock += 3_600_000
        assertEquals(0L, resume(at = 5_000))
    }

    @Test
    fun `a saved position from yesterday starts 20 s earlier, music, nothing saved and a shuffle start do not`() {
        val day = 86_400_000L
        assertEquals(580_000L, startFor(SavedPlace(600_000, 0), day, talk, null).positionMs)
        assertEquals(600_000L, startFor(SavedPlace(600_000, 0), day, talk.copy(talk = false), null).positionMs)
        assertEquals(0L, startFor(SavedPlace(null, null), day, talk, null).positionMs)
        assertEquals(600_000L, startFor(SavedPlace(600_000, null), day, talk, null).positionMs)
    }

    @Test
    fun `a live stream starts at its live edge, never at a saved place`() {
        val live = talk.copy(live = true)
        assertEquals(0L, startFor(SavedPlace(179_449, 0), 60_000, live, null).positionMs)
        assertEquals(0L, startFor(SavedPlace(179_449, null), 60_000, live, introEndMs = 83_000).positionMs)
    }

    @Test
    fun `a start inside the show's intro begins where the intro ends instead`() {
        val fromTop = startFor(SavedPlace(null, null), 0, talk, introEndMs = 83_000)
        assertEquals(83_000L, fromTop.positionMs)
        assertTrue(fromTop.why.contains("intro"))
        assertEquals(600_000L, startFor(SavedPlace(600_000, 0), 1_000, talk, introEndMs = 83_000).positionMs)
    }
}
