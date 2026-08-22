package com.dewijones92.totum.database

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.dewijones92.totum.common.HttpUrl
import com.dewijones92.totum.data.queue.QueueEntry
import com.dewijones92.totum.data.queue.QueueSnapshot
import com.dewijones92.totum.domain.MediaItem
import com.dewijones92.totum.domain.MediaItemId
import com.dewijones92.totum.domain.PlayHandle
import com.dewijones92.totum.domain.PlayableItem
import com.dewijones92.totum.domain.SourceId
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.Instant
import kotlin.time.Duration.Companion.minutes

/**
 * The view count and publication date surviving the database.
 *
 * This is where they were dying, and the failure was invisible from either end. The facts survived
 * the media session — `PlayerMetadataTest` proves that — and then the shared rebuild in
 * [playlistItemFrom] set `publishedAt = null` and there was no column for the other two. So the
 * video page showed "1.2M views · 2 days ago" for an item tapped from a feed and showed nothing at
 * all for the same item replayed from the queue, which is the ordinary case.
 *
 * Nothing can reconstruct them: a resolution knows the stream and nothing about either, which is the
 * whole reason `MediaItem.withStreamFrom` exists. Once a row is written without them they are gone.
 *
 * Every table on the `PlaylistItemColumns` contract is covered, because they share one mapper and a
 * gap in any of them is a gap in all of them. CI found this on 2026-08-07 after it passed locally on
 * timing — a reminder that a round-trip through a real datastore is its own claim.
 */
class ItemFactsSurviveStorageTest {

    private companion object {
        const val CHANNEL_URL = "https://www.youtube.com/@NovaraMedia"
    }

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val db = Room.inMemoryDatabaseBuilder(context, TotumDatabase::class.java)
        .allowMainThreadQueries()
        .build()

    private val publishedAt = Instant.parse("2026-08-01T09:00:00Z")
    private val duration = 42.minutes

    @After
    fun closeDb() = db.close()

    private fun item(id: String, withFacts: Boolean = true) = PlayableItem(
        MediaItem(
            id = MediaItemId(id),
            sourceId = SourceId("ytfeed:SUBSCRIPTIONS"),
            title = "an item with facts about it",
            publishedAt = publishedAt.takeIf { withFacts },
            publishedText = "2 days ago".takeIf { withFacts },
            duration = duration.takeIf { withFacts },
            author = "Novara Media",
            mediaUrl = HttpUrl.of("https://example.test/episode.mp3"),
            viewsText = "1.2M views".takeIf { withFacts },
            membersOnly = withFacts,
            sourceUrl = HttpUrl.of(CHANNEL_URL).takeIf { withFacts },
        ),
        PlayHandle.Podcast(),
    )

    private fun assertFactsKept(read: PlayableItem?) {
        assertEquals("the view count did not survive storage", "1.2M views", read?.item?.viewsText)
        assertEquals("the relative date did not survive storage", "2 days ago", read?.item?.publishedText)
        assertEquals("the absolute date did not survive storage", publishedAt, read?.item?.publishedAt)
        // Duration is not decoration: the Library's "Longest first" sorts on it, so losing it makes
        // that menu entry a silent no-op, and every persisted row loses its length chip.
        assertEquals("the duration did not survive storage", duration, read?.item?.duration)
        // sourceUrl is what makes "Go to channel" instant. Without it the locator falls back to a
        // full yt-dlp extraction to read one string -- 12.5s on a real phone, for a row that came
        // from the queue rather than a feed.
        assertEquals("the channel URL did not survive storage", CHANNEL_URL, read?.item?.sourceUrl?.value)
        assertEquals("members-only did not survive storage", true, read?.item?.membersOnly)
    }

    @Test
    fun theQueueKeepsAnItemsViewCountAndDate() = runBlocking {
        val store = RoomQueueStore(db.queueDao())
        store.save(QueueSnapshot(entries = listOf(QueueEntry(item("queued"))), currentIndex = 0))

        assertFactsKept(store.load().entries.firstOrNull()?.item)
    }

    @Test
    fun playHistoryKeepsAnItemsViewCountAndDate() = runBlocking {
        val store = RoomPlayHistoryStore(db.playHistoryDao())
        store.record(item("played"))

        assertFactsKept(store.observe().first().firstOrNull())
    }

    /**
     * And absence stays absence.
     *
     * The instant travels as epoch millis, and a mishandled null there does not produce a blank —
     * it produces a confident 1970, which is worse than showing nothing.
     */
    @Test
    fun anItemWithNoViewCountOrDateGetsNeitherInvented() = runBlocking {
        val store = RoomQueueStore(db.queueDao())
        store.save(QueueSnapshot(entries = listOf(QueueEntry(item("bare", withFacts = false))), currentIndex = 0))

        val read = store.load().entries.firstOrNull()?.item?.item
        assertNull("a view count was invented", read?.viewsText)
        assertNull("a relative date was invented", read?.publishedText)
        assertNull("an epoch date was invented from a null", read?.publishedAt)
        assertNull("a duration was invented", read?.duration)
        assertNull("a channel URL was invented", read?.sourceUrl)
        assertEquals("members-only was invented", false, read?.membersOnly)
    }
}
