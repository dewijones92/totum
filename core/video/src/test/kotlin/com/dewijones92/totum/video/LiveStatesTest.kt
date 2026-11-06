package com.dewijones92.totum.video

import com.dewijones92.totum.domain.MediaContentKind
import com.dewijones92.totum.innertube.player.UpcomingLive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.Instant

class LiveStatesTest {
    private var clock = 0L
    private val states = LiveStates { clock }

    @Test
    fun `a resolution tells the registry live, ended or nothing`() {
        states.observed("a", MediaContentKind.LIVE, "test")
        states.observed("b", MediaContentKind.WAS_LIVE, "test")
        states.observed("c", MediaContentKind.STANDARD, "test")

        assertEquals(LivePhase.LIVE, states.of("a")?.phase)
        assertEquals(LivePhase.ENDED, states.of("b")?.phase)
        assertNull(states.of("c"))
    }

    @Test
    fun `an upcoming entry is cleared by a plain resolve but a live one is not`() {
        states.noteUpcoming("a", UpcomingLive(Instant.ofEpochSecond(100), "soon"), "test")
        states.noteLive("b", "test")

        states.observed("a", MediaContentKind.STANDARD, "test")
        states.observed("b", MediaContentKind.STANDARD, "test")

        assertNull(states.of("a"))
        assertEquals(LivePhase.LIVE, states.of("b")?.phase)
    }

    @Test
    fun `upcoming and live entries expire after twelve hours and ended ones do not`() {
        states.noteLive("live", "test")
        states.noteEnded("ended", "test")
        clock += 13 * 60 * 60_000L

        states.noteLive("fresh", "test")

        assertNull(states.of("live"))
        assertEquals(LivePhase.ENDED, states.of("ended")?.phase)
        assertEquals(setOf("ended", "fresh"), states.states.value.keys)
    }

    @Test
    fun `the first sighting is kept when an entry is noted again`() {
        states.noteLive("a", "test")
        clock += 1_000L
        states.noteEnded("a", "test")

        val entry = states.of("a")
        assertEquals(0L, entry?.firstSeenMs)
        assertEquals(1_000L, entry?.lastSeenMs)
        assertEquals(LivePhase.ENDED, entry?.phase)
    }

    @Test
    fun `cleared removes an entry`() {
        states.noteLive("a", "test")
        states.cleared("a", "test")
        assertNull(states.of("a"))
    }

    @Test
    fun `the registry beats the saved kind and only videos can be live`() {
        val ended = states.run {
            noteEnded("a", "test")
            of("a")
        }
        val upcoming = LiveState("b", LivePhase.UPCOMING, Instant.ofEpochSecond(5), "", 0, 0)

        assertEquals(LiveTag.WasLive, liveTagOf(MediaContentKind.LIVE, ended))
        assertEquals(LiveTag.StartsSoon(Instant.ofEpochSecond(5)), liveTagOf(MediaContentKind.STANDARD, upcoming))
        assertEquals(LiveTag.Live, liveTagOf(MediaContentKind.LIVE, null))
        assertEquals(LiveTag.WasLive, liveTagOf(MediaContentKind.WAS_LIVE, null))
        assertNull(liveTagOf(MediaContentKind.STANDARD, null))
        assertNull(liveTagOf(MediaContentKind.SHORT, upcoming))
        assertNull(liveTagOf(MediaContentKind.MUSIC, ended))
    }
}
