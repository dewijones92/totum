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
 * End to end, through the launcher: an English talk must play in English, and you must be able
 * to say otherwise.
 *
 * Report 0.1.373 is the reason. The formats below mirror what the phone saw — YouTube's HLS
 * manifest publishing the same video at the same height once per audio language, labelled only
 * by its URL — and the German dub is the TALLER stream, exactly as it was there.
 */
class AudioTrackSelectionTest {

    private val playback = FakePlaybackController()

    private class DubbedEngine : YtDlpEngine by FakeYtDlpEngine() {
        override suspend fun extract(url: HttpUrl) = ExtractionResult.Success(
            MediaMetadata(
                id = VIDEO_ID,
                title = "A talk, in English",
                uploader = null,
                durationSeconds = 2_247,
                thumbnailUrl = null,
                formats = listOf(
                    hlsMuxed("de", height = 1080, xtags = "acont=dubbed-auto:lang=de-DE"),
                    hlsMuxed("en", height = 720, xtags = "acont=original:lang=en-US"),
                    audioOnly("audio-de", language = "de-DE", languagePreference = DUB_SCORE),
                    audioOnly("audio-en", language = "en-US", languagePreference = ORIGINAL_SCORE),
                ),
            ),
        )
    }

    private fun launcher() = VideoPlaybackLauncher(
        resolver = VideoResolver(
            DubbedEngine(),
            SkipSegmentSource { emptyList() },
            preferredAudioLanguages = { listOf("en") },
        ),
        playback = playback,
        watchHistory = FakeYouTubeWatchHistory(),
        playHistory = InMemoryPlayHistoryStore(),
    )

    @Test
    fun `an English talk plays in English, not in the taller German dub`() = runTest {
        val launcher = launcher()

        launcher.play(listing, WATCH_URL)

        assertTrue(
            "played ${playback.lastItem?.mediaUrl?.value}",
            playback.lastItem?.mediaUrl?.value?.contains("lang%3Den-US") == true,
        )
    }

    @Test
    fun `both tracks are offered, the original first`() = runTest {
        val launcher = launcher()

        launcher.play(listing, WATCH_URL)

        assertEquals(listOf("en-US", "de-DE"), launcher.quality.value.audioTracks.map { it.languageCode })
    }

    @Test
    fun `choosing German plays German`() = runTest {
        val launcher = launcher()
        launcher.play(listing, WATCH_URL)

        launcher.selectAudioTrack("de-DE")

        assertTrue(
            "played ${playback.lastItem?.mediaUrl?.value}",
            playback.lastItem?.mediaUrl?.value?.contains("lang%3Dde-DE") == true,
        )
        assertEquals("de-DE", launcher.quality.value.audioLanguage)
    }

    @Test
    fun `choosing English again comes back`() = runTest {
        val launcher = launcher()
        launcher.play(listing, WATCH_URL)
        launcher.selectAudioTrack("de-DE")

        launcher.selectAudioTrack("en-US")

        assertTrue(
            "played ${playback.lastItem?.mediaUrl?.value}",
            playback.lastItem?.mediaUrl?.value?.contains("lang%3Den-US") == true,
        )
    }

    @Test
    fun `switching track while listening stays in listen mode`() = runTest {
        // The mode is situational, not per-track: choosing a language must not put the picture
        // back on when you deliberately switched to audio.
        val launcher = launcher()
        launcher.play(listing, WATCH_URL)
        launcher.listen()

        launcher.selectAudioTrack("de-DE")

        assertTrue("still listening", launcher.quality.value.listening)
        assertEquals("audio-de", playback.lastItem?.mediaUrl?.value?.substringAfterLast('/'))
    }

    @Test
    fun `switching track re-uses the extraction rather than paying for another`() = runTest {
        val engine = CountingEngine()
        val launcher = VideoPlaybackLauncher(
            resolver = VideoResolver(engine, SkipSegmentSource { emptyList() }, preferredAudioLanguages = { EN }),
            playback = playback,
            watchHistory = FakeYouTubeWatchHistory(),
            playHistory = InMemoryPlayHistoryStore(),
        )
        launcher.play(listing, WATCH_URL)

        launcher.selectAudioTrack("de-DE")
        launcher.selectAudioTrack("en-US")

        assertEquals("one extraction, three plays", 1, engine.extractions)
    }

    @Test
    fun `a track that cannot be applied leaves playback alone`() = runTest {
        // Nothing has been resolved, so there is no format list to re-pick from. The menu tap
        // must be a no-op rather than a restart of whatever happened to be playing.
        val launcher = launcher()

        launcher.selectAudioTrack("de-DE")

        assertTrue("nothing played", playback.played.isEmpty())
    }

    private class CountingEngine : YtDlpEngine by FakeYtDlpEngine() {
        var extractions = 0
            private set

        override suspend fun extract(url: HttpUrl): ExtractionResult {
            extractions++
            return DubbedEngine().extract(url)
        }
    }

    private companion object {
        const val VIDEO_ID = "87DyyMV0kCY"
        val WATCH_URL = HttpUrl.of("https://www.youtube.com/watch?v=$VIDEO_ID")
        val EN = listOf("en")

        /** yt-dlp's scores: the uploader's own track is 10, a dub is the default -1. */
        const val ORIGINAL_SCORE = 10
        const val DUB_SCORE = -1
        const val WIDESCREEN_WIDTH = 16
        const val WIDESCREEN_HEIGHT = 9

        val listing = MediaItem(
            id = MediaItemId(VIDEO_ID),
            sourceId = SourceId("youtube"),
            title = "A talk, in English",
            publishedAt = null,
            duration = null,
            mediaUrl = WATCH_URL,
        )

        /** An HLS variant, labelled only by its URL — which is what the phone actually gets. */
        fun hlsMuxed(id: String, height: Int, xtags: String) = MediaFormat(
            formatId = id,
            container = "mp4",
            width = height * WIDESCREEN_WIDTH / WIDESCREEN_HEIGHT,
            height = height,
            hasVideo = true,
            hasAudio = true,
            fileSizeBytes = null,
            url = "https://manifest.googlevideo.com/api/manifest/hls_playlist/itag/96/sgoap/" +
                "clen%3D1%3Bxtags%3D${xtags.replace("=", "%3D")}/playlist/index.m3u8",
            videoCodec = "avc1.640028",
            audioCodec = "mp4a.40.2",
        )

        fun audioOnly(id: String, language: String, languagePreference: Int) = MediaFormat(
            formatId = id,
            container = "m4a",
            width = null,
            height = null,
            hasVideo = false,
            hasAudio = true,
            fileSizeBytes = 1_000,
            url = "https://cdn.test/$id",
            audioCodec = "mp4a.40.2",
            language = language,
            languagePreference = languagePreference,
        )
    }
}
