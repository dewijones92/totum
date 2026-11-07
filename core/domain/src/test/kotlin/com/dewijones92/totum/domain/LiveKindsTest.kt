package com.dewijones92.totum.domain

import com.dewijones92.totum.common.HttpUrl
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.time.Duration.Companion.minutes

class LiveKindsTest {

    private val every = MediaContentKind.entries

    @Test
    fun `a standard item takes whatever the resolution says`() {
        every.forEach { resolved ->
            assertEquals(resolved, MediaContentKind.STANDARD.afterSeeing(resolved))
        }
    }

    @Test
    fun `a live item becomes was live when the resolution says it ended and otherwise stays live`() {
        assertEquals(MediaContentKind.WAS_LIVE, MediaContentKind.LIVE.afterSeeing(MediaContentKind.WAS_LIVE))
        every.filter { it != MediaContentKind.WAS_LIVE }.forEach { resolved ->
            assertEquals("resolved=$resolved", MediaContentKind.LIVE, MediaContentKind.LIVE.afterSeeing(resolved))
        }
    }

    @Test
    fun `an ended stream goes back to live when the resolution says it is live and otherwise stays ended`() {
        assertEquals(MediaContentKind.LIVE, MediaContentKind.WAS_LIVE.afterSeeing(MediaContentKind.LIVE))
        every.filter { it != MediaContentKind.LIVE }.forEach { resolved ->
            assertEquals(
                "resolved=$resolved",
                MediaContentKind.WAS_LIVE,
                MediaContentKind.WAS_LIVE.afterSeeing(resolved)
            )
        }
    }

    @Test
    fun `a short, a song and a book never change kind whatever is resolved`() {
        listOf(MediaContentKind.SHORT, MediaContentKind.MUSIC, MediaContentKind.AUDIOBOOK).forEach { kind ->
            every.forEach { resolved -> assertEquals(kind, kind.afterSeeing(resolved)) }
        }
    }

    @Test
    fun `only a video can be live`() {
        assertTrue(MediaContentKind.STANDARD.canBeLive)
        assertTrue(MediaContentKind.LIVE.canBeLive)
        assertTrue(MediaContentKind.WAS_LIVE.canBeLive)
        assertFalse(MediaContentKind.SHORT.canBeLive)
        assertFalse(MediaContentKind.MUSIC.canBeLive)
        assertFalse(MediaContentKind.AUDIOBOOK.canBeLive)
    }

    private fun item(kind: MediaContentKind) = MediaItem(
        id = MediaItemId("a"),
        sourceId = SourceId("s"),
        title = "Title",
        publishedAt = null,
        duration = 10.minutes,
        mediaUrl = HttpUrl.of("https://www.youtube.com/watch?v=dQw4w9WgXcQ"),
        contentKind = kind,
    )

    @Test
    fun `learning nothing new hands back the very same item so a caller can tell`() {
        val known = item(MediaContentKind.LIVE)

        assertSame(known, known.fillingSilenceFrom(item(MediaContentKind.LIVE)))
        assertSame(known, known.fillingSilenceFrom(item(MediaContentKind.STANDARD)))
    }

    @Test
    fun `learning that a stream ended gives a copy of the item with the new kind`() {
        val known = item(MediaContentKind.LIVE)

        val learned = known.fillingSilenceFrom(item(MediaContentKind.WAS_LIVE))

        assertEquals(MediaContentKind.WAS_LIVE, learned.contentKind)
        assertEquals(known.title, learned.title)
    }

    @Test
    fun `a queued standard item learns that it is live`() {
        assertEquals(
            MediaContentKind.LIVE,
            item(MediaContentKind.STANDARD).fillingSilenceFrom(item(MediaContentKind.LIVE)).contentKind,
        )
    }

    @Test
    fun `a song keeps its kind when a resolution of the same id says live`() {
        val song = item(MediaContentKind.MUSIC)

        assertSame(song, song.fillingSilenceFrom(item(MediaContentKind.LIVE)))
    }
}
