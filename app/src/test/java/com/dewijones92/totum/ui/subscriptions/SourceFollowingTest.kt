package com.dewijones92.totum.ui.subscriptions

import com.dewijones92.totum.common.HttpUrl
import com.dewijones92.totum.data.podcast.fake.FakePodcastRepository
import com.dewijones92.totum.domain.Following
import com.dewijones92.totum.domain.MediaItem
import com.dewijones92.totum.domain.MediaItemId
import com.dewijones92.totum.domain.MediaSource
import com.dewijones92.totum.domain.SourceId
import com.dewijones92.totum.domain.Subscription
import com.dewijones92.totum.domain.UnknownBecause
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class SourceFollowingTest {

    private val channel = MediaSource.VideoChannel(
        id = SourceId(CHANNEL_URL),
        title = "Novara Media",
        channelUrl = HttpUrl.of(CHANNEL_URL),
    )
    private val feed = MediaSource.PodcastFeed(
        id = SourceId(FEED_URL),
        title = "Football Daily",
        feedUrl = HttpUrl.of(FEED_URL),
    )
    private val channelCalls = mutableListOf<Pair<MediaSource.VideoChannel, Boolean>>()
    private var channelWrites = true

    private var located: MediaSource? = null
    private val lookedUp = mutableListOf<MediaItem>()

    private fun following(podcasts: FakePodcastRepository = FakePodcastRepository()) = SourceFollowing(
        setChannel = { source, on ->
            channelCalls += source to on
            channelWrites
        },
        podcasts = podcasts,
        locate = { item ->
            lookedUp += item
            located
        },
    )

    private val nameOnly = MediaItem(
        id = MediaItemId("v1"),
        sourceId = SourceId("ytfeed:RECOMMENDED"),
        title = "Protesters around the world",
        publishedAt = null,
        duration = null,
        author = "Middle East Eye",
        mediaUrl = HttpUrl.of("https://www.youtube.com/watch?v=v1"),
    )

    @Test
    fun `a row known only by its channel name looks the channel up, then subscribes to it`() = runTest {
        located = channel

        assertTrue(following().change(Following.NotSubscribedByName(nameOnly), subscribe = true, from = "test"))

        assertEquals(listOf(nameOnly), lookedUp)
        assertEquals(listOf(channel to true), channelCalls)
    }

    @Test
    fun `a channel that cannot be looked up is not subscribed to`() = runTest {
        located = null

        assertFalse(following().change(Following.NotSubscribedByName(nameOnly), subscribe = true, from = "test"))

        assertEquals(emptyList<Any>(), channelCalls)
    }

    @Test
    fun `a known source is changed without a lookup`() = runTest {
        assertTrue(following().change(Following.Subscribed(channel, byName = true), subscribe = false, from = "test"))

        assertEquals(emptyList<Any>(), lookedUp)
        assertEquals(listOf(channel to false), channelCalls)
    }

    @Test
    fun `an unknown row changes nothing`() = runTest {
        assertFalse(
            following().change(Following.Unknown(UnknownBecause.SIGNED_OUT), subscribe = true, from = "test"),
        )

        assertEquals(emptyList<Any>(), channelCalls)
    }

    @Test
    fun `subscribing to a channel goes to the account`() = runTest {
        assertTrue(following().set(channel, subscribe = true, from = "test"))

        assertEquals(listOf(channel to true), channelCalls)
    }

    @Test
    fun `unsubscribing from a channel goes to the account`() = runTest {
        assertTrue(following().set(channel, subscribe = false, from = "test"))

        assertEquals(listOf(channel to false), channelCalls)
    }

    @Test
    fun `a channel the account refuses reports failure`() = runTest {
        channelWrites = false

        assertFalse(following().set(channel, subscribe = true, from = "test"))
    }

    @Test
    fun `subscribing to a feed stores it on the device`() = runTest {
        val podcasts = FakePodcastRepository()

        assertTrue(following(podcasts).set(feed, subscribe = true, from = "test"))

        assertEquals(listOf(feed.id), podcasts.observeSubscriptions().first().map { it.source.id })
        assertEquals(emptyList<Any>(), channelCalls)
    }

    @Test
    fun `subscribing to a feed already held still counts as subscribed`() = runTest {
        val podcasts = FakePodcastRepository(listOf(Subscription(feed, Instant.EPOCH)))

        assertTrue(following(podcasts).set(feed, subscribe = true, from = "test"))
    }

    @Test
    fun `unsubscribing from a feed removes it`() = runTest {
        val podcasts = FakePodcastRepository(listOf(Subscription(feed, Instant.EPOCH)))

        assertTrue(following(podcasts).set(feed, subscribe = false, from = "test"))

        assertEquals(emptyList<Any>(), podcasts.observeSubscriptions().first())
    }

    private companion object {
        const val CHANNEL_URL = "https://www.youtube.com/channel/UCnovara"
        const val FEED_URL = "https://podcasts.example.com/football-daily.xml"
    }
}
