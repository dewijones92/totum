package com.dewijones92.totum.domain

import com.dewijones92.totum.common.HttpUrl
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaysWithNoSignalTest {

    private fun item(id: String = "x", mediaUrl: String? = "https://example.com/x.mp3") = MediaItem(
        id = MediaItemId(id),
        sourceId = SourceId("feed"),
        title = id,
        publishedAt = null,
        duration = null,
        mediaUrl = mediaUrl?.let(HttpUrl::of),
    )

    private val video = PlayableItem(item(), PlayHandle.Video(HttpUrl.of("https://www.youtube.com/watch?v=dQw4w9WgXcQ")))
    private val podcast = PlayableItem(item(), PlayHandle.Podcast())

    @Test
    fun `a finished download plays with no signal, whole or audio-only, for a video and for a podcast`() {
        val whole = DownloadState.Downloaded("/data/a.mkv", audioOnly = false)
        val audio = DownloadState.Downloaded("/data/a.m4a", audioOnly = true)

        assertTrue(video.playsWithNoSignal(whole))
        assertTrue("an audio copy stands in once there is no signal", video.playsWithNoSignal(audio))
        assertTrue(podcast.playsWithNoSignal(whole))
        assertTrue(podcast.playsWithNoSignal(audio))
    }

    @Test
    fun `a handle that carries its own file plays with no signal whatever the download store says`() {
        val ownPodcast = PlayableItem(item(), PlayHandle.Podcast(localPath = "/data/own.mp3"))
        val ownVideo = PlayableItem(item(), PlayHandle.LocalVideo("/data/own.mp4"))

        assertTrue(ownPodcast.playsWithNoSignal(DownloadState.NotDownloaded))
        assertTrue(ownVideo.playsWithNoSignal(DownloadState.NotDownloaded))
        assertTrue(ownVideo.playsWithNoSignal(DownloadState.Failed("gone")))
    }

    @Test
    fun `anything not on the phone does not play with no signal`() {
        assertFalse(podcast.playsWithNoSignal(DownloadState.NotDownloaded))
        assertFalse(podcast.playsWithNoSignal(DownloadState.Downloading(downloadedBytes = 5, totalBytes = 10)))
        assertFalse(podcast.playsWithNoSignal(DownloadState.Failed("boom")))
        assertFalse(video.playsWithNoSignal(DownloadState.NotDownloaded))
    }

    @Test
    fun `a torrent that only has an audio address on the Pi does not play with no signal`() {
        val torrent = PlayableItem(
            item(id = "torrent:abc", mediaUrl = null),
            PlayHandle.Podcast(audioUrl = HttpUrl.of("https://pi.home.example/audio/abc.m3u8")),
        )

        assertFalse(torrent.playsWithNoSignal(DownloadState.NotDownloaded))
    }

    @Test
    fun `the row line, the count and the filter all agree because they ask the same question`() {
        val states = listOf(
            DownloadState.NotDownloaded,
            DownloadState.Downloading(1, 2),
            DownloadState.Failed("x"),
            DownloadState.Downloaded("/a.mp3", audioOnly = true),
        )

        states.forEach { state ->
            assertEquals(
                "state=$state",
                podcast.playsWithNoSignal(state),
                !podcast.unavailableOfflineNow(state, offline = true),
            )
            assertFalse(podcast.unavailableOfflineNow(state, offline = false))
        }
    }
}
