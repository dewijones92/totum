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
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A quality or an audio track picked by hand lasts longer than one video.
 *
 * Dewi, 2026-08-09: *"I want everything to be maintained going to the next video in auto play …
 * I don't want that ever to change unless I manually change it"*. Both were per-video, so every
 * auto-advance quietly went back to the automatic pick.
 *
 * The second `play()` here is the auto-advance: the queue calls exactly this when an item ends.
 */
class ChoicesSurviveTheNextVideoTest {

    private val playback = FakePlaybackController()
    private val choices = StreamChoices(deviceLanguages = { listOf("en") })

    private fun launcher(maxHeight: Int = Int.MAX_VALUE) = VideoPlaybackLauncher(
        resolver = VideoResolver(
            LadderEngine(),
            SkipSegmentSource { emptyList() },
            preferredAudioLanguages = choices::preferredAudioLanguages,
        ),
        playback = playback,
        watchHistory = FakeYouTubeWatchHistory(),
        playHistory = InMemoryPlayHistoryStore(),
        preferredMaxHeight = { maxHeight },
        choices = choices,
    )

    private fun playedHeight(): Int? =
        playback.lastItem?.mediaUrl?.value?.substringAfterLast("/h")?.substringBefore('-')?.toIntOrNull()

    private fun playedLanguage(): String? =
        playback.lastItem?.mediaUrl?.value?.substringAfterLast("lang%3D")?.takeIf { it.length <= LANG_CHARS }

    @Test
    fun `the quality you picked opens the next video too`() = runTest {
        val launcher = launcher()
        launcher.play(listing(FIRST), watch(FIRST))
        assertEquals("the automatic pick is the tallest", TALLEST, playedHeight())

        launcher.selectQuality("$MIDDLE")
        launcher.play(listing(SECOND), watch(SECOND))

        assertEquals(MIDDLE, playedHeight())
    }

    @Test
    fun `and every video after that, not just the one`() = runTest {
        val launcher = launcher()
        launcher.play(listing(FIRST), watch(FIRST))
        launcher.selectQuality("$MIDDLE")

        repeat(VIDEOS) { launcher.play(listing("v$it"), watch("v$it")) }

        assertEquals(MIDDLE, playedHeight())
    }

    @Test
    fun `the audio track you picked opens the next video too`() = runTest {
        val launcher = launcher()
        launcher.play(listing(FIRST), watch(FIRST))
        assertEquals("en-US", playedLanguage())

        launcher.selectAudioTrack("de-DE")
        launcher.play(listing(SECOND), watch(SECOND))

        assertEquals("de-DE", playedLanguage())
    }

    @Test
    fun `the two hold at the same time`() = runTest {
        val launcher = launcher()
        launcher.play(listing(FIRST), watch(FIRST))
        launcher.selectQuality("$MIDDLE")
        launcher.selectAudioTrack("de-DE")

        launcher.play(listing(SECOND), watch(SECOND))

        assertEquals(MIDDLE, playedHeight())
        assertEquals("de-DE", playedLanguage())
    }

    @Test
    fun `changing your mind replaces the choice rather than adding to it`() = runTest {
        val launcher = launcher()
        launcher.play(listing(FIRST), watch(FIRST))
        launcher.selectQuality("$MIDDLE")
        launcher.selectQuality("$TALLEST")

        launcher.play(listing(SECOND), watch(SECOND))

        assertEquals(TALLEST, playedHeight())
    }

    @Test
    fun `the network's cap still wins over the height you picked`() = runTest {
        // Data-saver is a limit, not a preference. Picking 1080p on Wi-Fi must not lift it later.
        val launcher = launcher(maxHeight = SMALLEST)
        launcher.play(listing(FIRST), watch(FIRST))
        launcher.selectQuality("$TALLEST")

        launcher.play(listing(SECOND), watch(SECOND))

        assertEquals(SMALLEST, playedHeight())
    }

    @Test
    fun `picking nothing leaves the automatic behaviour exactly as it was`() = runTest {
        val launcher = launcher()

        launcher.play(listing(FIRST), watch(FIRST))
        launcher.play(listing(SECOND), watch(SECOND))

        assertEquals(TALLEST, playedHeight())
        assertTrue("and the phone's own language", playedLanguage() == "en-US")
    }

    /** Offers the same ladder for every video, in two languages — enough to tell the picks apart. */
    private class LadderEngine : YtDlpEngine by FakeYtDlpEngine() {
        override suspend fun extract(url: HttpUrl): ExtractionResult {
            val id = url.value.substringAfterLast('=')
            return ExtractionResult.Success(
                MediaMetadata(
                    id = id,
                    title = "Video $id",
                    uploader = null,
                    durationSeconds = 600,
                    thumbnailUrl = null,
                    formats = listOf(SMALLEST, MIDDLE, TALLEST).flatMap { height ->
                        listOf(
                            hls(id, height, "acont=original:lang=en-US"),
                            hls(id, height, "acont=dubbed-auto:lang=de-DE"),
                        )
                    },
                ),
            )
        }
    }

    private fun listing(id: String) = MediaItem(
        id = MediaItemId(id),
        sourceId = SourceId("youtube"),
        title = "Video $id",
        publishedAt = null,
        duration = null,
        mediaUrl = watch(id),
    )

    private companion object {
        const val FIRST = "aaa"
        const val SECOND = "bbb"
        const val SMALLEST = 360
        const val MIDDLE = 720
        const val TALLEST = 1080
        const val VIDEOS = 5

        /** "de-DE" and "en-US" are five characters; anything longer is a URL that did not match. */
        const val LANG_CHARS = 5

        fun watch(id: String) = HttpUrl.of("https://www.youtube.com/watch?v=$id")

        /**
         * The height is in the path as `/h<height>-` so a test can read back WHICH rung played,
         * and the language is in `xtags` exactly as YouTube writes it.
         */
        fun hls(id: String, height: Int, xtags: String) = MediaFormat(
            formatId = "$height-$xtags",
            container = "mp4",
            width = height * 16 / 9,
            height = height,
            hasVideo = true,
            hasAudio = true,
            fileSizeBytes = null,
            url = "https://cdn.test/$id/h$height-x/sgoap/xtags%3D${xtags.replace("=", "%3D")}",
            videoCodec = "avc1.640028",
            audioCodec = "mp4a.40.2",
        )
    }
}
