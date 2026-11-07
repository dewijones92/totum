package com.dewijones92.totum.queue

import com.dewijones92.totum.common.HttpUrl
import com.dewijones92.totum.data.download.fake.FakeDownloadManager
import com.dewijones92.totum.data.history.fake.InMemoryPlayHistoryStore
import com.dewijones92.totum.data.queue.QueueEntry
import com.dewijones92.totum.data.queue.QueueGroup
import com.dewijones92.totum.data.queue.QueueSnapshot
import com.dewijones92.totum.data.queue.fake.InMemoryQueueStore
import com.dewijones92.totum.data.sponsorblock.SkipSegmentSource
import com.dewijones92.totum.domain.MediaContentKind
import com.dewijones92.totum.domain.MediaItem
import com.dewijones92.totum.domain.MediaItemId
import com.dewijones92.totum.domain.PlayHandle
import com.dewijones92.totum.domain.PlayState
import com.dewijones92.totum.domain.PlayableItem
import com.dewijones92.totum.domain.SourceId
import com.dewijones92.totum.innertube.history.fake.FakeYouTubeWatchHistory
import com.dewijones92.totum.playback.Chosen
import com.dewijones92.totum.playback.PlaybackProgressStore
import com.dewijones92.totum.playback.fake.FakePlaybackController
import com.dewijones92.totum.video.VideoPlaybackLauncher
import com.dewijones92.totum.video.VideoResolver
import com.dewijones92.totum.ytdlp.fake.FakeYtDlpEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class QueueTidyTest {

    private val dispatcher = StandardTestDispatcher()
    private val day = 86_400_000L
    private val now = 100 * day

    private class Progress : PlaybackProgressStore {
        val states = MutableStateFlow<Map<MediaItemId, PlayState>>(emptyMap())
        val savedAt = mutableMapOf<MediaItemId, Long>()

        fun played(id: String, daysAgo: Long?, now: Long, day: Long) {
            states.value += MediaItemId(id) to PlayState.Played
            if (daysAgo != null) savedAt[MediaItemId(id)] = now - daysAgo * day
        }

        override suspend fun resumePositionMs(itemId: MediaItemId): Long? = null
        override suspend fun lastSavedAtEpochMs(itemId: MediaItemId): Long? = savedAt[itemId]
        override suspend fun playState(itemId: MediaItemId): PlayState = states.value[itemId] ?: PlayState.Unplayed
        override suspend fun save(itemId: MediaItemId, positionMs: Long, durationMs: Long?, chosen: Chosen) = Unit
        override fun observeStates(): Flow<Map<MediaItemId, PlayState>> = states
        override suspend fun setPlayed(itemId: MediaItemId, played: Boolean) = Unit
    }

    private val controller = FakePlaybackController()
    private val queue = PlaybackQueue(
        controller,
        VideoPlaybackLauncher(
            VideoResolver(FakeYtDlpEngine(), SkipSegmentSource { emptyList() }),
            controller,
            FakeYouTubeWatchHistory(),
            InMemoryPlayHistoryStore(),
        ),
        CoroutineScope(dispatcher),
        InMemoryQueueStore(),
    )
    private val progress = Progress()
    private val downloads = FakeDownloadManager()
    private var locked = emptySet<MediaItemId>()
    private val undoStore = TidyUndoStore(null, { now })

    private val tidy = QueueTidy(queue, progress, downloads, { locked }, { now }, undoStore)

    private fun episode(id: String, kind: MediaContentKind = MediaContentKind.STANDARD) = PlayableItem(
        MediaItem(
            id = MediaItemId(id),
            sourceId = SourceId("feed"),
            title = "Episode $id",
            publishedAt = null,
            duration = null,
            mediaUrl = HttpUrl.of("https://feeds.example.com/$id.mp3"),
            contentKind = kind,
        ),
        PlayHandle.Podcast(),
    )

    private suspend fun TestScope.queued(vararg ids: String, group: QueueGroup? = null) {
        ids.forEach { queue.enqueue(episode(it), group) }
        advanceUntilIdle()
    }

    private fun ids() = queue.state.value.entries.map { it.item.item.id.value }

    @Test
    fun `played talk older than the age leaves the queue and the rest stays in order`() = runTest(dispatcher) {
        queued("old1", "fresh", "old2", "unplayed")
        progress.played("old1", daysAgo = 30, now, day)
        progress.played("old2", daysAgo = 15, now, day)
        progress.played("fresh", daysAgo = 3, now, day)

        val result = tidy.tidy(14, "test")
        advanceUntilIdle()

        assertEquals(QueueTidy.Result(2, 0), result)
        assertEquals(listOf("fresh", "unplayed"), ids())
        assertTrue(result.movedAnything)
    }

    @Test
    fun `an item played exactly at the cut-off leaves and one a moment newer stays`() = runTest(dispatcher) {
        queued("onTheLine", "justNewer")
        progress.played("onTheLine", daysAgo = 14, now, day)
        progress.played("justNewer", daysAgo = 14, now, day)
        progress.savedAt[MediaItemId("justNewer")] = now - 14 * day + 1

        tidy.tidy(14, "test")
        advanceUntilIdle()

        assertEquals(listOf("justNewer"), ids())
    }

    @Test
    fun `a finished item with no saved date is kept rather than guessed old`() = runTest(dispatcher) {
        queued("undated", "dated")
        progress.played("undated", daysAgo = null, now, day)
        progress.played("dated", daysAgo = 40, now, day)

        tidy.tidy(14, "test")
        advanceUntilIdle()

        assertEquals(listOf("undated"), ids())
    }

    @Test
    fun `music, locked, in progress and unplayed items are never tidied however old`() = runTest(dispatcher) {
        queue.enqueue(episode("song", MediaContentKind.MUSIC))
        queued("locked", "halfway", "never")
        progress.played("song", daysAgo = 90, now, day)
        progress.played("locked", daysAgo = 90, now, day)
        progress.states.value += MediaItemId("halfway") to PlayState.InProgress(1_000, 10_000)
        progress.savedAt[MediaItemId("halfway")] = now - 90 * day
        locked = setOf(MediaItemId("locked"))

        val result = tidy.tidy(14, "test")
        advanceUntilIdle()

        assertEquals(QueueTidy.Result(0, 0), result)
        assertFalse(result.movedAnything)
        assertEquals(listOf("song", "locked", "halfway", "never"), ids())
    }

    @Test
    fun `what is playing and the group it belongs to are kept even when finished and old`() = runTest(dispatcher) {
        val peep = QueueGroup("peep", "Peep Show")
        queued("a", "b", "c", group = peep)
        queued("outsider")
        queue.jumpTo(1)
        advanceUntilIdle()
        listOf("a", "b", "c", "outsider").forEach { progress.played(it, daysAgo = 60, now, day) }

        tidy.tidy(14, "test")
        advanceUntilIdle()

        assertEquals(listOf("a", "b", "c"), ids())
    }

    @Test
    fun `failed downloads are cleared and counted, finished ones are left`() = runTest(dispatcher) {
        queued("a")
        downloads.know(episode("bad1"))
        downloads.know(episode("bad2"))
        downloads.know(episode("good"))
        downloads.setFailed(MediaItemId("bad1"), "boom")
        downloads.setFailed(MediaItemId("bad2"), "boom")
        downloads.download(episode("good"))

        val result = tidy.tidy(14, "test")

        assertEquals(QueueTidy.Result(0, 2), result)
        assertTrue(result.movedAnything)
        assertEquals(listOf("good"), downloads.observeDownloaded().first().map { it.item.id.value })
    }

    @Test
    fun `undo puts every tidied item back in the slot it left`() = runTest(dispatcher) {
        queued("a", "b", "c", "d", "e")
        progress.played("b", daysAgo = 30, now, day)
        progress.played("d", daysAgo = 30, now, day)
        tidy.tidy(14, "test")
        advanceUntilIdle()
        assertEquals(listOf("a", "c", "e"), ids())

        val back = tidy.undo("test")
        advanceUntilIdle()

        assertEquals(2, back)
        assertEquals(listOf("a", "b", "c", "d", "e"), ids())
    }

    @Test
    fun `undo twice brings nothing back the second time`() = runTest(dispatcher) {
        queued("a", "b")
        progress.played("a", daysAgo = 30, now, day)
        tidy.tidy(14, "test")
        advanceUntilIdle()

        assertEquals(1, tidy.undo("first"))
        advanceUntilIdle()
        assertEquals(0, tidy.undo("second"))
        assertEquals(listOf("a", "b"), ids())
    }

    @Test
    fun `an item queued again since is left alone by undo and nothing is duplicated`() = runTest(dispatcher) {
        queued("a", "b")
        progress.played("a", daysAgo = 30, now, day)
        tidy.tidy(14, "test")
        advanceUntilIdle()
        queue.enqueue(episode("a"))
        advanceUntilIdle()

        tidy.undo("test")
        advanceUntilIdle()

        assertEquals(listOf("b", "a"), ids())
    }

    @Test
    fun `a run that tidies nothing does not forget an earlier batch that can still be undone`() = runTest(dispatcher) {
        queued("a", "b")
        progress.played("a", daysAgo = 30, now, day)
        tidy.tidy(14, "first")
        advanceUntilIdle()

        tidy.tidy(14, "second")

        assertEquals(1, tidy.undo("test"))
    }

    @Test
    fun `forgetting the undo means nothing comes back`() = runTest(dispatcher) {
        queued("a", "b")
        progress.played("a", daysAgo = 30, now, day)
        tidy.tidy(14, "test")
        advanceUntilIdle()

        tidy.forgetUndo("swiped away")

        assertEquals(0, tidy.undo("test"))
        assertEquals(listOf("b"), ids())
    }

    private fun entry(id: String, group: QueueGroup? = null, kind: MediaContentKind = MediaContentKind.STANDARD) =
        QueueEntry(episode(id, kind), group)

    private fun snapshot(vararg entries: QueueEntry, at: Int = -1) = QueueSnapshot(entries.toList(), at)

    @Test
    fun `each rule has its own reason and they apply in a fixed order`() {
        val g = QueueGroup("g", "G")
        val states = mapOf(
            MediaItemId("done") to PlayState.Played,
            MediaItemId("song") to PlayState.Played,
            MediaItemId("lock") to PlayState.Played,
            MediaItemId("playing") to PlayState.Played,
            MediaItemId("mate") to PlayState.Played,
        )
        val queue = snapshot(
            entry("playing", g),
            entry("mate", g),
            entry("done"),
            entry("song", kind = MediaContentKind.MUSIC),
            entry("lock"),
            entry("fresh"),
            at = 0,
        )
        val lockedIds = setOf(MediaItemId("lock"), MediaItemId("song"))
        fun reason(id: String) = tidyKeepReason(queue.entries.first { it.item.item.id.value == id }, queue, states, lockedIds)

        assertEquals(TidyKeep.PLAYING, reason("playing"))
        assertEquals(TidyKeep.PLAYING_GROUP, reason("mate"))
        assertNull(reason("done"))
        assertEquals("music wins over locked", TidyKeep.MUSIC, reason("song"))
        assertEquals(TidyKeep.LOCKED, reason("lock"))
        assertEquals(TidyKeep.NOT_FINISHED, reason("fresh"))
    }

    @Test
    fun `with nothing playing no group is treated as the playing group`() {
        val g = QueueGroup("g", "G")
        val states = mapOf(MediaItemId("a") to PlayState.Played)
        val queue = snapshot(entry("a", g), at = -1)

        assertNull(tidyKeepReason(queue.entries.single(), queue, states, emptySet()))
    }
}
