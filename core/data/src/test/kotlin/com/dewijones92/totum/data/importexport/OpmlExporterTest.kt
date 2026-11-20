package com.dewijones92.totum.data.importexport

import com.dewijones92.totum.common.HttpUrl
import com.dewijones92.totum.data.bbc.BbcProgrammes
import com.dewijones92.totum.domain.MediaSource
import com.dewijones92.totum.domain.SourceId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class OpmlExporterTest {

    private val exporter = OpmlExporter()

    private fun podcast(title: String, feed: String, website: String? = null) = MediaSource.PodcastFeed(
        id = SourceId(feed),
        title = title,
        feedUrl = HttpUrl.of(feed),
        websiteUrl = website?.let(HttpUrl::of),
    )

    private fun channel(title: String, url: String) = MediaSource.VideoChannel(
        id = SourceId(url),
        title = title,
        channelUrl = HttpUrl.of(url),
    )

    @Test
    fun `podcast exports as an rss outline with its feed URL`() {
        val opml = exporter.export(
            listOf(podcast("A Show", "https://feeds.example.com/a.xml", "https://a.example.com"))
        )

        assertTrue(opml.contains("xmlUrl=\"https://feeds.example.com/a.xml\""))
        assertTrue(opml.contains("htmlUrl=\"https://a.example.com\""))
        assertTrue(opml.contains("text=\"A Show\""))
    }

    @Test
    fun `a BBC programme is left out because it is not an rss feed other apps could read`() {
        val opml = exporter.export(
            listOf(
                podcast("A Show", "https://feeds.example.com/a.xml"),
                podcast("Have I Got News for You", BbcProgrammes.feedUrl("b006mkw3").value),
            ),
        )

        assertTrue(opml.contains("text=\"A Show\""))
        assertTrue(opml, !opml.contains("Have I Got News"))
        assertTrue(opml, !opml.contains("ibl.api.bbci.co.uk"))
    }

    @Test
    fun `channel exports as its uploads-feed URL so it round-trips`() {
        val opml = exporter.export(
            listOf(channel("A Chan", "https://www.youtube.com/channel/UCaaaaaaaaaaaaaaaaaaaaaa"))
        )

        assertTrue(
            opml.contains("xmlUrl=\"https://www.youtube.com/feeds/videos.xml?channel_id=UCaaaaaaaaaaaaaaaaaaaaaa\""),
        )
    }

    @Test
    fun `special characters in titles are XML-escaped`() {
        val opml = exporter.export(listOf(podcast("Tom & \"Jerry\" <fun>", "https://feeds.example.com/tj.xml")))

        assertTrue(opml.contains("Tom &amp; &quot;Jerry&quot; &lt;fun&gt;"))
    }

    @Test
    fun `exported OPML round-trips back through the parser`() {
        val sources = listOf(
            podcast("A Show", "https://feeds.example.com/a.xml"),
            channel("A Chan", "https://www.youtube.com/channel/UCaaaaaaaaaaaaaaaaaaaaaa"),
        )

        val reparsed = (SubscriptionImportParser().parse(exporter.export(sources)) as ImportParseResult.Success).sources

        assertEquals(2, reparsed.size)
        assertEquals("A Show", (reparsed[0] as ImportedSource.Podcast).title)
        val chan = reparsed[1] as ImportedSource.YouTubeChannel
        assertEquals("https://www.youtube.com/channel/UCaaaaaaaaaaaaaaaaaaaaaa", chan.channelUrl.value)
    }

    @Test
    fun `an empty subscription list still produces valid OPML`() {
        val opml = exporter.export(emptyList())

        assertTrue(opml.contains("<opml version=\"2.0\">"))
        assertTrue(opml.contains("<body>"))
    }
}
