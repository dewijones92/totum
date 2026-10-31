package com.dewijones92.totum.video

import com.dewijones92.totum.common.HttpUrl
import com.dewijones92.totum.innertube.player.PlayableFormat
import com.dewijones92.totum.innertube.player.StreamingData
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Building the shared quality ladder from a `/player` response.
 *
 * This exists because yt-dlp has deprecated extraction without a JavaScript runtime and
 * Chaquopy cannot provide one, so on a phone it silently loses formats — one 360p stream for
 * a video YouTube will happily serve at 1080p.
 */
class PlayerStreamsFallbackTest {

    private fun video(itag: Int, height: Int, bitrate: Long, url: String?) =
        PlayableFormat(itag, "video/mp4; codecs=\"avc1.4d401f\"", height, bitrate, url?.let(HttpUrl::parse))

    private fun audio(itag: Int, bitrate: Long) =
        PlayableFormat(itag, "audio/mp4; codecs=\"mp4a.40.2\"", null, bitrate, HttpUrl.of("https://x.test/a$itag"))

    @Test
    fun `pairs each video height with the best audio`() {
        val streaming = StreamingData(
            listOf(
                video(137, 1080, 1_864_000, "https://x.test/v1080"),
                video(136, 720, 496_000, "https://x.test/v720"),
                audio(140, 129_000),
                audio(139, 48_000),
            ),
        )

        val qualities = streaming.videoQualities()

        assertEquals(listOf(1080, 720), qualities.map { it.height })
        // Highest-bitrate audio, and the same one for every height — it is merged at play time.
        assertEquals(listOf("https://x.test/a140", "https://x.test/a140"), qualities.map { it.audioUrl?.value })
    }

    @Test
    fun `formats with no URL are left out — they are the ones we cannot fetch`() {
        val streaming = StreamingData(
            listOf(
                video(137, 1080, 1_864_000, url = null),
                video(18, 360, 312_000, "https://x.test/v360"),
                audio(140, 129_000),
            ),
        )

        assertEquals(listOf(360), streaming.videoQualities().map { it.height })
        assertEquals(1080, streaming.bestOfferedHeight)
        assertEquals(360, streaming.bestReachableHeight)
    }

    @Test
    fun `video-only with no audio anywhere is not offered, since it cannot be played`() {
        val streaming = StreamingData(listOf(video(137, 1080, 1_864_000, "https://x.test/v1080")))

        assertEquals(emptyList<Int>(), streaming.videoQualities().map { it.height })
    }

    @Test
    fun `a muxed stream needs no separate audio`() {
        val muxed = PlayableFormat(
            18,
            "video/mp4; codecs=\"avc1.42001E, mp4a.40.2\"",
            360,
            312_000,
            HttpUrl.of("https://x.test/muxed"),
        )

        val qualities = StreamingData(listOf(muxed)).videoQualities()

        assertEquals(listOf(360), qualities.map { it.height })
        assertEquals(listOf<String?>(null), qualities.map { it.audioUrl?.value })
    }
}
