package com.dewijones92.totum.video

import com.dewijones92.totum.common.HttpUrl
import com.dewijones92.totum.data.history.fake.InMemoryPlayHistoryStore
import com.dewijones92.totum.data.sponsorblock.SkipSegmentSource
import com.dewijones92.totum.domain.MediaItem
import com.dewijones92.totum.domain.MediaItemId
import com.dewijones92.totum.domain.SourceId
import com.dewijones92.totum.innertube.history.fake.FakeYouTubeWatchHistory
import com.dewijones92.totum.playback.fake.FakePlaybackController
import com.dewijones92.totum.ytdlp.ExtractionResult
import com.dewijones92.totum.ytdlp.MediaFormat
import com.dewijones92.totum.ytdlp.MediaMetadata
import com.dewijones92.totum.ytdlp.YtDlpEngine
import com.dewijones92.totum.ytdlp.fake.FakeYtDlpEngine
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

/**
 * The stream nominated for preloading must be the stream that then plays.
 *
 * They were picked in two places by two different rules, so almost every preload was thrown away:
 * report 0.1.390 counted `preloadsWasted = 12` against twelve nominations, each roughly 30 seconds
 * of 1080p held and discarded, and the trail said so on every single item —
 *
 * ```
 * 20:25:34.723 preload held a different stream of ng2Tsa5KE_A than the one that played,
 *              so the preload was wasted
 * ```
 *
 * `AppContainer.readyAgain` nominated `resolved.item.mediaUrl`, while `playVideoQuality` plays
 * `choices.qualityFrom(resolved.qualities, cap)?.videoUrl` — the quality ladder's pick, which is a
 * different URL whenever a ladder exists. 0.1.359 had already recorded the mismatch as "itag 18
 * held, itag 399 played" and it was only ever counted, never resolved.
 *
 * So the launcher answers it once, in [VideoPlaybackLauncher.urlThatWouldPlay], and the preloader
 * asks rather than guessing. This test asserts the two AGREE end to end — the nomination against
 * what the controller was actually handed — because both halves were individually defensible and
 * the pair was wrong. Exactly the failure `PreloadOnWifiOnlyTest` could not catch: it reimplemented
 * the rule as a third copy, and pinned the wrong one.
 */
class ThePreloadIsTheStreamThatPlaysTest {

    private val controller = FakePlaybackController()
    private val choices = StreamChoices()
    private var maxHeight = Int.MAX_VALUE
    private var listening = false

    /**
     * A ladder whose best entry is NOT `mediaUrl`, which is the ordinary YouTube shape: yt-dlp
     * reports a muxed default plus separate video/audio streams that merge to something better.
     */
    private class LadderEngine : YtDlpEngine by FakeYtDlpEngine() {
        override suspend fun extract(url: HttpUrl): ExtractionResult = ExtractionResult.Success(
            MediaMetadata(
                id = VIDEO_ID,
                title = "Discoveries That Confirmed Ancient Folklore",
                uploader = "A Channel",
                durationSeconds = 600,
                thumbnailUrl = null,
                formats = listOf(
                    // The muxed 360p default — what `item.mediaUrl` ends up being.
                    MediaFormat(
                        formatId = "18",
                        container = "mp4",
                        width = 640,
                        height = 360,
                        hasVideo = true,
                        hasAudio = true,
                        fileSizeBytes = 360,
                        url = MUXED,
                        videoCodec = "avc1.42001E",
                        audioCodec = "mp4a.40.2",
                    ),
                    // Video-only 1080p plus its audio partner: the pair the ladder prefers.
                    MediaFormat(
                        formatId = "399",
                        container = "mp4",
                        width = 1920,
                        height = 1080,
                        hasVideo = true,
                        hasAudio = false,
                        fileSizeBytes = 1_080,
                        url = HD_VIDEO,
                        videoCodec = "av01.0.08M.08",
                        audioCodec = null,
                    ),
                    // And a 480p rung, so a cap and a hand-picked height have somewhere to land.
                    MediaFormat(
                        formatId = "244",
                        container = "mp4",
                        width = 854,
                        height = 480,
                        hasVideo = true,
                        hasAudio = false,
                        fileSizeBytes = 480,
                        url = SD_VIDEO,
                        videoCodec = "vp09.00.20.08",
                        audioCodec = null,
                    ),
                    MediaFormat(
                        formatId = "140",
                        container = "m4a",
                        width = null,
                        height = null,
                        hasVideo = false,
                        hasAudio = true,
                        fileSizeBytes = 1_000,
                        url = AUDIO,
                        videoCodec = null,
                        audioCodec = "mp4a.40.2",
                    ),
                ),
            ),
        )
    }

    private val resolver = VideoResolver(LadderEngine(), SkipSegmentSource { emptyList() })

    private val launcher = VideoPlaybackLauncher(
        resolver,
        controller,
        FakeYouTubeWatchHistory(),
        InMemoryPlayHistoryStore(),
        preferredMaxHeight = { maxHeight },
        audioPreferred = { listening },
        choices = choices,
    )

    private val watchUrl = HttpUrl.of("https://www.youtube.com/watch?v=$VIDEO_ID")

    private val listing = MediaItem(
        id = MediaItemId(VIDEO_ID),
        sourceId = SourceId("youtube"),
        title = "Discoveries That Confirmed Ancient Folklore",
        publishedAt = null,
        duration = null,
        mediaUrl = watchUrl,
    )

    /** THE assertion. Everything else here is a way of reaching it under a different setting. */
    private suspend fun assertPreloadMatchesPlayback(setting: String) {
        val resolved = resolver.prefetch(watchUrl, listing.sourceId)
        assertNotNull("the fixture should resolve", resolved)
        val nominated = launcher.urlThatWouldPlay(resolved!!)

        launcher.play(listing, watchUrl)

        assertEquals(
            "$setting: the preload would hold a stream the player does not use",
            controller.lastItem?.mediaUrl,
            nominated,
        )
    }

    @Test
    fun `watching, the nomination is the stream that plays`() = runTest {
        assertPreloadMatchesPlayback("watching, no cap")
    }

    /**
     * The reported case, and the one that stands in for "watched failing against the old code":
     * `urlThatWouldPlay` did not exist to fail, so what is pinned instead is the **divergence** the
     * old rule produced. `resolved.item.mediaUrl` — exactly what `readyAgain` used to nominate — is
     * the muxed 360p default, and the stream that plays is the ladder's 1080p pick. Two URLs, every
     * time, which is why twelve preloads out of twelve were thrown away.
     */
    @Test
    fun `watching, the nomination is the ladder's pick rather than the muxed default`() = runTest {
        val resolved = resolver.prefetch(watchUrl, listing.sourceId)

        assertEquals("the old nomination", MUXED, resolved!!.item.mediaUrl?.value)
        assertEquals("what actually plays", HD_VIDEO, launcher.urlThatWouldPlay(resolved)?.value)
    }

    /** A cap changes which rung plays, so it has to change the nomination too. */
    @Test
    fun `a height cap moves the nomination with it`() = runTest {
        maxHeight = 480

        assertPreloadMatchesPlayback("capped at 480p")
    }

    /** And a hand-picked height, which is the other half of `qualityFrom`. */
    @Test
    fun `a chosen height moves the nomination with it`() = runTest {
        choices.chooseHeight(480)

        assertPreloadMatchesPlayback("480p chosen by hand")
    }

    /** Listening spends a fraction of the data; preloading the picture would spend it twice over. */
    @Test
    fun `listening, the nomination is the audio-only stream`() = runTest {
        listening = true

        assertPreloadMatchesPlayback("listening")
        assertEquals(AUDIO, controller.lastItem?.mediaUrl?.value)
    }

    private companion object {
        const val VIDEO_ID = "ng2Tsa5KE_A"
        const val MUXED = "https://googlevideo.test/videoplayback?itag=18"
        const val HD_VIDEO = "https://googlevideo.test/videoplayback?itag=399"
        const val SD_VIDEO = "https://googlevideo.test/videoplayback?itag=244"
        const val AUDIO = "https://googlevideo.test/videoplayback?itag=140"
    }
}
