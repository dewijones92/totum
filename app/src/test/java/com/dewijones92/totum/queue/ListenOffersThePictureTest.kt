package com.dewijones92.totum.queue

import com.dewijones92.totum.common.HttpUrl
import com.dewijones92.totum.data.history.fake.InMemoryPlayHistoryStore
import com.dewijones92.totum.data.sponsorblock.SkipSegmentSource
import com.dewijones92.totum.domain.MediaContentKind
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
import org.junit.Assert.assertNull
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ListenOffersThePictureTest {

    private val dispatcher = StandardTestDispatcher()
    private val controller = FakePlaybackController()

    private fun item(id: String, url: String, kind: MediaContentKind = MediaContentKind.STANDARD) = MediaItem(
        id = MediaItemId(id),
        sourceId = SourceId("feed"),
        title = "item $id",
        publishedAt = null,
        duration = null,
        mediaUrl = HttpUrl.of(url),
        contentKind = kind,
    )

    private val torrent =
        PlayableItem(item("torrent:abc:7", "https://home.test/ts/stream/S01E01.mkv"), PlayHandle.Podcast())
    private val episode = PlayableItem(item("ep1", "https://feeds.test/ep1.mp3"), PlayHandle.Podcast())
    private val song = PlayableItem(
        item("song1", "https://feeds.test/song1.mkv", MediaContentKind.MUSIC),
        PlayHandle.Podcast(),
    )

    private fun queue(listening: Boolean) = PlaybackQueue(
        controller = controller,
        launcher = VideoPlaybackLauncher(
            VideoResolver(FakeYtDlpEngine(), SkipSegmentSource { emptyList() }),
            controller,
            FakeYouTubeWatchHistory(),
            InMemoryPlayHistoryStore(),
        ),
        scope = CoroutineScope(dispatcher),
        audioPreferred = { listening },
    )

    @Test
    fun `starting something with a picture while listening offers it`() = runTest(dispatcher) {
        val queue = queue(listening = true)

        queue.playNow(torrent)
        advanceUntilIdle()

        assertEquals(torrent.item.id, queue.pictureOffers.offer.value?.item?.item?.id)
        queue.pictureOffers.dismiss("keep listening")
        assertNull(queue.pictureOffers.offer.value)
    }

    @Test
    fun `a whole list started while listening offers the first item's picture`() = runTest(dispatcher) {
        val queue = queue(listening = true)

        queue.playAll(listOf(torrent, episode))
        advanceUntilIdle()

        assertEquals(torrent.item.id, queue.pictureOffers.offer.value?.item?.item?.id)
    }

    @Test
    fun `nothing is offered for sound, for music, when watching, or when the app moved on itself`() =
        runTest(dispatcher) {
            val listening = queue(listening = true)
            listening.playNow(episode)
            advanceUntilIdle()
            assertNull("no picture", listening.pictureOffers.offer.value)

            listening.playNow(song)
            advanceUntilIdle()
            assertNull("music", listening.pictureOffers.offer.value)

            listening.playNow(torrent, byYou = false)
            advanceUntilIdle()
            assertNull("not started by you", listening.pictureOffers.offer.value)

            val watching = queue(listening = false)
            watching.playNow(torrent)
            advanceUntilIdle()
            assertNull("already watching", watching.pictureOffers.offer.value)
        }
}
