package com.dewijones92.totum.data.rss

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

class RssParserTest {

    private val parser = RssParser()

    private fun parseSampleFeed(): ParsedFeed {
        val xml = checkNotNull(javaClass.getResource("/sample-feed.xml")).readText()
        return (parser.parse(xml) as RssParseResult.Success).feed
    }

    @Test
    fun `parses channel metadata`() {
        val feed = parseSampleFeed()
        assertEquals("The Test Podcast", feed.title)
        assertEquals("A podcast used in tests.", feed.description)
        assertEquals("https://podcast.example.com", feed.websiteUrl)
        assertEquals(3, feed.episodes.size)
    }

    /**
     * The channel's own `itunes:author` — the network behind the show, and a different fact from its
     * title. It was not read at all, so the one place most feeds state their publisher was thrown
     * away before anything downstream could show it.
     */
    @Test
    fun `reads the channel's author, which is the publisher the episodes share`() {
        val xml = """
            <rss version="2.0" xmlns:itunes="http://www.itunes.com/dtds/podcast-1.0.dtd">
              <channel><title>The Rest Is Politics</title>
                <itunes:author>Goalhanger</itunes:author>
                <item><title>Ep 214</title></item>
              </channel>
            </rss>
        """.trimIndent()

        assertEquals("Goalhanger", (RssParser().parse(xml) as RssParseResult.Success).feed.author)
    }

    /** A feed that names no author says null, rather than borrowing its own title. */
    @Test
    fun `a channel with no author is null`() {
        assertNull(parseSampleFeed().author)
    }

    @Test
    fun `parses a fully specified episode`() {
        val episode = parseSampleFeed().episodes[0]
        assertEquals("A Guest Author", episode.author)
        assertEquals("ep-2-guid", episode.guid)
        assertEquals("Episode two: the reckoning", episode.title)
        assertEquals("https://cdn.example.com/ep2.mp3", episode.enclosureUrl)
        assertEquals(Instant.parse("2026-07-10T06:00:00Z"), episode.publishedAt)
        assertEquals(1.hours + 2.minutes + 3.seconds, episode.duration)
        assertEquals("https://podcast.example.com/ep2.jpg", episode.imageUrl)
    }

    @Test
    fun `parses minute-second durations and offset dates`() {
        val episode = parseSampleFeed().episodes[1]
        assertNull(episode.guid)
        assertEquals(42.minutes + 30.seconds, episode.duration)
        assertEquals(Instant.parse("2026-07-01T06:00:00Z"), episode.publishedAt)
    }

    @Test
    fun `broken dates and durations degrade to null, not failure`() {
        val episode = parseSampleFeed().episodes[2]
        assertNull(episode.publishedAt)
        assertNull(episode.duration)
        assertNull(episode.enclosureUrl)
    }

    @Test
    fun `rejects non-xml input`() {
        assertTrue(parser.parse("this is not xml <<<") is RssParseResult.Failure)
    }

    @Test
    fun `rejects xml that is not rss`() {
        assertTrue(parser.parse("""<?xml version="1.0"?><atomish/>""") is RssParseResult.Failure)
    }

    @Test
    fun `rejects a feed without a channel title`() {
        val xml = """<?xml version="1.0"?><rss version="2.0"><channel><item/></channel></rss>"""
        assertTrue(parser.parse(xml) is RssParseResult.Failure)
    }

    @Test
    fun `parses inline Podlove Simple Chapters, allowing a zero start`() {
        val xml = """
            <?xml version="1.0"?>
            <rss version="2.0">
              <channel>
                <title>Chaptered</title>
                <item>
                  <title>Episode with chapters</title>
                  <psc:chapters version="1.2">
                    <psc:chapter start="00:00:00" title="Intro"/>
                    <psc:chapter start="00:01:30.500" title="Topic one"/>
                    <psc:chapter start="12:05" title="Wrap up"/>
                    <psc:chapter title="no start, dropped"/>
                  </psc:chapters>
                </item>
              </channel>
            </rss>
        """.trimIndent()

        val chapters = (parser.parse(xml) as RssParseResult.Success).feed.episodes.single().chapters

        assertEquals(3, chapters.size)
        assertEquals(0.seconds, chapters[0].start)
        assertEquals("Intro", chapters[0].title)
        assertEquals(1.minutes + 30.seconds + 500.milliseconds, chapters[1].start)
        assertEquals(12.minutes + 5.seconds, chapters[2].start)
    }

    @Test
    fun `an episode with no chapters has an empty chapter list`() {
        assertTrue(parseSampleFeed().episodes[0].chapters.isEmpty())
    }

    @Test
    fun `reads the Podcasting 2_0 remote chapters URL`() {
        val xml = """
            <?xml version="1.0"?>
            <rss version="2.0"><channel><title>Remote</title>
              <item>
                <title>Ep</title>
                <podcast:chapters url="https://chapters.example.com/ep1.json" type="application/json+chapters"/>
              </item>
            </channel></rss>
        """.trimIndent()

        val episode = (parser.parse(xml) as RssParseResult.Success).feed.episodes.single()

        assertEquals("https://chapters.example.com/ep1.json", episode.chaptersUrl)
        assertTrue(episode.chapters.isEmpty())
    }

    @Test
    fun `rejects doctype declarations (XXE hardening)`() {
        val xml = """
            <?xml version="1.0"?>
            <!DOCTYPE rss [<!ENTITY xxe SYSTEM "file:///etc/passwd">]>
            <rss version="2.0"><channel><title>&xxe;</title></channel></rss>
        """.trimIndent()
        assertTrue(parser.parse(xml) is RssParseResult.Failure)
    }
}
