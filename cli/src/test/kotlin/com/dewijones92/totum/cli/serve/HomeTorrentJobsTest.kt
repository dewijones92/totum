package com.dewijones92.totum.cli.serve

import com.dewijones92.totum.common.BotBackOff
import com.dewijones92.totum.common.HttpUrl
import com.dewijones92.totum.common.PoliteGate
import com.dewijones92.totum.data.backup.toBackup
import com.dewijones92.totum.data.home.HomeJob
import com.dewijones92.totum.data.home.HomeJobRequest
import com.dewijones92.totum.data.home.HomeJobState
import com.dewijones92.totum.domain.MediaContentKind
import com.dewijones92.totum.domain.MediaItem
import com.dewijones92.totum.domain.MediaItemId
import com.dewijones92.totum.domain.PlayHandle
import com.dewijones92.totum.domain.PlayableItem
import com.dewijones92.totum.domain.TorrentIds
import com.dewijones92.totum.polite.Politeness
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.net.InetSocketAddress
import java.util.concurrent.CopyOnWriteArrayList

class HomeTorrentJobsTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val polite = Politeness(PoliteGate(maxInFlight = 4, perSecond = 1_000.0, burst = 1_000), BotBackOff())
    private val seen = CopyOnWriteArrayList<String>()
    private var answer = 503
    private val stalls = CopyOnWriteArrayList<HttpServer>()

    @After
    fun stop() {
        scope.cancel()
        stalls.forEach { it.stop(0) }
    }

    private val transport = Interceptor { chain ->
        val request = chain.request()
        val body = request.body?.let { okio.Buffer().also { buffer -> it.writeTo(buffer) }.readUtf8() }.orEmpty()
        seen += "${request.method} ${request.url} $body".trim()
        Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(answer).message("answer")
            .body("{}".toResponseBody("application/json".toMediaType())).build()
    }

    private fun home(
        readTimeoutS: Long = 60,
        torrentReadTimeoutS: Long = 600,
        intercept: Boolean = true,
    ) = HomeDownloads(
        dataDir = tmp.newFolder("data"),
        downloadDir = tmp.newFolder("downloads"),
        scope = scope,
        rewrites = rewritesFrom("https://totum.home/ts/=http://gluetun:8090/"),
        freeFloorBytes = 0,
        polite = polite,
        transport = transport.takeIf { intercept },
        readTimeoutS = readTimeoutS,
        torrentReadTimeoutS = torrentReadTimeoutS,
    )

    private fun torrent(url: String, id: String = "torrent:abc123:1") = PlayableItem(
        MediaItem(
            id = MediaItemId(id),
            sourceId = TorrentIds.SOURCE,
            title = "A film",
            publishedAt = null,
            duration = null,
            author = "A.Film.2025.1080p",
            mediaUrl = HttpUrl.of(url),
            contentKind = MediaContentKind.STANDARD,
        ),
        PlayHandle.Podcast(),
    )

    private fun podcast(url: String) = torrent(url, id = "https://feed/episode-1").let {
        it.copy(item = it.item.copy(sourceId = com.dewijones92.totum.domain.SourceId("feed")))
    }

    private fun request(item: PlayableItem) = HomeJobRequest(item.toBackup(), audioOnly = false)

    private fun until(what: String, check: suspend () -> Boolean) {
        val limit = System.currentTimeMillis() + WAIT_MS
        while (System.currentTimeMillis() < limit) {
            if (runBlocking { check() }) return
            Thread.sleep(POLL_MS)
        }
        throw AssertionError("gave up waiting for $what; the Pi was asked: $seen")
    }

    private fun HomeDownloads.job(id: String): HomeJob? = runBlocking { jobs().jobs.firstOrNull { it.id == id } }

    private val publicStream = "https://totum.home/ts/stream/film.mkv?link=abc123&index=1&play"

    @Test
    fun `a transient failure with a retry planned is waiting, not failed, and says when it tries again`() {
        val home = home()
        runBlocking { home.start(request(torrent(publicStream))) }

        until("the first attempt to fail") { home.job("torrent:abc123:1")?.retry != null }

        val job = home.job("torrent:abc123:1")!!
        assertEquals(HomeJobState.WAITING, job.state)
        assertEquals(1, job.retry!!.attempt)
        assertEquals(4, job.retry!!.of)
        assertTrue("it is planned in the future", job.retry!!.atEpochMs > System.currentTimeMillis())
        assertTrue("it still says why: ${job.error}", job.error.orEmpty().contains("503"))
    }

    @Test
    fun `the job keeps the phone's own address, and the rewrite is used only to fetch`() {
        val home = home()
        runBlocking { home.start(request(torrent(publicStream))) }

        until("the Pi to fetch") { seen.any { it.startsWith("GET") } }

        assertEquals(publicStream, home.job("torrent:abc123:1")!!.item.mediaUrl)
        assertTrue(seen.toString(), seen.any { it.startsWith("GET http://gluetun:8090/stream/film.mkv") })
    }

    @Test
    fun `a transient torrent failure makes TorrServer drop and re-add the torrent before the retry`() {
        val home = home()
        runBlocking { home.start(request(torrent(publicStream))) }

        until("TorrServer to be asked to re-add it") { seen.any { "\"action\":\"add\"" in it } }

        val resets = seen.filter { it.startsWith("POST http://gluetun:8090/torrents") }
        assertEquals(seen.toString(), 2, resets.size)
        assertTrue(resets[0], "\"action\":\"drop\"" in resets[0] && "\"hash\":\"abc123\"" in resets[0])
        assertTrue(resets[1], "\"action\":\"add\"" in resets[1] && "\"link\":\"abc123\"" in resets[1])
    }

    @Test
    fun `a stalled torrent stream gets the long read timeout, a podcast the short one`() {
        val server = stallingServer(stallMs = 2_500)
        val home = home(readTimeoutS = 1, torrentReadTimeoutS = 8, intercept = false)

        runBlocking { home.start(request(torrent("http://127.0.0.1:${server.address.port}/stream/film.mkv"))) }
        until("the stalled torrent to land") { home.job("torrent:abc123:1")?.state == HomeJobState.DONE }

        runBlocking { home.start(request(podcast("http://127.0.0.1:${server.address.port}/ep.mp3"))) }
        until("the podcast to hit its short timeout") { home.job("https://feed/episode-1")?.retry != null }
        assertNotNull(home.job("https://feed/episode-1")!!.error)
        assertTrue(home.job("https://feed/episode-1")!!.error.orEmpty().lowercase().contains("time"))
    }

    private fun stallingServer(stallMs: Long): HttpServer {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { exchange ->
            exchange.responseHeaders.add("Content-Type", "video/x-matroska")
            exchange.sendResponseHeaders(200, 8)
            exchange.responseBody.use { out ->
                out.write(ByteArray(4))
                out.flush()
                Thread.sleep(stallMs)
                out.write(ByteArray(4))
            }
        }
        server.start()
        stalls += server
        return server
    }

    private companion object {
        const val WAIT_MS = 20_000L
        const val POLL_MS = 50L
    }
}
