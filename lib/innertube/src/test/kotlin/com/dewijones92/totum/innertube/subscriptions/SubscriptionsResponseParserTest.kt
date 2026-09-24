package com.dewijones92.totum.innertube.subscriptions

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SubscriptionsResponseParserTest {

    private fun fixture(): String =
        checkNotNull(javaClass.getResourceAsStream("/fechannels_tv_sample.json")) { "fixture missing" }
            .bufferedReader().readText()

    @Test
    fun `extracts channels from the real TV renderer shape, deduped and filtered`() {
        val result = SubscriptionsResponseParser.parse(fixture())

        val channels = (result as SubscriptionsResult.Success).channels
        // Alpha (deduped from 2), Beta, Gamma, and the titleless one — the video tile is excluded.
        assertEquals(listOf("UCaaa111", "UCbbb222", "UCccc333", "UCnotitle"), channels.map { it.channelId })
    }

    @Test
    fun `maps id, title, canonical url and best avatar`() {
        val alpha = (SubscriptionsResponseParser.parse(fixture()) as SubscriptionsResult.Success)
            .channels.first { it.channelId == "UCaaa111" }

        assertEquals("Alpha Channel", alpha.title)
        assertEquals("https://www.youtube.com/channel/UCaaa111", alpha.channelUrl.value)
        assertEquals("https://yt3.example/UCaaa111=s176", alpha.avatarUrl?.value)
    }

    @Test
    fun `a protocol-relative avatar, which is what YouTube actually sends, is kept`() {
        val json = """
            {"contents":[{"tileRenderer":{"contentType":"TILE_CONTENT_TYPE_CHANNEL",
              "onSelectCommand":{"browseEndpoint":{"browseId":"UCrel"}},
              "metadata":{"tileMetadataRenderer":{"title":{"simpleText":"Relative"}}},
              "header":{"tileHeaderRenderer":{"thumbnail":{"thumbnails":[{"url":"//yt3.ggpht.com/rel=s176"}]}}}}}]}
        """.trimIndent()

        val channel = (SubscriptionsResponseParser.parse(json) as SubscriptionsResult.Success).channels.single()

        assertEquals("https://yt3.ggpht.com/rel=s176", channel.avatarUrl?.value)
    }

    @Test
    fun `a channel with no title falls back to its id and tolerates no avatar`() {
        val titleless = (SubscriptionsResponseParser.parse(fixture()) as SubscriptionsResult.Success)
            .channels.first { it.channelId == "UCnotitle" }

        assertEquals("UCnotitle", titleless.title)
        assertNull(titleless.avatarUrl)
    }

    @Test
    fun `an empty logged-out envelope yields an empty list, not a failure`() {
        val result = SubscriptionsResponseParser.parse(
            """{"responseContext":{},"trackingParams":"x"}""",
        )

        assertEquals(emptyList<SubscribedChannel>(), (result as SubscriptionsResult.Success).channels)
    }

    @Test
    fun `unparseable json is a failure value`() {
        assertTrue(SubscriptionsResponseParser.parse("not json") is SubscriptionsResult.Failure)
    }
}
