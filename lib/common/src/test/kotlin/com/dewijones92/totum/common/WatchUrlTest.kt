package com.dewijones92.totum.common

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * One video, one identity.
 *
 * The URL is the video's id everywhere — MediaItemId, the resolve cache key, what the queue
 * dedupes on — so two spellings of one video used to be two different videos. Sharing a
 * link for something already queued added a second copy instead of jumping to it (0.1.228).
 */
class WatchUrlTest {

    private val canonical = "https://www.youtube.com/watch?v=dQw4w9WgXcQ"

    @Test
    fun `every spelling of a video collapses to one`() {
        val spellings = listOf(
            "https://www.youtube.com/watch?v=dQw4w9WgXcQ",
            "https://m.youtube.com/watch?v=dQw4w9WgXcQ",
            "https://youtu.be/dQw4w9WgXcQ",
            "https://www.youtube.com/shorts/dQw4w9WgXcQ",
        )

        assertEquals(
            List(spellings.size) { canonical },
            spellings.map { HttpUrl.of(it).canonicalWatchUrl().value },
        )
    }

    /** The share sheet's `si` identifies the sharer; it has no business in a stored id. */
    @Test
    fun `share tracking and start offsets are dropped`() {
        assertEquals(
            canonical,
            HttpUrl.of("https://youtu.be/dQw4w9WgXcQ?si=iGM7wAbCdEf%3D%3D").canonicalWatchUrl().value
        )
        assertEquals(
            canonical,
            HttpUrl.of("https://www.youtube.com/watch?v=dQw4w9WgXcQ&t=150s").canonicalWatchUrl().value
        )
    }

    @Test
    fun `anything that is not a YouTube video is left exactly as it was`() {
        val enclosure = "https://cdn.example.com/episode-42.mp3"

        assertEquals(enclosure, HttpUrl.of(enclosure).canonicalWatchUrl().value)
        assertNull(HttpUrl.of(enclosure).youTubeVideoId())
    }

    /**
     * The id is a query PARAMETER, not the text after `watch?`. A share sheet or a desktop link can
     * put another parameter first, and a share of one of those was rejected outright once a link
     * with no id stopped being handed to the resolver (2026-09-23).
     */
    @Test
    fun `the id is found wherever it sits in the query`() {
        listOf(
            "https://m.youtube.com/watch?feature=shared&v=dQw4w9WgXcQ",
            "https://www.youtube.com/watch?app=desktop&v=dQw4w9WgXcQ&t=10s",
        ).forEach { assertEquals(it, canonical, HttpUrl.of(it).canonicalWatchUrl().value) }
    }

    @Test
    fun `a parameter that merely ends in v is not the id`() {
        assertNull(HttpUrl.of("https://www.youtube.com/watch?lv=dQw4w9WgXcQ").youTubeVideoId())
    }

    @Test
    fun `a channel link is not mistaken for a video`() {
        val channel = "https://www.youtube.com/channel/UCsufaClk5if2RGqABb-09Uw"

        assertNull(HttpUrl.of(channel).youTubeVideoId())
        assertEquals(channel, HttpUrl.of(channel).canonicalWatchUrl().value)
    }
}
