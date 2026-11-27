package com.dewijones92.totum.queue

import com.dewijones92.totum.common.HttpUrl
import com.dewijones92.totum.data.history.fake.InMemoryPlayHistoryStore
import com.dewijones92.totum.data.queue.QueueGroup
import com.dewijones92.totum.data.sponsorblock.SkipSegmentSource
import com.dewijones92.totum.domain.IPlayer
import com.dewijones92.totum.domain.MediaItem
import com.dewijones92.totum.domain.MediaItemId
import com.dewijones92.totum.domain.PlayHandle
import com.dewijones92.totum.domain.PlayableItem
import com.dewijones92.totum.domain.SourceId
import com.dewijones92.totum.domain.toPlayableOrNull
import com.dewijones92.totum.innertube.history.fake.FakeYouTubeWatchHistory
import com.dewijones92.totum.playback.fake.FakePlaybackController
import com.dewijones92.totum.video.VideoPlaybackLauncher
import com.dewijones92.totum.video.VideoResolver
import com.dewijones92.totum.ytdlp.fake.FakeYtDlpEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class AnIPlayerEpisodeOpensElsewhereAndNeverQueuesTest {

    private val dispatcher = StandardTestDispatcher()
    private val controller = FakePlaybackController()
    private val opened = mutableListOf<Pair<String, HttpUrl>>()
    private var canOpen = true
    private val markedPlayed = mutableListOf<MediaItemId>()

    private fun queue() = PlaybackQueue(
        controller = controller,
        launcher = VideoPlaybackLauncher(
            VideoResolver(FakeYtDlpEngine(), SkipSegmentSource { emptyList() }),
            controller,
            FakeYouTubeWatchHistory(),
            InMemoryPlayHistoryStore(),
        ),
        scope = CoroutineScope(dispatcher),
        openExternally = { item, url ->
            opened += item.item.title to url
            canOpen
        },
        markPlayed = { markedPlayed += it },
    )

    private fun episode(pid: String, title: String): PlayableItem {
        val item = MediaItem(
            id = MediaItemId("bbc:$pid"),
            sourceId = SourceId("https://ibl.api.bbci.co.uk/ibl/v1/programmes/b006mkw3/episodes"),
            title = title,
            publishedAt = null,
            duration = null,
            mediaUrl = IPlayer.episodeUrl(pid),
        )
        return checkNotNull(item.toPlayableOrNull())
    }

    private val one = episode("m0033v6x", "Series 72: Episode 6")
    private val two = episode("m0033jsv", "Series 72: Episode 5")

    @Test
    fun `tapping an iPlayer episode hands its link to the other app and starts no playback`() = runTest(dispatcher) {
        val queue = queue()

        val played = queue.playNow(one)
        advanceUntilIdle()

        assertTrue(played)
        assertEquals(listOf("Series 72: Episode 6" to IPlayer.episodeUrl("m0033v6x")), opened)
        assertTrue(queue.state.value.entries.isEmpty())
        assertEquals(null, queue.nowPlaying.value)
    }

    @Test
    fun `a tap that nothing can open says it did not play`() = runTest(dispatcher) {
        canOpen = false

        assertFalse(queue().playNow(one))
    }

    @Test
    fun `an automatic play never opens another app`() = runTest(dispatcher) {
        queue().playNow(one, byYou = false)
        advanceUntilIdle()

        assertTrue(opened.isEmpty())
    }

    @Test
    fun `peek and play all open it too, and play all opens only the one asked for`() = runTest(dispatcher) {
        val queue = queue()

        queue.peek(one)
        queue.playAll(listOf(one, two), startAt = two.item.id)
        advanceUntilIdle()

        assertEquals(listOf("Series 72: Episode 6", "Series 72: Episode 5"), opened.map { it.first })
        assertTrue(queue.state.value.entries.isEmpty())
    }

    @Test
    fun `none of the ways to add to the queue accept an iPlayer episode`() = runTest(dispatcher) {
        val queue = queue()
        val group = QueueGroup("g", "A group")

        assertFalse(queue.enqueue(one))
        assertFalse(queue.playNext(one))
        queue.enqueueAll(listOf(one, two))
        queue.playNextAll(listOf(one, two))
        assertEquals(0, queue.addToGroup(group, listOf(one, two), byYou = false))
        advanceUntilIdle()

        assertTrue(queue.state.value.entries.isEmpty())
        assertTrue(opened.isEmpty())
    }

    @Test
    fun `a mixed run keeps the playable items and leaves the iPlayer ones out`() = runTest(dispatcher) {
        val queue = queue()
        val podcast = PlayableItem(
            one.item.copy(
                id = MediaItemId("pod-1"),
                title = "A podcast",
                mediaUrl = HttpUrl.of("https://x.test/a.mp3")
            ),
            PlayHandle.Podcast(),
        )

        queue.enqueueAll(listOf(one, podcast, two))

        assertEquals(listOf("pod-1"), queue.state.value.entries.map { it.item.item.id.value })
    }

    @Test
    fun `a successful hand-off marks the episode played through the progress seam`() = runTest(dispatcher) {
        val queue = queue()

        queue.playNow(one)
        queue.peek(two)
        advanceUntilIdle()

        assertEquals(listOf(one.item.id, two.item.id), markedPlayed)
    }

    @Test
    fun `a failed hand-off does not mark the episode played`() = runTest(dispatcher) {
        canOpen = false

        queue().playNow(one)
        advanceUntilIdle()

        assertTrue(markedPlayed.isEmpty())
    }

    @Test
    fun `an automatic play opens nothing and marks nothing`() = runTest(dispatcher) {
        queue().playNow(one, byYou = false)
        advanceUntilIdle()

        assertTrue(markedPlayed.isEmpty())
    }
}
