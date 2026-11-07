package com.dewijones92.totum.domain

import com.dewijones92.totum.common.HttpUrl
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What a queue row should admit when there is no network.
 *
 * The queue already skips these rather than spending a minute of stall budget proving each one is
 * unplayable. That is the right behaviour and completely invisible — an item silently passed over
 * looks like the app losing your place, which is why the row has to say it.
 */
class UnavailableOfflineNowTest {
    private val episode = PlayableItem(
        MediaItem(
            id = MediaItemId("episode"),
            sourceId = SourceId("feed"),
            title = "Episode",
            publishedAt = null,
            duration = null,
            mediaUrl = HttpUrl.of("https://example.com/episode.mp3"),
        ),
        PlayHandle.Podcast(),
    )

    @Test
    fun `offline and not downloaded is unavailable`() {
        assertTrue(episode.unavailableOfflineNow(DownloadState.NotDownloaded, offline = true))
    }

    /** The whole point of having downloaded it. */
    @Test
    fun `offline but downloaded is available`() {
        assertFalse(episode.unavailableOfflineNow(DownloadState.Downloaded("/data/a.mp3"), offline = true))
    }

    /**
     * A download that is still running is unavailable offline too — and will stay that way, since
     * the bytes it is waiting for cannot arrive. Saying "downloading" would be a promise the
     * device cannot keep.
     */
    @Test
    fun `offline and still downloading is unavailable`() {
        assertTrue(
            episode.unavailableOfflineNow(
                DownloadState.Downloading(downloadedBytes = 1L, totalBytes = 2L),
                offline = true
            )
        )
    }

    @Test
    fun `online, nothing is unavailable for want of a network`() {
        assertFalse(episode.unavailableOfflineNow(DownloadState.NotDownloaded, offline = false))
        assertFalse(
            episode.unavailableOfflineNow(
                DownloadState.Downloading(downloadedBytes = 1L, totalBytes = 2L),
                offline = false
            )
        )
        assertFalse(episode.unavailableOfflineNow(DownloadState.Downloaded("/data/a.mp3"), offline = false))
    }

    @Test
    fun `offline with a file of its own is available even with no download record`() {
        val own = episode.copy(handle = PlayHandle.Podcast(localPath = "/data/own.mp3"))
        val ownVideo = episode.copy(handle = PlayHandle.LocalVideo("/data/own.mp4"))

        assertFalse(own.unavailableOfflineNow(DownloadState.NotDownloaded, offline = true))
        assertFalse(ownVideo.unavailableOfflineNow(DownloadState.NotDownloaded, offline = true))
    }
}
