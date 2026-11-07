package com.dewijones92.totum.video

import com.dewijones92.totum.domain.MediaContentKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.Instant

class LiveTagTest {

    private val startsAt = Instant.ofEpochSecond(1_000)

    private fun state(phase: LivePhase) = LiveState("v", phase, startsAt, "said", 0L, 0L)

    private val canBeLive = listOf(MediaContentKind.STANDARD, MediaContentKind.LIVE, MediaContentKind.WAS_LIVE)
    private val neverLive = listOf(MediaContentKind.SHORT, MediaContentKind.MUSIC, MediaContentKind.AUDIOBOOK)

    @Test
    fun `an upcoming entry says starts soon for every kind a video can have, with its start`() {
        canBeLive.forEach { kind ->
            assertEquals("$kind", LiveTag.StartsSoon(startsAt), liveTagOf(kind, state(LivePhase.UPCOMING)))
        }
    }

    @Test
    fun `a live entry says live and an ended entry says was live whatever the saved kind was`() {
        canBeLive.forEach { kind ->
            assertEquals("$kind", LiveTag.Live, liveTagOf(kind, state(LivePhase.LIVE)))
            assertEquals("$kind", LiveTag.WasLive, liveTagOf(kind, state(LivePhase.ENDED)))
        }
    }

    @Test
    fun `with no registry entry the saved kind decides and an ordinary video has no tag`() {
        assertEquals(LiveTag.Live, liveTagOf(MediaContentKind.LIVE, null))
        assertEquals(LiveTag.WasLive, liveTagOf(MediaContentKind.WAS_LIVE, null))
        assertNull(liveTagOf(MediaContentKind.STANDARD, null))
    }

    @Test
    fun `a short, a song and a book are never tagged, however the registry sees the id`() {
        neverLive.forEach { kind ->
            LivePhase.entries.forEach { phase -> assertNull("$kind $phase", liveTagOf(kind, state(phase))) }
            assertNull("$kind", liveTagOf(kind, null))
        }
    }

    @Test
    fun `an upcoming entry with no start time is still starts soon`() {
        val noStart = LiveState("v", LivePhase.UPCOMING, null, "said", 0L, 0L)

        assertEquals(LiveTag.StartsSoon(null), liveTagOf(MediaContentKind.STANDARD, noStart))
    }
}
