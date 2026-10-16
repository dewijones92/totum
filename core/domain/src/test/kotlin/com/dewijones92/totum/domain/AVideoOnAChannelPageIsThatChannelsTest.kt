package com.dewijones92.totum.domain

import com.dewijones92.totum.common.HttpUrl
import org.junit.Assert.assertEquals
import org.junit.Test

class AVideoOnAChannelPageIsThatChannelsTest {

    private val channel = MediaSource.VideoChannel(
        id = SourceId(CHANNEL_URL),
        title = "3Blue1Brown",
        channelUrl = HttpUrl.of(CHANNEL_URL),
    )

    @Test
    fun `a video the channel grid lists without its channel is followed as that channel's`() {
        val followed = FollowedSources(listOf(channel), feeds = emptyList(), signedIn = true)
        val listed = video(author = null, sourceUrl = null).listedOn(channel)

        assertEquals("3Blue1Brown", listed.author)
        assertEquals(Following.Subscribed(channel), followed.of(listed, MediaKind.VIDEO))
    }

    @Test
    fun `a video that names its own channel keeps it`() {
        val other = "https://www.youtube.com/channel/UCpolymath"
        val listed = video(author = "3Blue1Brown and PolyaMath", sourceUrl = other).listedOn(channel)

        assertEquals("3Blue1Brown and PolyaMath", listed.author)
        assertEquals(HttpUrl.of(other), listed.sourceUrl)
    }

    @Test
    fun `a row naming another channel's link is not given this channel's name`() {
        val other = "https://www.youtube.com/channel/UCpolymath"
        val listed = video(author = null, sourceUrl = other).listedOn(channel)

        assertEquals(null, listed.author)
        assertEquals(HttpUrl.of(other), listed.sourceUrl)
    }

    private fun video(author: String?, sourceUrl: String?) = MediaItem(
        id = MediaItemId("v1"),
        sourceId = SourceId(CHANNEL_URL),
        title = "A video",
        publishedAt = null,
        duration = null,
        author = author,
        sourceUrl = sourceUrl?.let(HttpUrl::of),
    )

    private companion object {
        const val CHANNEL_URL = "https://www.youtube.com/channel/UCYO_jab_esuFRV4b17AJtAw"
    }
}
