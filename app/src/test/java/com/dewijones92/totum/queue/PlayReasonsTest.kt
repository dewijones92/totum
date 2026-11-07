package com.dewijones92.totum.queue

import com.dewijones92.totum.common.HttpUrl
import com.dewijones92.totum.data.history.fake.InMemoryPlayHistoryStore
import com.dewijones92.totum.data.queue.QueueEntry
import com.dewijones92.totum.data.queue.QueueGroup
import com.dewijones92.totum.data.queue.fake.InMemoryQueueStore
import com.dewijones92.totum.data.sponsorblock.SkipSegmentSource
import com.dewijones92.totum.domain.ChosenWay
import com.dewijones92.totum.domain.MediaItem
import com.dewijones92.totum.domain.MediaItemId
import com.dewijones92.totum.domain.PlayHandle
import com.dewijones92.totum.domain.PlayReason
import com.dewijones92.totum.domain.PlayableItem
import com.dewijones92.totum.domain.SourceId
import com.dewijones92.totum.innertube.history.fake.FakeYouTubeWatchHistory
import com.dewijones92.totum.music.RADIO_GROUP_PREFIX
import com.dewijones92.totum.playback.fake.FakePlaybackController
import com.dewijones92.totum.uploads.NewUploads
import com.dewijones92.totum.video.VideoPlaybackLauncher
import com.dewijones92.totum.video.VideoResolver
import com.dewijones92.totum.ytdlp.fake.FakeYtDlpEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class PlayReasonsTest {

    private val dispatcher = StandardTestDispatcher()
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

    private fun podcast(id: String) = PlayableItem(
        MediaItem(
            id = MediaItemId(id),
            sourceId = SourceId("feed"),
            title = id,
            publishedAt = null,
            duration = null,
            mediaUrl = HttpUrl.of("https://feeds.example.com/$id.mp3"),
        ),
        PlayHandle.Podcast(),
    )

    private fun lastReason(id: String): PlayReason? =
        controller.playReasons.lastOrNull { it.first == MediaItemId(id) }?.second

    private fun entry(id: String, group: QueueGroup?) = QueueEntry(podcast(id), group)

    @Test
    fun `a tap on play now says you tapped it`() = runTest(dispatcher) {
        queue.playNow(podcast("a"))
        advanceUntilIdle()

        assertEquals(PlayReason.Chosen(ChosenWay.TAP), lastReason("a"))
    }

    @Test
    fun `play now says the reason the caller gave and records none when the caller gave none`() = runTest(dispatcher) {
        queue.playNow(podcast("a"), reason = PlayReason.Chosen(ChosenWay.PIN))
        queue.playNow(podcast("b"), reason = null)
        advanceUntilIdle()

        assertEquals(PlayReason.Chosen(ChosenWay.PIN), lastReason("a"))
        assertNull(lastReason("b"))
    }

    @Test
    fun `a play all starts the first item with the reason it was given`() = runTest(dispatcher) {
        queue.playAll(listOf(podcast("a"), podcast("b")), reason = PlayReason.Chosen(ChosenWay.SHUFFLE))
        advanceUntilIdle()

        assertEquals(PlayReason.Chosen(ChosenWay.SHUFFLE), lastReason("a"))
        assertNull(lastReason("b"))
    }

    @Test
    fun `a play all with no reason is a tap`() = runTest(dispatcher) {
        queue.playAll(listOf(podcast("a"), podcast("b")))
        advanceUntilIdle()

        assertEquals(PlayReason.Chosen(ChosenWay.TAP), lastReason("a"))
    }

    @Test
    fun `a peek says you peeked`() = runTest(dispatcher) {
        queue.peek(podcast("a"))
        advanceUntilIdle()

        assertEquals(PlayReason.Chosen(ChosenWay.PEEK), lastReason("a"))
    }

    @Test
    fun `jumping to a queue row says you played it from the queue`() = runTest(dispatcher) {
        queue.enqueueAll(listOf(podcast("a"), podcast("b")))
        advanceUntilIdle()

        queue.jumpTo(1)
        advanceUntilIdle()

        assertEquals(PlayReason.Chosen(ChosenWay.QUEUE_ROW), lastReason("b"))
    }

    @Test
    fun `advancing to the next item in a group names the group and outside any group names none`() =
        runTest(dispatcher) {
            val peep = QueueGroup("peep", "Peep Show")
            queue.playAll(listOf(podcast("a"), podcast("b")), peep)
            queue.enqueue(podcast("loose"))
            advanceUntilIdle()

            queue.playNextInQueue()
            advanceUntilIdle()
            assertEquals(PlayReason.Next("Peep Show"), lastReason("b"))

            queue.playNextInQueue()
            advanceUntilIdle()
            assertEquals(PlayReason.Next(null), lastReason("loose"))
        }

    @Test
    fun `going back says you went back to the one before it, with its group`() = runTest(dispatcher) {
        val peep = QueueGroup("peep", "Peep Show")
        queue.playAll(listOf(podcast("a"), podcast("b")), peep)
        advanceUntilIdle()
        queue.playNextInQueue()
        advanceUntilIdle()

        queue.playPreviousInQueue()
        advanceUntilIdle()

        assertEquals(PlayReason.Previous("Peep Show"), lastReason("a"))
    }

    @Test
    fun `arriving in the new group reads as a new upload and in a radio group as that radio`() {
        assertEquals(PlayReason.NewUpload, entry("a", NewUploads.INBOX).arrivedNext())
        assertEquals(
            PlayReason.Radio("Radio · Jazz"),
            entry("a", QueueGroup("${RADIO_GROUP_PREFIX}RDabc", "Radio · Jazz")).arrivedNext(),
        )
        assertEquals(PlayReason.Next("Peep Show"), entry("a", QueueGroup("peep", "Peep Show")).arrivedNext())
        assertEquals(PlayReason.Next(null), entry("a", null).arrivedNext())
        assertEquals(PlayReason.Previous("Peep Show"), entry("a", QueueGroup("peep", "Peep Show")).wentBackTo())
        assertEquals(PlayReason.Previous(null), entry("a", null).wentBackTo())
    }

    @Test
    fun `the reason is only ever shown for the item it was recorded for`() = runTest(dispatcher) {
        queue.playNow(podcast("a"), reason = PlayReason.Chosen(ChosenWay.LINK))
        advanceUntilIdle()
        queue.playNow(podcast("b"), reason = PlayReason.Chosen(ChosenWay.TAP))
        advanceUntilIdle()

        assertEquals(MediaItemId("b"), controller.state.value?.itemId)
        assertEquals(PlayReason.Chosen(ChosenWay.TAP), controller.state.value?.playReason)
    }
}
