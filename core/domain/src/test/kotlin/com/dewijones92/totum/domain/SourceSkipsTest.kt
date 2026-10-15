package com.dewijones92.totum.domain

import com.dewijones92.totum.common.HttpUrl
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.time.Duration.Companion.milliseconds

class SourceSkipsTest {

    @Test
    fun `an intro skips from the start, an outro skips the same length off every episode's end`() {
        val skips = SourceSkips(introEndMs = 83_000, outroLengthMs = 45_000)

        assertEquals(
            listOf(
                SkipSegment(0.milliseconds, 83_000.milliseconds),
                SkipSegment(1_755_000.milliseconds, 1_800_000.milliseconds),
            ),
            skips.segmentsFor(1_800_000),
        )
        assertEquals(
            listOf(SkipSegment(3_555_000.milliseconds, 3_600_000.milliseconds)),
            SourceSkips(outroLengthMs = 45_000).segmentsFor(3_600_000),
        )
    }

    @Test
    fun `an outro needs a duration, and neither may cover the whole item`() {
        assertEquals(
            listOf(SkipSegment(0.milliseconds, 83_000.milliseconds)),
            SourceSkips(83_000, 45_000).segmentsFor(null)
        )
        assertTrue(SourceSkips(introEndMs = 90_000).segmentsFor(60_000).isEmpty())
        assertTrue(SourceSkips(outroLengthMs = 90_000).segmentsFor(60_000).isEmpty())
        assertEquals(1, SourceSkips(introEndMs = 50_000, outroLengthMs = 20_000).segmentsFor(60_000).size)
    }

    private fun video(source: String, channelUrl: String?, author: String? = "Chan") = MediaItem(
        MediaItemId("v"),
        SourceId(source),
        "a video",
        publishedAt = null,
        duration = null,
        author = author,
        mediaUrl = HttpUrl.of("https://www.youtube.com/watch?v=dQw4w9WgXcQ"),
        sourceUrl = channelUrl?.let(HttpUrl::of),
    )

    @Test
    fun `a video is keyed by its channel, whichever list it came from`() {
        val fromRelated = video("ytrelated", "https://www.youtube.com/channel/UCabcdefghijklmnopqrstuv")
        val fromSearch = video("search:ad-hoc-video", "https://www.youtube.com/channel/UCabcdefghijklmnopqrstuv")
        val otherChannel = video("ytrelated", "https://www.youtube.com/channel/UCzzzzzzzzzzzzzzzzzzzzzz")

        assertEquals(SourceId("yt:UCabcdefghijklmnopqrstuv"), fromRelated.showKey())
        assertEquals(fromRelated.showKey(), fromSearch.showKey())
        assertNotEquals(fromRelated.showKey(), otherChannel.showKey())
    }

    @Test
    fun `with no channel link a video is keyed by its channel name, and with neither it has no key`() {
        assertEquals(SourceId("name:chan"), video("ytrelated", null, author = " Chan ").showKey())
        assertNull(video("ytrelated", null, author = null).showKey())
    }

    @Test
    fun `an episode is keyed by its feed`() {
        val episode = MediaItem(
            MediaItemId("e"),
            SourceId("https://example.com/feed.xml"),
            "an episode",
            publishedAt = null,
            duration = null,
            mediaUrl = HttpUrl.of("https://example.com/e.mp3"),
        )

        assertEquals(SourceId("https://example.com/feed.xml"), episode.showKey())
    }

    @Test
    fun `the map survives a round trip, and an empty or broken line is dropped`() {
        val skips = mapOf(
            SourceId("UCabc") to SourceSkips(83_000, null),
            SourceId("feed:https://x/rss") to SourceSkips(null, 45_000),
            SourceId("gone") to SourceSkips(),
        )

        val back = decodeSourceSkips(encodeSourceSkips(skips) + "\nbroken\n\t1\t2")

        assertEquals(skips - SourceId("gone"), back)
        assertTrue(decodeSourceSkips(null).isEmpty())
    }
}
