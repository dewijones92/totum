package com.dewijones92.totum.polite

import com.dewijones92.totum.common.BotBackOff
import com.dewijones92.totum.common.HttpUrl
import com.dewijones92.totum.common.PoliteGate
import com.dewijones92.totum.ytdlp.DownloadEvent
import com.dewijones92.totum.ytdlp.DownloadRequest
import com.dewijones92.totum.ytdlp.ExtractionResult
import com.dewijones92.totum.ytdlp.VideoSearchResult
import com.dewijones92.totum.ytdlp.YtDlpEngine
import com.dewijones92.totum.ytdlp.fake.FakeYtDlpEngine
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class PoliteDownloadTest {
    private var clock = 0L
    private val url = HttpUrl.of("https://www.youtube.com/watch?v=C6pdB9oikdo")
    private val request = DownloadRequest(url = url, targetDirectory = File("unused"))
    private val botBackOff = BotBackOff { clock }
    private val gate = PoliteGate(maxInFlight = 1, perSecond = 1_000.0, burst = 1_000, now = { clock })
    private val inFlightAtStart = mutableListOf<Int>()
    private var upstream: () -> Flow<DownloadEvent> = { flowOf() }
    private val inner = object : YtDlpEngine by FakeYtDlpEngine() {
        override fun download(request: DownloadRequest): Flow<DownloadEvent> = flow {
            inFlightAtStart += gate.inFlight
            emitAll(upstream())
        }
    }
    private val engine = PoliteYtDlpEngine(inner, gate, botBackOff)

    private fun finishes() = {
        flowOf(
            DownloadEvent.Started(url),
            DownloadEvent.Progress(1, 10, 0),
            DownloadEvent.Completed(File("out.mp4")),
        )
    }

    private fun refusedWith(text: String) = {
        flowOf(DownloadEvent.Started(url), DownloadEvent.Failed(ExtractionResult.Failure.Extractor(text)))
    }

    @Test
    fun `a download's extraction holds a permit from the gate until bytes flow`() = runTest {
        upstream = finishes()

        engine.download(request).toList()

        assertEquals("the extraction ran holding the gate's one permit", listOf(1), inFlightAtStart)
        assertEquals(0, gate.inFlight)
    }

    @Test
    fun `a long byte transfer does not hold the permit, so downloads keep their own cap`() = runTest {
        upstream = finishes()
        val seen = mutableListOf<Int>()

        engine.download(request).collect { if (it is DownloadEvent.Progress) seen += gate.inFlight }

        assertEquals("the permit was already back when the first bytes arrived", listOf(0), seen)
    }

    @Test
    fun `a bot check inside a download backs anonymous yt-dlp off for the next extraction`() = runTest {
        upstream = refusedWith("ERROR: [youtube] C6pdB9oikdo: Sign in to confirm you’re not a bot.")

        engine.download(request).toList()

        assertTrue(botBackOff.until.value != null)
        val skipped = engine.extract(url)
        assertTrue(skipped.toString(), skipped.toString().contains(PoliteYtDlpEngine.BACKING_OFF))
    }

    @Test
    fun `a 429 inside a download slows every YouTube call down`() = runTest {
        upstream = refusedWith("ERROR: HTTP Error 429: Too Many Requests")

        engine.download(request).toList()

        assertTrue(gate.slowDown.value != null)
    }

    @Test
    fun `a download that gets going lifts a bot back-off that has ended`() = runTest {
        upstream = refusedWith("ERROR: Sign in to confirm you’re not a bot")
        engine.download(request).toList()
        clock += 60_001
        upstream = finishes()

        engine.download(request).toList()

        assertNull(botBackOff.until.value)
    }

    @Test
    fun `a failure that is neither a bot check nor a 429 changes nothing`() = runTest {
        upstream = refusedWith("ERROR: HTTP Error 403: Forbidden")

        engine.download(request).toList()

        assertNull(botBackOff.until.value)
        assertNull(gate.slowDown.value)
    }

    @Test
    fun `a collector that stops early gives the permit back`() = runTest {
        upstream = finishes()

        engine.download(request).first()

        assertEquals(0, gate.inFlight)
    }
}

class PolitenessWiringTest {
    private val gate = PoliteGate(maxInFlight = 1, perSecond = 1_000.0, burst = 1_000)
    private val polite = Politeness(gate, BotBackOff())

    @Test
    fun `an engine wrapped by Politeness takes its permits from Politeness's own gate`() = runTest {
        val seen = mutableListOf<Int>()
        val inner = object : YtDlpEngine by FakeYtDlpEngine() {
            override suspend fun searchVideos(query: String, maxResults: Int): VideoSearchResult {
                seen += gate.inFlight
                return VideoSearchResult.Success(emptyList())
            }
        }

        polite.gated(inner).searchVideos("x", 1)

        assertEquals(listOf(1), seen)
    }

    @Test
    fun `a client built through Politeness gates YouTube's API and leaves the video bytes alone`() {
        val seen = mutableListOf<Pair<String, Int>>()
        val client = polite.gated(OkHttpClient.Builder())
            .addInterceptor { chain ->
                seen += chain.request().url.host to gate.inFlight
                Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200)
                    .message("ok").body("{}".toResponseBody()).build()
            }
            .build()

        client.newCall(Request.Builder().url("https://www.youtube.com/youtubei/v1/player").build()).execute().close()
        client.newCall(Request.Builder().url("https://rr1.googlevideo.com/videoplayback?x=1").build()).execute().close()

        assertEquals(listOf("www.youtube.com" to 1, "rr1.googlevideo.com" to 0), seen)
    }

    @Test
    fun `a solve of n or of a signature takes a permit, as the rule names solving`() = runTest {
        val seen = mutableListOf<Int>()
        val inner = object : YtDlpEngine by FakeYtDlpEngine() {
            override suspend fun solveN(challenges: List<String>, playerUrl: String): Map<String, String> {
                seen += gate.inFlight
                return emptyMap()
            }

            override suspend fun solveSignatures(challenges: List<String>, playerUrl: String): Map<String, String> {
                seen += gate.inFlight
                return emptyMap()
            }
        }
        val engine = polite.gated(inner)

        engine.solveN(listOf("a"), "https://www.youtube.com/s/player/x/base.js")
        engine.solveSignatures(listOf("a"), "https://www.youtube.com/s/player/x/base.js")

        assertEquals(listOf(1, 1), seen)
        assertEquals(0, gate.inFlight)
    }

    @Test
    fun `the banner is up while a slow-down lasts and not otherwise`() {
        assertFalse(polite.bannerUp())
        gate.tooManyRequests("x", retryAfterMs = null)
        assertTrue(polite.bannerUp())
    }
}
