package com.dewijones92.totum.video

import com.dewijones92.totum.common.Diag
import com.dewijones92.totum.common.HttpUrl
import com.dewijones92.totum.data.sponsorblock.SkipSegmentSource
import com.dewijones92.totum.domain.SourceId
import com.dewijones92.totum.innertube.browse.InnerTubeClient
import com.dewijones92.totum.innertube.player.NetworkUnreachable
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
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.net.UnknownHostException
import java.util.concurrent.atomic.AtomicInteger

class ADnsFailureWaitsForTheNetworkTest {

    private val source = SourceId("s")
    private val url = HttpUrl.of("https://www.youtube.com/watch?v=$VIDEO_ID")
    private val lines = mutableListOf<String>()

    @After fun quiet() {
        Diag.sink = Diag.Sink { _, _, _, _ -> }
    }

    private fun capture() {
        Diag.sink = Diag.Sink { _, _, message, _ -> lines += message }
    }

    private class Extractions(val calls: AtomicInteger, private val answer: ExtractionResult? = null) :
        YtDlpEngine by FakeYtDlpEngine() {
        override suspend fun extract(url: HttpUrl): ExtractionResult {
            calls.incrementAndGet()
            return answer ?: ExtractionResult.Success(
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

    private fun accountAnswer() = PlayerResult.Success(
        streaming = StreamingData(
            formats = listOf(
                PlayableFormat(
                    itag = 18,
                    mimeType = "video/mp4; codecs=\"avc1, mp4a\"",
                    height = 360,
                    bitrate = 1_000,
                    url = HttpUrl.of("https://x.test/account"),
                ),
            ),
        ),
        details = PlayerDetails(
            videoId = VIDEO_ID,
            title = "From the account",
            author = null,
            channelId = null,
            lengthSeconds = 10,
            thumbnailUrl = null,
            description = null,
        ),
    )

    private class Waits(var online: Boolean, val comesBack: Boolean) {
        val asked = mutableListOf<Long>()
        suspend fun until(timeoutMs: Long): Boolean {
            asked += timeoutMs
            online = comesBack
            return comesBack
        }
    }

    @Test
    fun `offline, nothing is asked of YouTube or yt-dlp until the network is back`() = runTest {
        val extractions = AtomicInteger()
        val network = Waits(online = false, comesBack = true)
        val resolver = VideoResolver(
            engine = Extractions(extractions),
            skipSegments = SkipSegmentSource { emptyList() },
            online = { network.online },
            awaitOnline = network::until,
        )

        val resolved = resolver.resolve(url, source, asked = "download")

        assertEquals("From extraction", resolved?.item?.title)
        assertEquals(listOf(30_000L), network.asked)
        assertEquals(1, extractions.get())
    }

    @Test
    fun `offline and staying offline, yt-dlp is never run and the video is not called unavailable`() = runTest {
        capture()
        val extractions = AtomicInteger()
        val network = Waits(online = false, comesBack = false)
        val resolver = VideoResolver(
            engine = Extractions(extractions),
            skipSegments = SkipSegmentSource { emptyList() },
            online = { network.online },
            awaitOnline = network::until,
        )

        assertNull(resolver.resolve(url, source, asked = "download"))

        assertEquals(0, extractions.get())
        assertFalse(lines.toString(), lines.any { it.contains("genuinely unavailable") })
        assertTrue(lines.toString(), lines.any { it.contains("still offline") })
    }

    @Test
    fun `online, the network is not waited for`() = runTest {
        val network = Waits(online = true, comesBack = true)
        val resolver = VideoResolver(
            engine = Extractions(AtomicInteger()),
            skipSegments = SkipSegmentSource { emptyList() },
            online = { network.online },
            awaitOnline = network::until,
        )

        assertNotNull(resolver.resolve(url, source, asked = "play"))

        assertTrue(network.asked.isEmpty())
    }

    @Test
    fun `a DNS failure in the signed-in ask waits for the network and asks the account again`() = runTest {
        val extractions = AtomicInteger()
        val tries = AtomicInteger()
        val network = Waits(online = true, comesBack = true)
        val resolver = VideoResolver(
            engine = Extractions(extractions),
            skipSegments = SkipSegmentSource { emptyList() },
            playerStreams = object : PlayerStreams {
                override suspend fun playerFor(videoId: String): PlayerResult.Success? = null
                override suspend fun signedInFor(videoId: String, describe: Boolean): PlayerResult.Success {
                    if (tries.incrementAndGet() == 1) throw NetworkUnreachable("Unable to resolve host")
                    return accountAnswer()
                }
            },
            online = { network.online },
            awaitOnline = network::until,
        )

        val resolved = resolver.resolve(url, source, asked = "download")

        assertEquals("https://x.test/account", resolved?.item?.mediaUrl?.value)
        assertEquals(2, tries.get())
        assertEquals("yt-dlp is not run through every client", 0, extractions.get())
    }

    @Test
    fun `a DNS failure that does not clear stops short of yt-dlp and does not call the video unavailable`() = runTest {
        capture()
        val extractions = AtomicInteger()
        val network = Waits(online = true, comesBack = true)
        val resolver = VideoResolver(
            engine = Extractions(extractions),
            skipSegments = SkipSegmentSource { emptyList() },
            playerStreams = object : PlayerStreams {
                override suspend fun playerFor(videoId: String): PlayerResult.Success? = null
                override suspend fun signedInFor(videoId: String, describe: Boolean): PlayerResult.Success =
                    throw NetworkUnreachable("Unable to resolve host \"www.youtube.com\"")
            },
            online = { network.online },
            awaitOnline = network::until,
        )

        assertNull(resolver.resolve(url, source, asked = "download"))

        assertEquals(0, extractions.get())
        assertFalse(lines.toString(), lines.any { it.contains("genuinely unavailable") })
        assertTrue(lines.toString(), lines.any { it.contains("still unreachable") })
    }

    @Test
    fun `a network failure out of yt-dlp is logged as the network, never as unavailable`() = runTest {
        capture()
        val resolver = VideoResolver(
            engine = Extractions(AtomicInteger(), ExtractionResult.Failure.Network("Unable to resolve host")),
            skipSegments = SkipSegmentSource { emptyList() },
            playerStreams = object : PlayerStreams {
                override suspend fun playerFor(videoId: String): PlayerResult.Success? = null
            },
        )

        assertNull(resolver.resolve(url, source, asked = "download"))

        assertFalse(lines.toString(), lines.any { it.contains("genuinely unavailable") })
        assertTrue(lines.toString(), lines.any { it.contains("the network failed during extraction") })
    }

    @Test
    fun `a refused video is still called unavailable when the network was fine`() = runTest {
        capture()
        val resolver = VideoResolver(
            engine = Extractions(AtomicInteger(), ExtractionResult.Failure.Extractor("Video unavailable")),
            skipSegments = SkipSegmentSource { emptyList() },
            playerStreams = object : PlayerStreams {
                override suspend fun playerFor(videoId: String): PlayerResult.Success? = null
            },
        )

        assertNull(resolver.resolve(url, source, asked = "download"))

        assertTrue(lines.toString(), lines.any { it.contains("genuinely unavailable") })
    }

    @Test
    fun `the client says a DNS failure is the network, and a timeout is not`() {
        assertTrue(InnerTubeClient.isUnreachable(UnknownHostException("www.youtube.com")))
        assertTrue(InnerTubeClient.isUnreachable(IOException("wrapped", UnknownHostException("www.youtube.com"))))
        assertFalse(InnerTubeClient.isUnreachable(java.net.SocketTimeoutException("timeout")))
        assertFalse(InnerTubeClient.isUnreachable(IOException("unexpected end of stream")))
    }

    @Test
    fun `the account path rethrows an unreachable network instead of giving nothing`() = runTest {
        val streams = InnerTubePlayerStreams(
            InnerTubeClient(OkHttpClient()),
            account = { _, _ -> PlayerResult.Failure("Unable to resolve host", unreachable = true) },
            preferAccount = { true },
        )

        val thrown = runCatching { streams.signedInFor(VIDEO_ID, describe = false) }.exceptionOrNull()

        assertTrue(thrown.toString(), thrown is NetworkUnreachable)
    }

    @Test
    fun `an account answer that is only a refusal is still nothing, not a network fault`() = runTest {
        val streams = InnerTubePlayerStreams(
            InnerTubeClient(OkHttpClient()),
            account = { _, _ -> PlayerResult.Failure("HTTP 400") },
            preferAccount = { true },
        )

        assertNull(streams.signedInFor(VIDEO_ID, describe = false))
    }

    private companion object {
        const val VIDEO_ID = "dQw4w9WgXcQ"
    }
}
