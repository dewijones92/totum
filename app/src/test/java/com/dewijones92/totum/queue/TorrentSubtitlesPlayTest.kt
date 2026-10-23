package com.dewijones92.totum.queue

import com.dewijones92.totum.common.HttpUrl
import com.dewijones92.totum.data.history.fake.InMemoryPlayHistoryStore
import com.dewijones92.totum.data.sponsorblock.SkipSegmentSource
import com.dewijones92.totum.data.torrent.TorrentFile
import com.dewijones92.totum.data.torrent.TorrentSidecarSubtitles
import com.dewijones92.totum.data.torrent.fake.FakeHomeTorrentServer
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
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class TorrentSubtitlesPlayTest {

    private val dispatcher = StandardTestDispatcher()
    private val controller = FakePlaybackController()
    private val server = FakeHomeTorrentServer().apply {
        files = listOf(
            TorrentFile(1, "Show.S01/Subs/Show.S01E01/2_English.srt", 60_000),
            TorrentFile(7, "Show.S01/Show.S01E01.mp4", 900_000_000),
        )
    }
    private val subtitles = TorrentSidecarSubtitles(server)

    private fun episode() = PlayableItem(
        item = MediaItem(
            id = MediaItemId("torrent:abc123:7"),
            sourceId = SourceId("torrent"),
            title = "S01E01",
            publishedAt = null,
            duration = null,
            mediaUrl = HttpUrl.of("https://home.test/ts/stream/Show.S01E01.mp4"),
        ),
        handle = PlayHandle.Podcast(audioUrl = HttpUrl.of("https://home.test/ts/audio/abc123/7/index.m3u8")),
    )

    private fun queue(audioPreferred: Boolean) = PlaybackQueue(
        controller = controller,
        launcher = VideoPlaybackLauncher(
            VideoResolver(FakeYtDlpEngine(), SkipSegmentSource { emptyList() }),
            controller,
            FakeYouTubeWatchHistory(),
            InMemoryPlayHistoryStore(),
            sidecarSubtitles = { subtitles.forItem(it) },
        ),
        scope = CoroutineScope(dispatcher),
        audioPreferred = { audioPreferred },
    )

    @Test
    fun `a torrent episode starts with the English subtitle file from its Subs folder`() = runTest(dispatcher) {
        queue(audioPreferred = false).playAll(listOf(episode()))
        advanceUntilIdle()

        val offered = controller.state.value?.subtitles.orEmpty()
        assertEquals(listOf("en"), offered.map { it.languageCode })
        assertTrue(offered.single().url.value.contains("2_English"))
    }

    @Test
    fun `a YouTube or podcast item asks the torrent server nothing`() = runTest(dispatcher) {
        val other = episode().let { it.copy(item = it.item.copy(id = MediaItemId("feed:ep1"))) }

        assertTrue(subtitles.forItem(other.item).isEmpty())
        assertTrue(server.prepared.isEmpty())
    }
}
