package com.dewijones92.totum.data.podcast

import com.dewijones92.totum.common.Breadcrumbs
import com.dewijones92.totum.common.HttpUrl
import com.dewijones92.totum.data.net.FetchResult
import com.dewijones92.totum.data.subscription.SubscriptionStore
import com.dewijones92.totum.domain.MediaItem
import com.dewijones92.totum.domain.MediaSource
import com.dewijones92.totum.domain.SourceId
import com.dewijones92.totum.domain.Subscription
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

class DefaultPodcastRepositoryTest {

    private val feedUrl = HttpUrl.of("https://podcast.example.com/feed.xml")
    private val store = InMemoryPodcastStore()
    private val now = Instant.parse("2026-07-12T10:00:00Z")

    private fun repository(fetchResult: FetchResult) = DefaultPodcastRepository(
        fetcher = { fetchResult },
        store = store,
        clock = Clock.fixed(now, ZoneOffset.UTC),
    )

    @Test
    fun `subscribe stores feed and episodes`() = runTest {
        val xml = checkNotNull(javaClass.getResource("/sample-feed.xml")).readText()
        val result = repository(FetchResult.Success(xml)).subscribe(feedUrl)

        val subscribed = result as SubscribeResult.Subscribed
        assertEquals("The Test Podcast", subscribed.source.title)
        assertEquals(feedUrl, subscribed.source.feedUrl)

        val stored = checkNotNull(store.saved)
        assertEquals(now, stored.first.subscribedAt)
        assertEquals(3, stored.second.size)
        assertEquals("ep-2-guid", stored.second[0].id.value)
        // No guid -> enclosure URL becomes the stable id.
        assertEquals("https://cdn.example.com/ep1.mp3", stored.second[1].id.value)
        // No guid or enclosure -> positional fallback.
        assertEquals("${feedUrl.value}#2", stored.second[2].id.value)
        assertEquals("https://cdn.example.com/ep2.mp3", stored.second[0].mediaUrl?.value)
        // The SHOW owns the maker line on every episode, whether or not the episode names an
        // author of its own; the episode's author becomes the publisher beside it. This read
        // `author ?: feedTitle`, so episode 0 said "A Guest Author" and the show's name appeared
        // nowhere at all.
        assertEquals("The Test Podcast", stored.second[0].author)
        assertEquals("A Guest Author", stored.second[0].publisher)
        assertEquals("The Test Podcast", stored.second[1].author)
        assertNull(stored.second[1].publisher)
    }

    /**
     * A REFRESH teaches an existing subscription what its feed now says — the publisher especially.
     *
     * This was the bug the emulator found and the code did not show: `toMediaSource` was reached
     * only by `subscribe`, and refresh re-saved the source it had read from storage, so every
     * episode row carried "BBC Radio 5 Live" while `podcast_feeds.publisher` stayed NULL for ever
     * and the show's own page had nothing to name. Read off the stored source, which is the thing
     * the screen reads.
     */
    @Test
    fun `a refresh gives an existing subscription its publisher`() = runTest {
        val xml = feed(channelExtras = "<itunes:author>Goalhanger</itunes:author>", episodeExtras = "")
        // Subscribed BEFORE the publisher was a thing, exactly like a row upgraded to v22.
        store.saveSource(
            Subscription(
                source = MediaSource.PodcastFeed(
                    id = SourceId(feedUrl.value),
                    title = "The Rest Is Politics",
                    feedUrl = feedUrl,
                    websiteUrl = null,
                ),
                subscribedAt = now,
            ),
            emptyList(),
        )

        repository(FetchResult.Success(xml)).refresh()

        val source = checkNotNull(store.saved).first.source as MediaSource.PodcastFeed
        assertEquals("Goalhanger", source.publisher)
        assertEquals("the original subscribe date must survive a refresh", now, store.saved!!.first.subscribedAt)
    }

    @Test
    fun `the channel's author is the publisher every episode shares`() = runTest {
        val xml = feed(
            channelExtras = "<itunes:author>Goalhanger</itunes:author>",
            episodeExtras = "",
        )

        repository(FetchResult.Success(xml)).subscribe(feedUrl)

        // Most feeds name the network once, on the channel, and never per episode — so without
        // this fallback the publisher would be absent from exactly the feeds that state it.
        val episode = checkNotNull(store.saved).second.single()
        assertEquals("The Rest Is Politics", episode.author)
        assertEquals("Goalhanger", episode.publisher)
    }

    @Test
    fun `an episode's own author beats the channel's`() = runTest {
        val xml = feed(
            channelExtras = "<itunes:author>Goalhanger</itunes:author>",
            episodeExtras = "<itunes:author>A Guest Author</itunes:author>",
        )

        repository(FetchResult.Success(xml)).subscribe(feedUrl)

        val episode = checkNotNull(store.saved).second.single()
        assertEquals("The Rest Is Politics", episode.author)
        assertEquals("A Guest Author", episode.publisher)
    }

    /**
     * The diagnostics line has to say which of the two "no publisher" cases happened, because that
     * is the whole reason it exists — and its first version got exactly this case backwards. A feed
     * naming the show again on EVERY episode was reported as "the feed named no publisher", since
     * the reason was reconstructed from the channel-level author instead of recorded per episode.
     */
    @Test
    fun `the log says a publisher was dropped as a repeat, not that none was given`() = runTest {
        Breadcrumbs.clear()
        val xml = feed(channelExtras = "", episodeExtras = "<itunes:author>The Rest Is Politics</itunes:author>")

        repository(FetchResult.Success(xml)).subscribe(feedUrl)

        val line = Breadcrumbs.snapshot().map { it.message }.single { it.startsWith("names ") }
        assertTrue("should count the repeat: $line", line.contains("droppedAsRepeatOfTheShow=1"))
        assertTrue("and show nothing: $line", line.contains("shown=0"))
        assertTrue("and not claim nobody was named: $line", line.contains("named-nobody=0"))
    }

    /** The other case, so the two are actually distinguishable in a report rather than just worded. */
    @Test
    fun `the log says nobody was named when the feed named nobody`() = runTest {
        Breadcrumbs.clear()
        val xml = feed(channelExtras = "", episodeExtras = "")

        repository(FetchResult.Success(xml)).subscribe(feedUrl)

        val line = Breadcrumbs.snapshot().map { it.message }.single { it.startsWith("names ") }
        assertTrue("should count the silence: $line", line.contains("named-nobody=1"))
        assertTrue("and no repeat: $line", line.contains("droppedAsRepeatOfTheShow=0"))
    }

    @Test
    fun `a publisher that merely repeats the show is dropped`() = runTest {
        // What most feeds actually do: `itunes:author` set to the show's own title. Two identical
        // lines under a title say less than one, so there is no publisher to show here.
        val xml = feed(
            channelExtras = "<itunes:author>the rest is POLITICS</itunes:author>",
            episodeExtras = "",
        )

        repository(FetchResult.Success(xml)).subscribe(feedUrl)

        assertNull(checkNotNull(store.saved).second.single().publisher)
    }

    private fun feed(channelExtras: String, episodeExtras: String) = """
        <rss version="2.0" xmlns:itunes="http://www.itunes.com/dtds/podcast-1.0.dtd">
          <channel>
            <title>The Rest Is Politics</title>
            $channelExtras
            <item>
              <title>Ep 214</title><guid>ep214</guid>
              $episodeExtras
              <enclosure url="https://cdn.example.com/ep214.mp3"/>
            </item>
          </channel>
        </rss>
    """.trimIndent()

    @Test
    fun `an episode with no picture of its own wears the show's artwork`() = runTest {
        val xml = feed(
            channelExtras = """<itunes:image href="https://img.example.com/show.jpg"/>""",
            episodeExtras = "",
        )

        repository(FetchResult.Success(xml)).subscribe(feedUrl)

        val (subscription, items) = checkNotNull(store.saved)
        assertEquals("https://img.example.com/show.jpg", subscription.source.artworkUrl?.value)
        assertEquals("https://img.example.com/show.jpg", items.single().thumbnailUrl?.value)
    }

    @Test
    fun `an episode's own picture beats the show's`() = runTest {
        val xml = feed(
            channelExtras = """<itunes:image href="https://img.example.com/show.jpg"/>""",
            episodeExtras = """<itunes:image href="https://img.example.com/ep214.jpg"/>""",
        )

        repository(FetchResult.Success(xml)).subscribe(feedUrl)

        assertEquals("https://img.example.com/ep214.jpg", checkNotNull(store.saved).second.single().thumbnailUrl?.value)
    }

    @Test
    fun `a refresh gives an existing subscription its artwork`() = runTest {
        store.saveSource(
            Subscription(MediaSource.PodcastFeed(SourceId(feedUrl.value), "The Rest Is Politics", feedUrl), now),
            emptyList(),
        )
        val xml = feed(
            channelExtras = """<itunes:image href="https://img.example.com/show.jpg"/>""",
            episodeExtras = "",
        )

        repository(FetchResult.Success(xml)).refresh()

        assertEquals("https://img.example.com/show.jpg", checkNotNull(store.saved).first.source.artworkUrl?.value)
    }

    @Test
    fun `a preview reads the feed without subscribing to it`() = runTest {
        val xml = feed(
            channelExtras = """<itunes:image href="https://img.example.com/show.jpg"/>""",
            episodeExtras = "",
        )

        val preview = repository(FetchResult.Success(xml)).preview(feedUrl) as PreviewResult.Loaded

        assertEquals("The Rest Is Politics", preview.source.title)
        assertEquals("https://img.example.com/show.jpg", preview.source.artworkUrl?.value)
        assertEquals(listOf("Ep 214"), preview.episodes.map { it.title })
        assertNull("a preview must store nothing", store.saved)
    }

    @Test
    fun `a preview of an unreachable feed says why`() = runTest {
        val preview = repository(FetchResult.Failure("offline")).preview(feedUrl)

        assertEquals(PreviewResult.Failed("offline"), preview)
    }

    @Test
    fun `fetches remote Podcasting 2_0 chapters for an episode that links them`() = runTest {
        val chaptersUrl = "https://chapters.example.com/ep.json"
        val feedXml = """
            <rss version="2.0"><channel><title>Chaptered</title>
              <item><title>Ep</title><guid>ep1</guid>
                <enclosure url="https://cdn.example.com/ep.mp3"/>
                <podcast:chapters url="$chaptersUrl" type="application/json+chapters"/>
              </item>
            </channel></rss>
        """.trimIndent()
        val chaptersJson = """{"chapters":[{"startTime":0,"title":"Intro"},{"startTime":30,"title":"Main"}]}"""
        val repository = DefaultPodcastRepository(
            fetcher = { url ->
                FetchResult.Success(if (url.value == chaptersUrl) chaptersJson else feedXml)
            },
            store = store,
            clock = Clock.fixed(now, ZoneOffset.UTC),
        )

        repository.subscribe(feedUrl)

        val episode = checkNotNull(store.saved).second.single()
        assertEquals(2, episode.chapters.size)
        assertEquals("Intro", episode.chapters[0].title)
        assertEquals("Main", episode.chapters[1].title)
    }

    @Test
    fun `subscribing twice reports AlreadySubscribed`() = runTest {
        val xml = checkNotNull(javaClass.getResource("/sample-feed.xml")).readText()
        val repository = repository(FetchResult.Success(xml))

        repository.subscribe(feedUrl)
        assertEquals(
            SubscribeResult.AlreadySubscribed(SourceId(feedUrl.value)),
            repository.subscribe(feedUrl),
        )
    }

    @Test
    fun `network failure is reported as a value`() = runTest {
        val result = repository(FetchResult.Failure("HTTP 503")).subscribe(feedUrl)
        assertEquals(SubscribeResult.Failure.Network("HTTP 503"), result)
    }

    @Test
    fun `unparseable body is reported as InvalidFeed`() = runTest {
        val result = repository(FetchResult.Success("not a feed")).subscribe(feedUrl)
        assertTrue(result is SubscribeResult.Failure.InvalidFeed)
    }

    @Test
    fun `unsubscribe removes the feed from the store`() = runTest {
        val xml = checkNotNull(javaClass.getResource("/sample-feed.xml")).readText()
        val repository = repository(FetchResult.Success(xml))
        repository.subscribe(feedUrl)

        repository.unsubscribe(SourceId(feedUrl.value))
        assertTrue(store.removed.contains(SourceId(feedUrl.value)))
    }
}

private class InMemoryPodcastStore : SubscriptionStore {
    var saved: Pair<Subscription, List<MediaItem>>? = null
    val removed = mutableListOf<SourceId>()

    private val subscriptions = MutableStateFlow<List<Subscription>>(emptyList())
    private val episodes = MutableStateFlow<List<MediaItem>>(emptyList())

    override fun observeSubscriptions(): Flow<List<Subscription>> = subscriptions
    override fun observeItems(): Flow<List<MediaItem>> = episodes
    override suspend fun contains(id: SourceId): Boolean =
        subscriptions.value.any { it.source.id == id }

    override suspend fun saveSource(subscription: Subscription, items: List<MediaItem>) {
        saved = subscription to items
        // REPLACES by source id, as `@Upsert` does in the real store. It appended, so saving the
        // same feed twice left two rows sharing one id — a fake that cannot fail the way the thing
        // it stands in for fails. Nothing depended on the old behaviour, but a second `refresh()`
        // in any future test would silently have refreshed the same feed twice.
        subscriptions.value = subscriptions.value.filterNot { it.source.id == subscription.source.id } +
            subscription
    }

    override suspend fun removeSource(id: SourceId) {
        removed += id
        subscriptions.value = subscriptions.value.filterNot { it.source.id == id }
    }
}
