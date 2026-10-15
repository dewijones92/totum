package com.dewijones92.totum.video

import com.dewijones92.totum.common.HttpUrl
import com.dewijones92.totum.data.sponsorblock.SkipSegmentSource
import com.dewijones92.totum.domain.SourceId
import com.dewijones92.totum.innertube.player.PlayableFormat
import com.dewijones92.totum.innertube.player.PlayerDetails
import com.dewijones92.totum.innertube.player.PlayerResult
import com.dewijones92.totum.innertube.player.StreamingData
import com.dewijones92.totum.ytdlp.ExtractionResult
import com.dewijones92.totum.ytdlp.MediaFormat
import com.dewijones92.totum.ytdlp.MediaMetadata
import com.dewijones92.totum.ytdlp.YtDlpEngine
import com.dewijones92.totum.ytdlp.fake.FakeYtDlpEngine
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

/**
 * Extraction is what resolves a video; the player response only catches what extraction cannot.
 *
 * The ordering was briefly the other way round and is pinned here because reverting it was
 * evidence-driven, not a matter of taste. Report 0.1.312, from a real phone: asking YouTube
 * first took 15-37 SECONDS per resolve — every one paying for a QuickJS `n` solve, where the
 * emulator's URLs happened to need none — and nearly all thirteen of them ended in "Source
 * error / stream failed". Slower AND broken, against an extraction path that works.
 *
 * What the attempt left behind is still load-bearing: building a result straight from a player
 * response is what makes age-restricted videos play, once extraction has refused them.
 */
class VideoResolverFastPathTest {

    private val source = SourceId("s")

    private companion object {
        const val VIDEO_ID = "dQw4w9WgXcQ"
    }

    private val url = HttpUrl.of("https://www.youtube.com/watch?v=$VIDEO_ID")

    /** Counts extractions, so a test can prove one did or did not happen. */
    private class CountingEngine(val calls: AtomicInteger) : YtDlpEngine by FakeYtDlpEngine() {
        override suspend fun extract(url: HttpUrl): ExtractionResult {
            calls.incrementAndGet()
            return ExtractionResult.Success(
                MediaMetadata(
                    id = VIDEO_ID,
                    title = "From extraction",
                    uploader = null,
                    durationSeconds = 10,
                    thumbnailUrl = null,
                    formats = listOf(
                        MediaFormat(
                            "18", "mp4", 640, 360, true, true, null,
                            "https://x.test/extracted", "avc1", "mp4a",
                        ),
                    ),
                ),
            )
        }
    }

    /** An engine that cannot extract — the age-restricted case, where the player is the answer. */
    private class RefusingEngine : YtDlpEngine by FakeYtDlpEngine() {
        override suspend fun extract(url: HttpUrl) =
            ExtractionResult.Failure.Extractor("ERROR: Sign in to confirm your age")
    }

    private fun playerResponse(url: String?) = PlayerResult.Success(
        streaming = StreamingData(
            formats = listOfNotNull(
                url?.let {
                    PlayableFormat(
                        itag = 18,
                        mimeType = "video/mp4; codecs=\"avc1, mp4a\"",
                        height = 360,
                        bitrate = 1_000,
                        url = HttpUrl.of(it),
                    )
                },
            ),
        ),
        details = PlayerDetails(
            videoId = VIDEO_ID,
            title = "From the player",
            author = null,
            channelId = null,
            lengthSeconds = 10,
            thumbnailUrl = null,
            description = null,
        ),
    )

    /**
     * The revert, pinned. Extraction works and is predictable; the player response is not asked
     * while it is succeeding, however tempting its latency looks in isolation.
     */
    @Test
    fun `extraction resolves the video, not the player response`() = runTest {
        val calls = AtomicInteger()
        val resolver = VideoResolver(
            engine = CountingEngine(calls),
            skipSegments = SkipSegmentSource { emptyList() },
            playerStreams = { playerResponse("https://x.test/fast") },
        )

        // The player may still be CONSULTED — betterQualities asks it whether YouTube can beat a
        // degraded yt-dlp ladder — but the item that comes back is extraction's, which is the
        // property that was reverted to restore.
        assertEquals("From extraction", resolver.resolve(url, source, asked = "play")?.item?.title)
        assertEquals(1, calls.get())
    }

    @Test
    fun `signed in, the account answers first and yt-dlp is not asked`() = runTest {
        val calls = AtomicInteger()
        val resolver = VideoResolver(
            engine = CountingEngine(calls),
            skipSegments = SkipSegmentSource { emptyList() },
            playerStreams = object : PlayerStreams {
                override suspend fun playerFor(videoId: String) = playerResponse("https://x.test/anonymous")
                override suspend fun signedInFor(
                    videoId: String,
                    describe: Boolean
                ) = playerResponse("https://x.test/account")
            },
        )

        val resolved = resolver.resolve(url, source, asked = "play")

        assertEquals("https://x.test/account", resolved?.item?.mediaUrl?.value)
        assertEquals(0, calls.get())
    }

    @Test
    fun `a caller that knows the title spares the account its watch-page lookup`() = runTest {
        val described = mutableListOf<Boolean>()
        val streams = object : PlayerStreams {
            override suspend fun playerFor(videoId: String) = playerResponse("https://x.test/anonymous")
            override suspend fun signedInFor(videoId: String, describe: Boolean) =
                playerResponse("https://x.test/account").also { described += describe }
        }
        val resolver = VideoResolver(
            CountingEngine(AtomicInteger()),
            SkipSegmentSource {
                emptyList()
            },
            playerStreams = streams
        )
        val other = HttpUrl.of("https://www.youtube.com/watch?v=ZZZZZZZZZZZ")

        resolver.resolve(url, source, asked = "play", titleKnown = true)
        resolver.resolve(other, source, asked = "play", titleKnown = false)

        assertEquals(listOf(false, true), described)
    }

    @Test
    fun `a lookup made without a title is not handed to a caller that needs one`() = runTest {
        val described = mutableListOf<Boolean>()
        val streams = object : PlayerStreams {
            override suspend fun playerFor(videoId: String) = playerResponse("https://x.test/anonymous")
            override suspend fun signedInFor(videoId: String, describe: Boolean) =
                playerResponse("https://x.test/account").let { response ->
                    described += describe
                    if (describe) response else response.copy(details = response.details?.copy(title = videoId))
                }
        }
        val resolver =
            VideoResolver(CountingEngine(AtomicInteger()), SkipSegmentSource { emptyList() }, playerStreams = streams)

        resolver.resolve(url, source, asked = "play", titleKnown = true)
        val forASharedLink = resolver.resolve(url, source, asked = "describe", titleKnown = false)

        assertEquals(listOf(false, true), described)
        assertTrue(forASharedLink?.item?.title != VIDEO_ID)
    }

    @Test
    fun `signed in but refused, yt-dlp still resolves it`() = runTest {
        val calls = AtomicInteger()
        val resolver = VideoResolver(
            engine = CountingEngine(calls),
            skipSegments = SkipSegmentSource { emptyList() },
            playerStreams = object : PlayerStreams {
                override suspend fun playerFor(videoId: String) = playerResponse("https://x.test/anonymous")
                override suspend fun signedInFor(videoId: String, describe: Boolean): PlayerResult.Success? = null
            },
        )

        assertEquals("From extraction", resolver.resolve(url, source, asked = "play")?.item?.title)
        assertEquals(1, calls.get())
    }

    /**
     * The reason the machinery stayed. yt-dlp cannot reach an age-restricted video at all, and
     * this is the path that plays one — verified on a device 2026-08-01.
     */
    @Test
    fun `an extraction failure is recovered from the player response`() = runTest {
        val resolver = VideoResolver(
            engine = RefusingEngine(),
            skipSegments = SkipSegmentSource { emptyList() },
            playerStreams = { playerResponse("https://x.test/from-player") },
        )

        assertEquals("From the player", resolver.resolve(url, source, asked = "play")?.item?.title)
    }

    /** A player response with nothing fetchable in it cannot rescue anything. */
    @Test
    fun `an extraction failure with no fetchable stream stays a failure`() = runTest {
        val resolver = VideoResolver(
            engine = RefusingEngine(),
            skipSegments = SkipSegmentSource { emptyList() },
            playerStreams = { playerResponse(url = null) },
        )

        assertNull(resolver.resolve(url, source, asked = "play"))
    }

    /** A throwing player must never turn a recoverable failure into a crash. */
    @Test
    fun `a player that throws leaves the resolve as a null`() = runTest {
        val resolver = VideoResolver(
            engine = RefusingEngine(),
            skipSegments = SkipSegmentSource { emptyList() },
            playerStreams = { error("innertube exploded") },
        )

        assertNull(resolver.resolve(url, source, asked = "play"))
    }

    /** With no player wired at all, an extraction failure is simply a failure. */
    @Test
    fun `no player configured means an extraction failure is final`() = runTest {
        val resolver = VideoResolver(
            engine = RefusingEngine(),
            skipSegments = SkipSegmentSource { emptyList() },
        )

        assertNull(resolver.resolve(url, source, asked = "play"))
    }
}
