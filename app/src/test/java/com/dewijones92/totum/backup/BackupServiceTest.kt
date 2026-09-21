package com.dewijones92.totum.backup

import com.dewijones92.totum.common.HttpUrl
import com.dewijones92.totum.data.backup.BackupCodec
import com.dewijones92.totum.data.backup.BackupReadResult
import com.dewijones92.totum.data.playlist.fake.InMemoryLocalPlaylistStore
import com.dewijones92.totum.data.queue.QueueEntry
import com.dewijones92.totum.data.queue.QueueSnapshot
import com.dewijones92.totum.data.queue.fake.InMemoryQueueStore
import com.dewijones92.totum.data.subscription.fake.InMemorySubscriptionStore
import com.dewijones92.totum.domain.MediaItem
import com.dewijones92.totum.domain.MediaItemId
import com.dewijones92.totum.domain.MediaSource
import com.dewijones92.totum.domain.PlayHandle
import com.dewijones92.totum.domain.PlayableItem
import com.dewijones92.totum.domain.SourceId
import com.dewijones92.totum.domain.Subscription
import com.dewijones92.totum.playback.fake.InMemoryPlaybackProgressStore
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class BackupServiceTest {

    private fun item(id: String) = MediaItem(
        id = MediaItemId(id),
        sourceId = SourceId("src"),
        title = "Item $id",
        publishedAt = null,
        duration = null,
        author = "The Rest Is Politics",
        publisher = "Goalhanger",
        mediaUrl = HttpUrl.of("https://cdn.example.com/$id.mp3"),
    )

    private fun playable(id: String) = PlayableItem(item(id), PlayHandle.Podcast())

    private fun feed(id: String) = Subscription(
        source = MediaSource.PodcastFeed(
            id = SourceId(id),
            title = "Feed $id",
            feedUrl = HttpUrl.of(id),
            websiteUrl = null,
            publisher = "Goalhanger",
        ),
        subscribedAt = Instant.ofEpochMilli(1_600_000_000_000),
    )

    private class Fixture {
        val subscriptions = InMemorySubscriptionStore()
        val playlists = InMemoryLocalPlaylistStore()
        val queue = InMemoryQueueStore()
        val progress = InMemoryPlaybackProgressStore()
        var settings = mapOf("playbackMode" to "AUDIO")

        fun service() = BackupService(
            subscriptions = subscriptions,
            playlists = playlists,
            queueStore = queue,
            progress = progress,
            settings = object : BackupService.BackupSettings {
                override fun export() = settings
                override fun restore(values: Map<String, String>) {
                    settings = values
                }
            },
            appVersion = "test",
            now = { 1_700_000_000_000 },
        )
    }

    @Test
    fun `a backup carries subscriptions, playlists, queue and progress`() = runTest {
        val from = Fixture()
        from.subscriptions.saveSource(feed("https://a.example/rss"), emptyList())
        val list = from.playlists.create("Later")
        from.playlists.addItem(list, playable("one"))
        from.queue.save(QueueSnapshot(listOf(QueueEntry(playable("two")))))
        from.progress.save(MediaItemId("three"), positionMs = 30_000, durationMs = 600_000)

        val backup = from.service().create()

        assertEquals(1, backup.subscriptions.size)
        assertEquals(listOf("Later"), backup.playlists.map { it.name })
        assertEquals(listOf("one"), backup.playlists.single().items.map { it.itemId })
        assertEquals(listOf("two"), backup.queue.map { it.itemId })
        assertEquals(listOf("three"), backup.progress.map { it.itemId })
        assertEquals("AUDIO", backup.settings["playbackMode"])
    }

    /** The whole point: a backup taken on one device rebuilds the library on another. */
    @Test
    fun `restoring onto an empty device rebuilds it`() = runTest {
        val from = Fixture()
        from.subscriptions.saveSource(feed("https://a.example/rss"), emptyList())
        val list = from.playlists.create("Later")
        from.playlists.addItem(list, playable("one"))
        from.queue.save(QueueSnapshot(listOf(QueueEntry(playable("two")))))
        val file = BackupCodec.encode(from.service().create())

        val onto = Fixture()
        val decoded = BackupCodec.decode(file) as BackupReadResult.Ok
        val summary = onto.service().restore(decoded.backup)

        assertEquals(1, summary.subscriptions)
        assertEquals(1, summary.playlists)
        assertEquals(
            listOf("Feed https://a.example/rss"),
            onto.subscriptions.observeSubscriptions().first().map {
                it.source.title
            }
        )
        assertEquals(listOf("Later"), onto.playlists.observePlaylists().first().map { it.name })
        assertEquals(listOf("two"), onto.queue.load().entries.map { it.item.item.id.value })
        assertEquals(mapOf("playbackMode" to "AUDIO"), onto.settings)
    }

    /**
     * A restored row must carry BOTH names, because nothing can put them back: a podcast enclosure
     * is never re-resolved and a restored queue row is never synced from its feed again. The backup
     * file was the SIXTH place an item is persisted and the only one the v22 publisher change
     * missed, so an export/restore silently dropped the network's name for ever — while the five
     * database tables kept it, which is the "same item reads differently depending which list you
     * reached it from" defect that change exists to fix.
     */
    @Test
    fun `a restored queue row keeps the show AND its publisher`() = runTest {
        val from = Fixture()
        from.queue.save(QueueSnapshot(listOf(QueueEntry(playable("ep1")))))
        val file = BackupCodec.encode(from.service().create())

        val onto = Fixture()
        onto.service().restore((BackupCodec.decode(file) as BackupReadResult.Ok).backup)

        val restored = onto.queue.load().entries.single().item.item
        assertEquals("The Rest Is Politics", restored.author)
        assertEquals("Goalhanger", restored.publisher)
    }

    /**
     * Additive, not destructive: restoring the wrong file must never remove a library.
     * This is the property that makes restore safe to try.
     */
    /**
     * A restored FEED keeps its publisher too, not just the items.
     *
     * It heals on the next refresh, unlike a queue row — but until then the show's own page would
     * name the show and nothing else, which is the gap this whole change exists to close.
     */
    @Test
    fun `a restored subscription keeps its publisher`() = runTest {
        val from = Fixture()
        from.subscriptions.saveSource(feed("https://a.example/rss"), emptyList())
        val file = BackupCodec.encode(from.service().create())

        val onto = Fixture()
        onto.service().restore((BackupCodec.decode(file) as BackupReadResult.Ok).backup)

        val source = onto.subscriptions.observeSubscriptions().first().single().source
        assertEquals("Goalhanger", (source as MediaSource.PodcastFeed).publisher)
    }

    @Test
    fun `restoring never removes what is already there`() = runTest {
        val onto = Fixture()
        onto.subscriptions.saveSource(feed("https://mine.example/rss"), emptyList())
        onto.playlists.create("Mine")

        val from = Fixture()
        from.subscriptions.saveSource(feed("https://theirs.example/rss"), emptyList())
        from.playlists.create("Theirs")
        onto.service().restore(from.service().create())

        val subs = onto.subscriptions.observeSubscriptions().first().map { it.source.id.value }
        assertTrue("kept mine: $subs", "https://mine.example/rss" in subs)
        assertTrue("added theirs: $subs", "https://theirs.example/rss" in subs)
        assertEquals(
            setOf("Mine", "Theirs"),
            onto.playlists.observePlaylists().first().mapTo(mutableSetOf()) { it.name }
        )
    }

    /** A subscription already present is not duplicated, so restoring twice is harmless. */
    @Test
    fun `restoring the same backup twice changes nothing the second time`() = runTest {
        val from = Fixture()
        from.subscriptions.saveSource(feed("https://a.example/rss"), emptyList())
        from.playlists.create("Later")
        val backup = from.service().create()

        val onto = Fixture()
        onto.service().restore(backup)
        val second = onto.service().restore(backup)

        assertEquals(0, second.subscriptions)
        assertEquals(0, second.playlists)
        assertEquals(1, onto.subscriptions.observeSubscriptions().first().size)
        assertEquals(1, onto.playlists.observePlaylists().first().size)
    }

    /** An order cannot be merged, so an empty backup queue leaves the current one alone. */
    @Test
    fun `a backup with no queue does not clear the current one`() = runTest {
        val onto = Fixture()
        onto.queue.save(QueueSnapshot(listOf(QueueEntry(playable("mine")))))

        onto.service().restore(Fixture().service().create())

        assertEquals(listOf("mine"), onto.queue.load().entries.map { it.item.item.id.value })
    }
}
