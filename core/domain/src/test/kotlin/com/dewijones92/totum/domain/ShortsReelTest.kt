package com.dewijones92.totum.domain

import com.dewijones92.totum.common.HttpUrl
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * What opens when you tap a Short in the feed.
 *
 * Dewi, 2026-08-16: *"open in a reel sorta view but keep unified???? i dunno"*. The two are not in
 * tension — the reel plays through the same `PlaybackController` and queue as everything else, so
 * it is a presentation rather than a second playback path, and a Short can look like a Short
 * without the app growing another player.
 */
class ShortsReelTest {

    private fun item(id: String, kind: MediaContentKind) = MediaItem(
        id = MediaItemId(id),
        sourceId = SourceId("youtube"),
        title = id,
        publishedAt = null,
        duration = null,
        mediaUrl = HttpUrl.of("https://www.youtube.com/watch?v=$id"),
        contentKind = kind,
    )

    private fun video(n: Int) = item("v$n", MediaContentKind.STANDARD)

    private fun short(n: Int) = item("s$n", MediaContentKind.SHORT)

    private val feed = listOf(video(0), short(0), video(1), short(1), video(2), short(2))

    @Test
    fun `the reel holds every short in the feed, in feed order`() {
        val reel = shortsReelFrom(feed, short(1))

        assertEquals(listOf("s0", "s1", "s2"), reel.shorts.map { it.id.value })
    }

    /** It opens on the one you touched, not at the top — that is the whole point of the index. */
    @Test
    fun `it opens on the short that was tapped`() {
        assertEquals(0, shortsReelFrom(feed, short(0)).index)
        assertEquals(1, shortsReelFrom(feed, short(1)).index)
        assertEquals(2, shortsReelFrom(feed, short(2)).index)
    }

    /**
     * The Shorts above the tapped one are kept, not trimmed away. They are the ones you were just
     * scrolling past, so they are the likeliest thing to swipe back to.
     */
    @Test
    fun `the shorts you scrolled past are still behind you`() {
        val reel = shortsReelFrom(feed, short(2))

        assertEquals(3, reel.shorts.size)
        assertEquals("s0", reel.shorts.first().id.value)
    }

    /** Videos are not part of a Shorts reel, however they were interleaved. */
    @Test
    fun `videos are left out of the reel`() {
        val reel = shortsReelFrom(feed, short(0))

        assertEquals(
            emptyList<String>(),
            reel.shorts.filter { it.contentKind != MediaContentKind.SHORT }.map { it.id.value }
        )
    }

    /** A stale row, or one from a filtered view: its own reel beats refusing to open. */
    @Test
    fun `a short that is not in the list still opens on its own`() {
        val reel = shortsReelFrom(listOf(video(0), video(1)), short(9))

        assertEquals(listOf("s9"), reel.shorts.map { it.id.value })
        assertEquals(0, reel.index)
    }

    @Test
    fun `an empty feed still opens the tapped short`() {
        val reel = shortsReelFrom(emptyList(), short(0))

        assertEquals(listOf("s0"), reel.shorts.map { it.id.value })
    }
}
