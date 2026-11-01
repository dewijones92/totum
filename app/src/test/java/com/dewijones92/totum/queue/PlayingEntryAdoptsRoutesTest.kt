package com.dewijones92.totum.queue

import com.dewijones92.totum.common.HttpUrl
import com.dewijones92.totum.data.history.fake.InMemoryPlayHistoryStore
import com.dewijones92.totum.data.sponsorblock.SkipSegmentSource
import com.dewijones92.totum.domain.MediaItem
import com.dewijones92.totum.domain.MediaItemId
import com.dewijones92.totum.domain.PlayHandle
import com.dewijones92.totum.domain.PlayableItem
import com.dewijones92.totum.domain.SourceId
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
import org.junit.Test

/**
 * The entry that is PLAYING must pick up a better route when its item is queued again.
 *
 * Every other entry gets removed and re-added, so it naturally takes the fresh handle. The playing
 * one is deliberately exempt — moving it would interrupt playback — which meant it alone kept the
 * route it was created with for as long as it stayed current.
 *
 * Found on the emulator, 2026-08-02, and invisible from anywhere else: the torrent being listened
 * to went on pulling the video while the episodes either side of it in the same run had the
 * audio-only URL. The database told the story — one row's handle null, its neighbours' correct.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PlayingEntryAdoptsRoutesTest {

    private val dispatcher = StandardTestDispatcher()
    private val controller = FakePlaybackController()

    private val videoUrl = HttpUrl.of("https://home.test/ts/stream/S01E01.mkv")
    private val audioUrl = HttpUrl.of("https://home.test/ts/audio/abc/7/index.m3u8")

    private fun torrent(handle: PlayHandle) = PlayableItem(
        item = MediaItem(
            id = MediaItemId("torrent:abc:7"),
            sourceId = SourceId("torrent"),
            title = "S01E01",
            publishedAt = null,
            duration = null,
            mediaUrl = videoUrl,
        ),
        handle = handle,
    )

    private fun queue() = PlaybackQueue(
        controller = controller,
        launcher = VideoPlaybackLauncher(
            VideoResolver(FakeYtDlpEngine(), SkipSegmentSource { emptyList() }),
            controller,
            FakeYouTubeWatchHistory(),
            InMemoryPlayHistoryStore(),
        ),
        scope = CoroutineScope(dispatcher),
        audioPreferred = { true },
    )

    private fun currentHandle(queue: PlaybackQueue) = queue.state.value.current?.item?.handle

    @Test
    fun `the playing entry adopts an audio route it did not have`() = runTest(dispatcher) {
        val queue = queue()
        queue.playAll(listOf(torrent(PlayHandle.Podcast())))
        advanceUntilIdle()

        queue.playAll(listOf(torrent(PlayHandle.Podcast(audioUrl = audioUrl))))
        advanceUntilIdle()

        assertEquals(PlayHandle.Podcast(audioUrl = audioUrl), currentHandle(queue))
    }

    /**
     * And it keeps what it already had. Adopting must not become "the newest handle wins", which
     * would drop the downloaded file and put the item back on the network.
     */
    @Test
    fun `adopting a route does not cost it the downloaded file`() = runTest(dispatcher) {
        val queue = queue()
        queue.playAll(listOf(torrent(PlayHandle.Podcast(localPath = "/data/S01E01.m4a"))))
        advanceUntilIdle()

        queue.playAll(listOf(torrent(PlayHandle.Podcast(audioUrl = audioUrl))))
        advanceUntilIdle()

        assertEquals(
            PlayHandle.Podcast(localPath = "/data/S01E01.m4a", audioUrl = audioUrl),
            currentHandle(queue),
        )
    }

    /**
     * The entry must still be the ONE playing entry afterwards. Refreshing it replaces it with an
     * equal-but-not-identical copy, so the "don't remove what is current" guard stops recognising
     * it unless that guard is tested against the refreshed snapshot — and the queue ends up with
     * two copies, the cursor on the wrong one.
     */
    @Test
    fun `re-queueing the playing item leaves exactly one copy of it`() = runTest(dispatcher) {
        val queue = queue()
        queue.playAll(listOf(torrent(PlayHandle.Podcast())))
        advanceUntilIdle()

        queue.playAll(listOf(torrent(PlayHandle.Podcast(audioUrl = audioUrl))))
        advanceUntilIdle()

        val entries = queue.state.value.entries
        assertEquals(1, entries.count { it.item.item.id == MediaItemId("torrent:abc:7") })
        assertEquals(MediaItemId("torrent:abc:7"), queue.state.value.current?.item?.item?.id)
    }

    @Test
    fun `an entry queued again keeps the sound-only route it had`() = runTest(dispatcher) {
        val queue = queue()
        val other = torrent(
            PlayHandle.Podcast()
        ).let { it.copy(item = it.item.copy(id = MediaItemId("torrent:abc:8"))) }
        queue.playAll(listOf(other, torrent(PlayHandle.Podcast(audioUrl = audioUrl))))
        advanceUntilIdle()

        queue.playNext(torrent(PlayHandle.Podcast()))
        queue.enqueue(torrent(PlayHandle.Podcast()))

        val again = queue.state.value.entries.single { it.item.item.id == MediaItemId("torrent:abc:7") }
        assertEquals(PlayHandle.Podcast(audioUrl = audioUrl), again.item.handle)
    }
}
