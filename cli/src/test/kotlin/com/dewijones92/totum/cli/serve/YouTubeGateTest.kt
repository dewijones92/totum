package com.dewijones92.totum.cli.serve

import com.dewijones92.totum.cli.serve.cast.CommandOutput
import com.dewijones92.totum.cli.serve.cast.YtDlpLiveHls
import com.dewijones92.totum.cli.serve.targets.YouTubeProgress
import com.dewijones92.totum.common.BotBackOff
import com.dewijones92.totum.common.HttpUrl
import com.dewijones92.totum.common.PoliteGate
import com.dewijones92.totum.data.backup.toBackup
import com.dewijones92.totum.data.home.HomeJobRequest
import com.dewijones92.totum.data.targets.TargetEntry
import com.dewijones92.totum.domain.MediaItem
import com.dewijones92.totum.domain.MediaItemId
import com.dewijones92.totum.domain.PlayHandle
import com.dewijones92.totum.domain.PlayableItem
import com.dewijones92.totum.domain.SourceId
import com.dewijones92.totum.polite.Politeness
import com.dewijones92.totum.ytdlp.DownloadEvent
import com.dewijones92.totum.ytdlp.DownloadRequest
import com.dewijones92.totum.ytdlp.ExtractionResult
import com.dewijones92.totum.ytdlp.YtDlpEngine
import com.dewijones92.totum.ytdlp.fake.FakeYtDlpEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class YouTubeGateTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val gate = PoliteGate(maxInFlight = 1, perSecond = 1_000.0, burst = 1_000)
    private val botBackOff = BotBackOff()
    private val polite = Politeness(gate, botBackOff)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private class Seen(val what: String, val inFlight: Int)

    private val network = CopyOnWriteArrayList<Seen>()
    private val extractions = CopyOnWriteArrayList<Seen>()
    private val downloads = CopyOnWriteArrayList<Seen>()
    private val liveLookups = CopyOnWriteArrayList<Seen>()

    private var playerReply = "{}"
    private var extractReply: ExtractionResult = ExtractionResult.Failure.Extractor("nothing to extract")
    private var downloadFailure: String = "ERROR: HTTP Error 403: Forbidden"

    private val transport = Interceptor { chain ->
        val request: Request = chain.request()
        network += Seen("${request.url.host}${request.url.encodedPath}", gate.inFlight)
        Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(200).message("ok")
            .body(playerReply.toResponseBody("application/json".toMediaType())).build()
    }

    private val rawEngine = object : YtDlpEngine by FakeYtDlpEngine() {
        override suspend fun extract(url: HttpUrl): ExtractionResult {
            extractions += Seen(url.value, gate.inFlight)
            return extractReply
        }

        override fun download(request: DownloadRequest): Flow<DownloadEvent> = flow {
            downloads += Seen(request.url.value, gate.inFlight)
            emit(DownloadEvent.Started(request.url))
            emit(DownloadEvent.Failed(ExtractionResult.Failure.Extractor(downloadFailure)))
        }
    }

    private fun home(): HomeDownloads = HomeDownloads(
        dataDir = tmp.newFolder("data"),
        downloadDir = tmp.newFolder("downloads"),
        scope = scope,
        freeFloorBytes = 0,
        polite = polite,
        rawEngine = rawEngine,
        transport = transport,
    )

    @After
    fun stop() {
        scope.cancel()
    }

    private val item = PlayableItem(
        MediaItem(MediaItemId("dQw4w9WgXcQ"), SourceId("c"), "A song", publishedAt = null, duration = null),
        PlayHandle.Video(HttpUrl.of("https://www.youtube.com/watch?v=dQw4w9WgXcQ")),
    )
    private val other = PlayableItem(
        MediaItem(MediaItemId("aaaaaaaaaaa"), SourceId("c"), "Another", publishedAt = null, duration = null),
        PlayHandle.Video(HttpUrl.of("https://www.youtube.com/watch?v=aaaaaaaaaaa")),
    )

    private fun signedInOwnerEntry(): TargetEntry = TargetEntry(
        item.toBackup(),
        addedBy = "Dewi's phone",
        reportsToYouTube = true,
    )

    private fun signIn(home: HomeDownloads) {
        val far = System.currentTimeMillis() / 1_000 + 86_400
        File(home.dataDir, "youtube.json").writeText("""{"access":"a","refresh":"r","expiresAt":$far}""")
    }

    private fun liveHls(output: CommandOutput = CommandOutput(0, "https://manifest.googlevideo.com/x.m3u8\n", "")) =
        YtDlpLiveHls("python3", null, gate, botBackOff) {
            liveLookups += Seen(it.last(), gate.inFlight)
            output
        }

    private fun requestFor(playable: PlayableItem) = HomeJobRequest(playable.toBackup(), audioOnly = false)

    private fun untilNonEmpty(seen: List<Seen>) {
        val until = System.currentTimeMillis() + WAIT_MS
        while (seen.isEmpty() && System.currentTimeMillis() < until) Thread.sleep(POLL_MS)
    }

    private fun reachedYouTube() = network.size + extractions.size + downloads.size + liveLookups.size

    private fun holdsTheOnlyPermitWhile(entryPoint: () -> Unit) {
        val held = CountDownLatch(1)
        val release = CountDownLatch(1)
        val holder = Thread {
            gate.pass("another YouTube call") {
                held.countDown()
                release.await()
            }
        }.apply { start() }
        assertTrue("the holder took the permit", held.await(WAIT_MS, TimeUnit.MILLISECONDS))
        val caller = Thread(entryPoint).apply { start() }
        Thread.sleep(HELD_MS)
        assertEquals("nothing reached YouTube while another call held the only permit", 0, reachedYouTube())
        assertTrue("the entry point is waiting for the gate", caller.isAlive)
        release.countDown()
        caller.join(WAIT_MS)
        holder.join(WAIT_MS)
        assertFalse("the entry point finished once the permit came back", caller.isAlive)
        assertTrue("it reached YouTube after the wait", reachedYouTube() > 0)
        (network + extractions + downloads + liveLookups).forEach {
            assertEquals("${it.what} ran holding the gate's one permit", 1, it.inFlight)
        }
    }

    @Test
    fun `a target's resolve for a speaker waits for the shared gate and holds a permit`() {
        val home = home()
        holdsTheOnlyPermitWhile { runBlocking { home.soundOf(item) } }
        assertTrue("yt-dlp was asked", extractions.isNotEmpty())
        assertTrue("YouTube's own API was asked", network.any { "youtube.com" in it.what })
    }

    @Test
    fun `a target's resolve for a TV waits for the shared gate and holds a permit`() {
        val home = home()
        holdsTheOnlyPermitWhile { runBlocking { home.castOf(item, picture = true) } }
        assertTrue("yt-dlp was asked", extractions.isNotEmpty())
    }

    @Test
    fun `a home download's extraction waits for the shared gate and holds a permit`() {
        val home = home()
        holdsTheOnlyPermitWhile {
            runBlocking { home.start(requestFor(item)) }
            untilNonEmpty(downloads)
        }
        assertEquals(1, downloads.size)
    }

    @Test
    fun `a progress report to YouTube waits for the shared gate and holds a permit`() {
        val home = home()
        signIn(home)
        val progress = YouTubeProgress(home.watchHistory, home::signedInToYouTube)
        holdsTheOnlyPermitWhile {
            runBlocking { progress.report(signedInOwnerEntry(), 30_000, 120_000, finished = false) }
        }
        assertTrue(network.toString(), network.any { "youtube.com" in it.what })
    }

    @Test
    fun `the live HLS lookup waits for the shared gate and holds a permit`() {
        holdsTheOnlyPermitWhile { liveHls().urlFor("Dx5qFachd3A") }
        assertEquals(1, liveLookups.size)
    }

    @Test
    fun `a bot check seen by a live HLS lookup stops home's yt-dlp extraction`() {
        val home = home()
        liveHls(CommandOutput(1, "", "ERROR: Sign in to confirm you're not a bot")).urlFor("Dx5qFachd3A")

        runBlocking { home.soundOf(item) }

        assertEquals("yt-dlp was not asked", 0, extractions.size)
        assertEquals("nor was YouTube's anonymous player", 0, network.count { "player" in it.what })
    }

    @Test
    fun `a bot check seen by home's extraction stops the live HLS lookup`() {
        val home = home()
        extractReply = ExtractionResult.Failure.Extractor("ERROR: [youtube] x: Sign in to confirm you’re not a bot")
        runBlocking { home.soundOf(item) }

        val url = liveHls().urlFor("Dx5qFachd3A")

        assertEquals(null, url)
        assertEquals("yt-dlp was not asked", 0, liveLookups.size)
    }

    @Test
    fun `a bot check seen by a home download stops the live HLS lookup and the next extraction`() {
        val home = home()
        downloadFailure = "ERROR: [youtube] x: Sign in to confirm you’re not a bot"
        runBlocking { home.start(requestFor(item)) }
        untilNonEmpty(downloads)
        val until = System.currentTimeMillis() + WAIT_MS
        while (botBackOff.until.value == null && System.currentTimeMillis() < until) Thread.sleep(POLL_MS)

        assertEquals(null, liveHls().urlFor("Dx5qFachd3A"))
        runBlocking { home.soundOf(other) }

        assertEquals("the live lookup did not run", 0, liveLookups.size)
        assertEquals("the resolve did not reach yt-dlp", 0, extractions.size)
    }

    @Test
    fun `a bot check in YouTube's own player answer stops yt-dlp everywhere`() {
        val home = home()
        playerReply = """{"playabilityStatus":{"status":"LOGIN_REQUIRED",""" +
            """"reason":"Sign in to confirm you’re not a bot"}}"""
        runBlocking { home.soundOf(item) }
        val extractedBefore = extractions.size

        runBlocking { home.soundOf(other) }

        assertEquals(null, liveHls().urlFor("Dx5qFachd3A"))
        assertEquals("the live lookup did not run", 0, liveLookups.size)
        assertEquals("the second resolve did not reach yt-dlp", extractedBefore, extractions.size)
    }

    private companion object {
        const val WAIT_MS = 15_000L
        const val HELD_MS = 400L
        const val POLL_MS = 25L
    }
}
