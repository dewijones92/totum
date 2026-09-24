package com.dewijones92.totum.domain

import com.dewijones92.totum.common.HttpUrl
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SourceArtworkTest {

    private val showArt = HttpUrl.of("https://img.example.com/show.jpg")
    private val avatar = HttpUrl.of("https://yt3.example.com/avatar.jpg")
    private val show = MediaSource.PodcastFeed(
        SourceId("https://feeds.example.com/show.rss"),
        "The Show",
        HttpUrl.of("https://feeds.example.com/show.rss"),
        artworkUrl = showArt,
    )
    private val channel = MediaSource.VideoChannel(
        SourceId("channel"),
        "A Channel",
        HttpUrl.of("https://www.youtube.com/channel/UCaaaaaaaaaaaaaaaaaaaaaa"),
        artworkUrl = avatar,
    )
    private val bare = MediaSource.PodcastFeed(SourceId("bare"), "Bare", HttpUrl.of("https://feeds.example.com/b.rss"))

    private fun episode(sourceId: SourceId, thumbnail: HttpUrl? = null) = MediaItem(
        id = MediaItemId("ep"),
        sourceId = sourceId,
        title = "Ep",
        publishedAt = null,
        duration = null,
        thumbnailUrl = thumbnail,
    )

    @Test
    fun `both pillars contribute their artwork and a source without any is left out`() {
        assertEquals(mapOf(show.id to showArt, channel.id to avatar), listOf(show, channel, bare).artworkById())
    }

    @Test
    fun `an item saved before its show had artwork borrows the show's`() {
        assertEquals(showArt, episode(show.id).withArtworkFrom(listOf(show).artworkById()).thumbnailUrl)
    }

    @Test
    fun `an item's own picture is never replaced`() {
        val own = HttpUrl.of("https://img.example.com/own.jpg")

        assertEquals(own, episode(show.id, own).withArtworkFrom(listOf(show).artworkById()).thumbnailUrl)
    }

    @Test
    fun `an item whose source has no artwork stays without`() {
        assertNull(episode(bare.id).withArtworkFrom(listOf(show).artworkById()).thumbnailUrl)
    }

    @Test
    fun `a source knows its pillar`() {
        assertEquals(MediaKind.PODCAST, show.pillar)
        assertEquals(MediaKind.VIDEO, channel.pillar)
    }
}
