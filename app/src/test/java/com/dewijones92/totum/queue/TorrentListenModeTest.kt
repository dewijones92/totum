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
 * Listen mode plays a torrent's audio-only stream, which is the whole point of having one.
 *
 * Dewi, 2026-08-02: *"make sure when on 'listen only' mode that it only streams the audio to my
 * device to save data"*. A torrent is ONE file carrying both tracks, so listening to one used to
 * pull the video as well — measured on the Pi at 15.2 MB/min against 2.1 for the audio alone.
 * The home server now remuxes it, and this is the switch that asks for it.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class TorrentListenModeTest {

    private val dispatcher = StandardTestDispatcher()
    private val controller = FakePlaybackController()

    private val videoUrl = HttpUrl.of("https://home.test/ts/stream/S01E01.mkv")
    private val audioUrl = HttpUrl.of("https://home.test/ts/audio/abc/7/index.m3u8")

    private fun torrent(localPath: String? = null) = PlayableItem(
        item = MediaItem(
            id = MediaItemId("torrent:abc:7"),
            sourceId = SourceId("torrent"),
            title = "S01E01",
            publishedAt = null,
            duration = null,
            mediaUrl = videoUrl,
        ),
        handle = PlayHandle.Podcast(localPath = localPath, audioUrl = audioUrl),
    )

    private val launcher = VideoPlaybackLauncher(
        VideoResolver(FakeYtDlpEngine(), SkipSegmentSource { emptyList() }),
        controller,
        FakeYouTubeWatchHistory(),
        InMemoryPlayHistoryStore(),
    )

    private fun queue(audioPreferred: Boolean) = PlaybackQueue(
        controller = controller,
        launcher = launcher,
        scope = CoroutineScope(dispatcher),
        audioPreferred = { audioPreferred },
    )

    @Test
    fun `a torrent in listen mode offers Watch, and watched it offers Listen`() = runTest(dispatcher) {
        for (listening in listOf(true, false)) {
            queue(audioPreferred = listening).playAll(listOf(torrent()))
            advanceUntilIdle()

            val quality = launcher.quality.value
            assertEquals("listening=$listening", true to listening, quality.canListen to quality.listening)
        }
    }

    @Test
    fun `a video podcast with no audio-only stream listens by switching its picture off`() = runTest(dispatcher) {
        val enclosure = HttpUrl.of("https://feeds.test/ep/12.mp4")
        val episode = PlayableItem(
            torrent().item.copy(id = MediaItemId("ep12"), mediaUrl = enclosure),
            PlayHandle.Podcast()
        )
        queue(audioPreferred = true).playAll(listOf(episode))
        advanceUntilIdle()

        assertEquals(enclosure to true, controller.lastItem?.mediaUrl to controller.lastAudioOnly)
        assertEquals(true to true, launcher.quality.value.canListen to launcher.quality.value.listening)
    }

    @Test
    fun `an audio podcast offers neither`() = runTest(dispatcher) {
        val enclosure = HttpUrl.of("https://feeds.test/ep/12.mp3")
        val episode = PlayableItem(
            torrent().item.copy(id = MediaItemId("ep12"), mediaUrl = enclosure),
            PlayHandle.Podcast()
        )
        queue(audioPreferred = true).playAll(listOf(episode))
        advanceUntilIdle()

        assertEquals(false to false, launcher.quality.value.canListen to launcher.quality.value.listening)
    }

    @Test
    fun `listen mode plays the audio-only stream`() = runTest(dispatcher) {
        val queue = queue(audioPreferred = true)
        queue.playAll(listOf(torrent()))
        advanceUntilIdle()

        assertEquals(audioUrl, controller.lastItem?.mediaUrl)
    }

    @Test
    fun `watching plays the video stream`() = runTest(dispatcher) {
        val queue = queue(audioPreferred = false)
        queue.playAll(listOf(torrent()))
        advanceUntilIdle()

        assertEquals(videoUrl, controller.lastItem?.mediaUrl)
    }

    /**
     * A downloaded copy beats both. It is already on the device, so streaming anything at all —
     * even the cheap version — would spend data to play a file you already have.
     */
    @Test
    fun `a downloaded copy wins over the audio stream`() = runTest(dispatcher) {
        val queue = queue(audioPreferred = true)
        queue.playAll(listOf(torrent(localPath = "/data/S01E01.m4a")))
        advanceUntilIdle()

        assertEquals("/data/S01E01.m4a", controller.lastLocalPath)
        assertEquals(videoUrl, controller.lastItem?.mediaUrl)
    }
}
