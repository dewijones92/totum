package com.dewijones92.totum.data.channel

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.Instant

class ChannelFeedParserTest {

    private val realFeed = checkNotNull(javaClass.getResource("/youtube-channel-feed.xml")).readText()

    @Test
    fun `reads the newest upload of a real channel feed`() {
        val latest = checkNotNull(ChannelFeedParser.latest(realFeed))

        assertEquals("giTmBaNGaHw", latest.id.value)
        assertEquals(Instant.parse("2026-09-23T13:00:20Z"), latest.publishedAt)
        assertEquals("What the Labs Kept Secret: The German Wiki & RubyGems Hacks - Computerphile", latest.title)
        assertEquals("Computerphile", latest.author)
        assertEquals("https://www.youtube.com/watch?v=giTmBaNGaHw", latest.mediaUrl?.value)
        assertEquals("https://i4.ytimg.com/vi/giTmBaNGaHw/hqdefault.jpg", latest.thumbnailUrl?.value)
        assertEquals("https://www.youtube.com/channel/UC9-y-6csu5WGm29I7JiwpnA", latest.sourceUrl?.value)
    }

    @Test
    fun `every entry is read, not just the first`() {
        assertEquals(3, ChannelFeedParser.uploads(realFeed)?.size)
    }

    @Test
    fun `the newest is chosen by date, not by position`() {
        val reordered = realFeed.replace("2026-09-23T13:00:20+00:00", "2020-01-01T00:00:00+00:00")

        assertEquals(false, ChannelFeedParser.latest(reordered)?.id?.value == "giTmBaNGaHw")
    }

    @Test
    fun `a channel that has never uploaded has no latest, and is not an error`() {
        val empty = """<feed xmlns="http://www.w3.org/2005/Atom"><title>Quiet</title></feed>"""

        assertEquals(emptyList<Any>(), ChannelFeedParser.uploads(empty))
        assertNull(ChannelFeedParser.latest(empty))
    }

    @Test
    fun `an error page is not a feed`() {
        assertNull(ChannelFeedParser.uploads("<html><body>404</body></html>"))
        assertNull(ChannelFeedParser.uploads("not xml"))
    }
}
