package com.dewijones92.totum.playback

import com.dewijones92.totum.common.HttpUrl
import com.dewijones92.totum.domain.MediaItem
import com.dewijones92.totum.domain.MediaItemId
import com.dewijones92.totum.domain.PlayHandle
import com.dewijones92.totum.domain.PlayableItem
import com.dewijones92.totum.domain.SourceId
import com.dewijones92.totum.playback.fake.FakePlaybackController
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Which items are worth spending bytes on ahead of time, and when.
 *
 * Dewi, 2026-08-02: *"defo yes on wifi, but maybe not on mobile please"*. Thirty seconds is flat in
 * TIME and roughly eight times apart in BYTES across the pillars — ~0.5MB for a podcast enclosure,
 * ~8MB for 1080p video — so getting the gate wrong is not a style question, it is somebody's bill.
 *
 * The rule under test is the one `AppContainer.preloadBytesOf` applies. It is duplicated here as a
 * pure function rather than reaching into the container, because the container needs a real Android
 * context; what matters is that the DECISION is pinned, and it is short enough that a divergence
 * would be obvious in review.
 */
class PreloadOnWifiOnlyTest {

    private val controller = FakePlaybackController()

    private fun preload(item: PlayableItem, metered: Boolean) {
        if (metered) return
        val url = when (val handle = item.handle) {
            is PlayHandle.Podcast -> if (handle.localPath != null) null else handle.audioUrl ?: item.item.mediaUrl
            is PlayHandle.LocalVideo -> null
            is PlayHandle.Video -> null
        } ?: return
        controller.preloadNext(MediaItemId("nominated"), url)
    }

    private val stream = HttpUrl.of("https://example.test/episode.mp3")
    private val audioOnly = HttpUrl.of("https://home.test/ts/audio/abc/7/index.m3u8")

    private fun item(handle: PlayHandle, mediaUrl: HttpUrl? = stream) = PlayableItem(
        item = MediaItem(
            id = MediaItemId("episode"),
            sourceId = SourceId("test"),
            title = "episode",
            publishedAt = null,
            duration = null,
            mediaUrl = mediaUrl,
        ),
        handle = handle,
    )

    @Test
    fun `on wifi, the next item's stream is preloaded`() {
        preload(item(PlayHandle.Podcast()), metered = false)

        assertEquals(listOf(stream), controller.preloaded)
    }

    /** The whole point of the gate. */
    @Test
    fun `on mobile data, nothing is preloaded`() {
        preload(item(PlayHandle.Podcast()), metered = true)

        assertEquals(emptyList<HttpUrl>(), controller.preloaded)
    }

    /**
     * A torrent's audio-only stream wins over its video URL. It is the cheap one — 2.1 MB/min
     * against 15.2 — so preloading the other would spend eight times the data to prepare something
     * listen mode will not play.
     */
    @Test
    fun `a torrent preloads its audio-only stream, not the video`() {
        preload(item(PlayHandle.Podcast(audioUrl = audioOnly)), metered = false)

        assertEquals(listOf(audioOnly), controller.preloaded)
    }

    /** Already on the device: preloading it would spend data to fetch a file we have. */
    @Test
    fun `a downloaded item is never preloaded`() {
        preload(item(PlayHandle.Podcast(localPath = "/data/episode.mp3")), metered = false)

        assertEquals(emptyList<HttpUrl>(), controller.preloaded)
    }

    /**
     * A video has no URL yet — it is resolved just-in-time, and the resolution is what the
     * readiness half already warms. Nominating its watch URL would preload a web page.
     */
    @Test
    fun `a video is not preloaded, because its stream URL is not known yet`() {
        val watch = HttpUrl.of("https://youtube.test/watch?v=aaaaaaaaaaa")
        preload(item(PlayHandle.Video(watch), mediaUrl = null), metered = false)

        assertEquals(emptyList<HttpUrl>(), controller.preloaded)
    }

    /**
     * Which stream a RESOLVED video nominates is deliberately not tested here any more.
     *
     * It used to be, as a third hand-written copy of the rule — and the copy pinned the wrong
     * answer. It asserted the nomination was `resolved.item.mediaUrl`, while the launcher plays the
     * quality ladder's pick, so every one of these tests passed while the app threw away every
     * preload it made (`preloadsWasted = 12` of 12, report 0.1.390). Reimplementing a rule in a test
     * cannot catch the rule being wrong; it can only make being wrong feel covered.
     *
     * `VideoPlaybackLauncher.urlThatWouldPlay` is now the single answer, and
     * `ThePreloadIsTheStreamThatPlaysTest` asserts the nomination against what the controller is
     * actually handed — end to end, through the real resolver and launcher.
     */
    @Test
    fun `an item with no URL at all is not preloaded`() {
        preload(item(PlayHandle.Podcast(), mediaUrl = null), metered = false)

        assertEquals(emptyList<HttpUrl>(), controller.preloaded)
    }
}
