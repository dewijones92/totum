package com.dewijones92.totum.domain

import com.dewijones92.totum.common.HttpUrl
import org.junit.Assert.assertEquals
import org.junit.Test

class FollowingTest {

    private val novara = channel("https://www.youtube.com/channel/UCnovara", "Novara Media")
    private val feed = MediaSource.PodcastFeed(
        id = SourceId(FEED_URL),
        title = "Football Daily",
        feedUrl = HttpUrl.of(FEED_URL),
    )

    @Test
    fun `a video from a subscribed channel is subscribed`() {
        val followed = FollowedSources(listOf(novara), feeds = emptyList(), signedIn = true)

        val answer = followed.of(video(sourceUrl = "https://www.youtube.com/channel/UCnovara"), MediaKind.VIDEO)

        assertEquals(Following.Subscribed(novara), answer)
    }

    @Test
    fun `the channel id matches whatever else the listing url carries`() {
        val followed = FollowedSources(listOf(novara), feeds = emptyList(), signedIn = true)

        val answer = followed.of(video(sourceUrl = "https://www.youtube.com/channel/UCnovara/videos"), MediaKind.VIDEO)

        assertEquals(Following.Subscribed(novara), answer)
    }

    @Test
    fun `a video from another channel is not subscribed and carries the channel to subscribe to`() {
        val followed = FollowedSources(listOf(novara), feeds = emptyList(), signedIn = true)
        val item = video(sourceUrl = RESTISPOLITICS, author = "The Rest Is Politics US")

        val answer = followed.of(item, MediaKind.VIDEO)

        assertEquals(
            Following.NotSubscribed(channel(RESTISPOLITICS, "The Rest Is Politics US")),
            answer,
        )
    }

    @Test
    fun `a video whose listing names no channel at all is unknown`() {
        val followed = FollowedSources(listOf(novara), feeds = emptyList(), signedIn = true)

        assertEquals(
            Following.Unknown(UnknownBecause.NO_SOURCE),
            followed.of(video(sourceUrl = null, author = " "), MediaKind.VIDEO),
        )
    }

    @Test
    fun `a video named only by a subscribed channel's name is subscribed by name`() {
        val followed = FollowedSources(listOf(novara), feeds = emptyList(), signedIn = true)

        val answer = followed.of(video(sourceUrl = null, author = " novara media "), MediaKind.VIDEO)

        assertEquals(Following.Subscribed(novara, byName = true), answer)
    }

    @Test
    fun `a video named only by another name is not subscribed, to be looked up on a tap`() {
        val followed = FollowedSources(listOf(novara), feeds = emptyList(), signedIn = true)
        val item = video(sourceUrl = null, author = "Middle East Eye")

        assertEquals(Following.NotSubscribedByName(item), followed.of(item, MediaKind.VIDEO))
    }

    @Test
    fun `a channel id outranks a name that happens to match`() {
        val followed = FollowedSources(listOf(novara), feeds = emptyList(), signedIn = true)
        val item = video(sourceUrl = "https://www.youtube.com/channel/UCimpostor", author = "Novara Media")

        assertEquals(
            Following.NotSubscribed(channel("https://www.youtube.com/channel/UCimpostor", "Novara Media")),
            followed.of(item, MediaKind.VIDEO),
        )
    }

    @Test
    fun `a name-only video is unknown while signed out or before the list loads`() {
        val item = video(sourceUrl = null, author = "Novara Media")

        assertEquals(
            Following.Unknown(UnknownBecause.SIGNED_OUT),
            FollowedSources(emptyList(), emptyList(), signedIn = false).of(item, MediaKind.VIDEO),
        )
        assertEquals(
            Following.Unknown(UnknownBecause.NOT_LOADED),
            FollowedSources(null, emptyList(), signedIn = true).of(item, MediaKind.VIDEO),
        )
    }

    @Test
    fun `signed out, no video channel can be judged`() {
        val followed = FollowedSources(channels = emptyList(), feeds = emptyList(), signedIn = false)

        val answer = followed.of(video(sourceUrl = "https://www.youtube.com/channel/UCnovara"), MediaKind.VIDEO)

        assertEquals(Following.Unknown(UnknownBecause.SIGNED_OUT), answer)
    }

    @Test
    fun `signed in before the list has loaded is unknown rather than not subscribed`() {
        val followed = FollowedSources(channels = null, feeds = emptyList(), signedIn = true)

        val answer = followed.of(video(sourceUrl = "https://www.youtube.com/channel/UCnovara"), MediaKind.VIDEO)

        assertEquals(Following.Unknown(UnknownBecause.NOT_LOADED), answer)
    }

    @Test
    fun `a handle-only channel that is not in the list cannot be judged`() {
        val followed = FollowedSources(listOf(novara), feeds = emptyList(), signedIn = true)
        val item = video(sourceUrl = "https://www.youtube.com/@middleeasteye", author = "Middle East Eye")

        assertEquals(Following.Unknown(UnknownBecause.HANDLE_ONLY), followed.of(item, MediaKind.VIDEO))
    }

    @Test
    fun `a handle-only channel whose name is subscribed is subscribed by name`() {
        val followed = FollowedSources(listOf(novara), feeds = emptyList(), signedIn = true)

        val answer = followed.of(video(sourceUrl = "https://www.youtube.com/@novaramedia"), MediaKind.VIDEO)

        assertEquals(Following.Subscribed(novara, byName = true), answer)
    }

    @Test
    fun `a handle-only channel that is in the list by the same url is subscribed`() {
        val byHandle = channel("https://www.youtube.com/@novaramedia", "Novara Media")
        val followed = FollowedSources(listOf(byHandle), feeds = emptyList(), signedIn = true)

        val answer = followed.of(video(sourceUrl = "https://www.youtube.com/@novaramedia"), MediaKind.VIDEO)

        assertEquals(Following.Subscribed(byHandle), answer)
    }

    @Test
    fun `an episode of a subscribed feed is subscribed, signed in to YouTube or not`() {
        val followed = FollowedSources(channels = null, feeds = listOf(feed), signedIn = false)

        assertEquals(Following.Subscribed(feed), followed.of(episode(FEED_URL), MediaKind.PODCAST))
    }

    @Test
    fun `an episode of another feed is not subscribed and carries the feed to subscribe to`() {
        val followed = FollowedSources(channels = null, feeds = listOf(feed), signedIn = false)
        val other = "https://example.com/other.xml"

        val answer = followed.of(episode(other, author = "Other Show"), MediaKind.PODCAST)

        assertEquals(
            Following.NotSubscribed(
                MediaSource.PodcastFeed(id = SourceId(other), title = "Other Show", feedUrl = HttpUrl.of(other)),
            ),
            answer,
        )
    }

    @Test
    fun `an episode whose source is not a feed url is unknown`() {
        val followed = FollowedSources(channels = null, feeds = listOf(feed), signedIn = false)

        assertEquals(
            Following.Unknown(UnknownBecause.NO_SOURCE),
            followed.of(episode("ytfeed:SUBSCRIPTIONS"), MediaKind.PODCAST),
        )
    }

    @Test
    fun `podcasts not loaded yet are unknown`() {
        val followed = FollowedSources(channels = null, feeds = null, signedIn = false)

        assertEquals(Following.Unknown(UnknownBecause.NOT_LOADED), followed.of(episode(FEED_URL), MediaKind.PODCAST))
    }

    @Test
    fun `the pillar passed decides which rule applies, not the item`() {
        val followed = FollowedSources(listOf(novara), feeds = listOf(feed), signedIn = true)
        val item = episode(FEED_URL).copy(sourceUrl = HttpUrl.of("https://www.youtube.com/channel/UCnovara"))

        assertEquals(Following.Subscribed(novara), followed.of(item, MediaKind.VIDEO))
        assertEquals(Following.Subscribed(feed), followed.of(item, MediaKind.PODCAST))
    }

    @Test
    fun `nothing known judges nothing`() {
        val followed = FollowedSources.NOTHING_KNOWN

        assertEquals(
            Following.Unknown(UnknownBecause.SIGNED_OUT),
            followed.of(video(sourceUrl = "https://www.youtube.com/channel/UCnovara"), MediaKind.VIDEO),
        )
        assertEquals(Following.Unknown(UnknownBecause.NOT_LOADED), followed.of(episode(FEED_URL), MediaKind.PODCAST))
    }

    private fun channel(url: String, title: String) =
        MediaSource.VideoChannel(id = SourceId(url), title = title, channelUrl = HttpUrl.of(url))

    private fun video(sourceUrl: String?, author: String = "Novara Media") = MediaItem(
        id = MediaItemId("v1"),
        sourceId = SourceId("ytfeed:SUBSCRIPTIONS"),
        title = "A video",
        publishedAt = null,
        duration = null,
        author = author,
        sourceUrl = sourceUrl?.let(HttpUrl::of),
    )

    private fun episode(sourceId: String, author: String = "Football Daily") = MediaItem(
        id = MediaItemId("e1"),
        sourceId = SourceId(sourceId),
        title = "An episode",
        publishedAt = null,
        duration = null,
        author = author,
    )

    private companion object {
        const val RESTISPOLITICS = "https://www.youtube.com/channel/UCrestispolitics"
        const val FEED_URL = "https://podcasts.example.com/football-daily.xml"
    }
}
