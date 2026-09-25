package com.dewijones92.totum.playback

import androidx.media3.common.Player
import androidx.test.ext.junit.rules.ActivityScenarioRule
import androidx.test.platform.app.InstrumentationRegistry
import com.dewijones92.totum.MainActivity
import com.dewijones92.totum.TotumApplication
import com.dewijones92.totum.common.Breadcrumbs
import com.dewijones92.totum.data.search.SearchHit
import com.dewijones92.totum.data.search.SearchOutcome
import com.dewijones92.totum.data.search.SearchQuery
import com.dewijones92.totum.data.search.TorrentSearchSource
import com.dewijones92.totum.data.torrent.HttpHomeTorrentServer
import com.dewijones92.totum.data.torrent.TorrentPlayables
import com.dewijones92.totum.domain.DownloadState
import com.dewijones92.totum.domain.MediaItemId
import com.dewijones92.totum.settings.PlaybackMode
import com.dewijones92.totum.support.DeviceRadios.goOffline
import com.dewijones92.totum.support.DeviceRadios.goOnline
import com.dewijones92.totum.support.DeviceRadios.hasNetwork
import com.dewijones92.totum.support.PlaybackWaits
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import java.io.OutputStream
import java.net.ServerSocket
import java.net.Socket
import kotlin.concurrent.thread

/**
 * The torrent pillar, end to end on a device: search → prepare → queue → play, then offline.
 *
 * Dewi, 2026-08-06: *"make sure you have e2e of all these flows (copyright free stuff for torrents
 * ofcourse) in the ci/cd"*, and separately *"check that torrents work this way too????? unified
 * approach????"*. This is the answer to both, in CI, on every commit.
 *
 * **Nothing copyrighted is fetched, and nothing real is torrented.** The media is a silent WAV this
 * test generates, so the bytes are copyright-free by construction; the titles name genuinely
 * public-domain films (*Night of the Living Dead*, 1968) because a test's fixtures should not imply
 * otherwise even when nothing is downloaded. No swarm, no peers, no magnet is ever resolved.
 *
 * **Why a stand-in home server rather than the real Pi.** CI cannot reach it: the WireGuard peer
 * that gives the emulator a residential IP is deliberately firewalled to internet-only egress
 * (`vpn-stack/wg-home-init/10-ci-peer-lockdown.sh`, asserted by `tools/ci/live-test-via-home.sh`),
 * and the public endpoints are behind a Google login the app cannot complete unattended until
 * `torrent-zero-config` ships. So this drives the REAL `HttpHomeTorrentServer`, the REAL
 * `TorrentSearchSource`, the REAL queue and the REAL player against a socket that speaks
 * Prowlarr's and TorrServer's protocols — everything the app owns, none of what it does not.
 *
 * **What this does NOT cover**, said out loud rather than left to look covered: Listen mode's
 * remuxed audio (`/ts/audio/…/index.m3u8`) is real HLS, and a stand-in cannot serve a valid
 * playlist plus segments without shipping media this repository deliberately does not carry. So
 * these tests watch rather than listen, and the audio-only variant stays proven only on the real
 * server. Its URL construction and warm-up are unit-tested in `HttpHomeTorrentServerTest`.
 */
class TorrentQueuePlaybackTest {

    /** Foreground, or the platform refuses audio focus and nothing ever plays. */
    @get:Rule
    val activity = ActivityScenarioRule(MainActivity::class.java)

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val app = context.applicationContext as TotumApplication
    private val container get() = app.container
    private val controller get() = container.playbackController
    private val queue get() = container.playbackQueue
    private val downloads get() = container.downloadManager

    private lateinit var socket: ServerSocket

    /**
     * The repository's own generated clip: 90 seconds of black H.264 with silent audio, made with
     * ffmpeg rather than sourced, so it is copyright-free by construction — and a real MP4, so the
     * fixture's `feature.mp4` is honest about what the bytes are. A `.wav` would be rejected before
     * playback anyway: `.wav` is not in `PLAYABLE_EXTENSIONS`, because a film torrent is not one.
     */
    private val media: ByteArray by lazy {
        InstrumentationRegistry.getInstrumentation().context.assets.open("clip.mp4").use { it.readBytes() }
    }

    /** The real client, pointed at the stand-in. Token and key are whatever it likes. */
    private val homeServer by lazy {
        HttpHomeTorrentServer(
            client = OkHttpClient(),
            base = "http://127.0.0.1:${socket.localPort}",
            prowlarrApiKey = { "test-key" },
            token = { "test-token" },
        )
    }

    @Before
    fun startStandInAndEmptyTheQueue() {
        socket = ServerSocket(0)
        thread(isDaemon = true, name = "torrent-test-server") { serveUntilClosed() }
        runBlocking(Dispatchers.Main) {
            awaitControllerConnected()
            controller.setSkipSilence(false)
            queue.clear()
            controller.player?.stop()
            controller.player?.clearMediaItems()
            // A torrent item's id is deterministic (hash + file index), so the download left by
            // the offline test below is still there for the streaming one — which then correctly
            // plays the file and fails an assertion about the stream. Deleting it here is test
            // isolation, and the confusion it caused is itself evidence the fix works.
            downloads.delete(ITEM_ID)
            // Watching, not listening: Listen mode plays the server's REMUXED audio, which is an
            // HLS playlist this stand-in does not serve (see the note on coverage below).
            container.appPreferences.setPlaybackMode(PlaybackMode.VIDEO)
            // And no automatic fetching, or it races the streaming test and wins: the fixture is
            // 69KB from localhost, so the copy lands within half a second and the queue then
            // correctly prefers it — which is the fix working and the test measuring the wrong
            // thing. It cost a confusing red run to notice.
            container.appPreferences.setAutoDownloadQueue(false)
        }
    }

    @After
    fun tearDown() {
        goOnline()
        container.appPreferences.setPlaybackMode(PlaybackMode.AUTO)
        container.appPreferences.setAutoDownloadQueue(true)
        runBlocking(Dispatchers.Main) {
            queue.clear()
            controller.player?.stop()
            controller.player?.clearMediaItems()
            downloads.delete(ITEM_ID)
        }
        runCatching { socket.close() }
    }

    /**
     * The whole path a person takes: search for a public-domain film, open the result, play it.
     *
     * Asserts the player was handed the STREAM URL, because "it played" is also satisfied by
     * playing something else entirely, and this is the one test that proves a torrent reaches the
     * one playback path rather than a second one built for it.
     */
    @Test
    fun `a torrent found by search plays through the same queue as everything else`() =
        runBlocking(Dispatchers.Main) {
            val hit = searchForOnePublicDomainFilm()
            val prepared = homeServer.prepare(hit.magnet)
            assertNotNull("the stand-in server never prepared the torrent", prepared)

            val items = TorrentPlayables.queueItems(homeServer, prepared!!)
            assertEquals("one playable file means one queue item", 1, items.size)

            queue.playNow(items.first())
            assertTrue("the torrent never started playing. ${whyNotPlaying()}", awaitPlaying())

            assertTrue(
                "a torrent must play from the server's stream URL, not something else. It played " +
                    "from \"${lastSource()}\"",
                lastSource()?.contains("/ts/stream/") == true,
            )
            assertTrue(
                "torrent playback stalled. The player is on ${PlaybackWaits.whatIsActuallyPlaying(controller)}",
                awaitPositionBeyond(PROGRESS_MS),
            )
        }

    /**
     * And offline, through the same one routing decision as a video or a podcast.
     *
     * Automatic queue downloads are off in this class, so this fetches deliberately — which is
     * also what proved a real gap: the request is `audioOnly = true`, and the trail records
     * `copy=full`, because a podcast-shaped download ignores that flag. A queued torrent therefore
     * pulls the video too. See `docs/todos/torrents-through-the-unified-route.md`.
     *
     * This is the half that had never been tested for ANY pillar until 2026-08-06, and the half
     * that was broken for videos. A torrent's queue entry is a `PlayHandle.Podcast`, so it takes
     * the path that always consulted the download store — but "should" is not evidence, and the
     * download itself (a plain ranged GET of a stream the server produces on demand) had never
     * been exercised at all. If this fails, that gap is real and
     * `docs/todos/torrents-through-the-unified-route.md` says what to do about it.
     */
    @Test
    fun `a downloaded torrent plays with the radios off`() = runBlocking(Dispatchers.Main) {
        val hit = searchForOnePublicDomainFilm()
        val prepared = homeServer.prepare(hit.magnet)
        val item = TorrentPlayables.queueItems(homeServer, prepared!!).first()

        downloads.download(item, audioOnly = true)
        val path = withTimeoutOrNull(DOWNLOAD_TIMEOUT_MS) {
            downloads.observe(item.item.id).first { it is DownloadState.Downloaded || it is DownloadState.Failed }
        }
        assertTrue(
            "a queued torrent must be downloadable for offline, or the queue's offline promise " +
                "excludes the whole pillar. It ended as: $path",
            path is DownloadState.Downloaded,
        )
        val file = (path as DownloadState.Downloaded).localPath

        goOffline()
        assertEquals("the radios did not actually go off", false, hasNetwork())

        queue.playNow(item)
        assertTrue("the downloaded torrent never started playing offline", awaitPlaying())
        assertTrue(
            "offline it must play the downloaded file, not the stream URL. It played from " +
                "\"${lastSource()}\"",
            lastSource()?.contains(file) == true,
        )
    }

    private suspend fun searchForOnePublicDomainFilm(): SearchHit.Torrent {
        val outcome = TorrentSearchSource(homeServer).search(SearchQuery(PUBLIC_DOMAIN_FILM), limit = 10, after = null)
        assertTrue("the search failed: $outcome", outcome is SearchOutcome.Success)
        val hits = (outcome as SearchOutcome.Success).page.items
        assertEquals("the stand-in server offers exactly one result", 1, hits.size)
        return hits.first() as SearchHit.Torrent
    }

    // ---- The stand-in home server: Prowlarr's search, TorrServer's add/get/stream ----

    private fun serveUntilClosed() {
        while (!socket.isClosed) {
            val client = runCatching { socket.accept() }.getOrNull() ?: return
            thread(isDaemon = true) { runCatching { respond(client) } }
        }
    }

    /** One request, parsed: what was asked for, any body it carried, and any byte range. */
    private data class Asked(val line: String, val body: String, val range: String?)

    private fun read(client: Socket): Asked? {
        val reader = client.getInputStream().bufferedReader()
        val line = reader.readLine() ?: return null
        var header = reader.readLine()
        var length = 0
        var range: String? = null
        while (!header.isNullOrBlank()) {
            if (header.startsWith("Content-Length:", ignoreCase = true)) {
                length = header.substringAfter(":").trim().toIntOrNull() ?: 0
            }
            if (header.startsWith("Range:", ignoreCase = true)) range = header.substringAfter(":").trim()
            header = reader.readLine()
        }
        val body = CharArray(length).also { if (length > 0) reader.read(it) }.concatToString()
        return Asked(line, body, range)
    }

    private fun respond(client: Socket) {
        client.use {
            val asked = read(client) ?: return
            val out = client.getOutputStream()
            when {
                "/prowlarr/api/v1/search" in asked.line -> out.json(PROWLARR_RESULT)
                "/ts/torrents" in asked.line && "\"action\":\"add\"" in asked.body -> out.json(ADDED)
                "/ts/torrents" in asked.line -> out.json(FILE_LIST)
                "/ts/stream/" in asked.line -> out.media(asked.line, asked.range)
                else -> out.write("HTTP/1.1 404 Not Found\r\nContent-Length: 0\r\n\r\n".toByteArray())
            }
            out.flush()
        }
    }

    private fun OutputStream.json(body: String) {
        write(
            (
                "HTTP/1.1 200 OK\r\nContent-Type: application/json\r\n" +
                    "Content-Length: ${body.toByteArray().size}\r\n\r\n"
                ).toByteArray(),
        )
        write(body.toByteArray())
    }

    /**
     * Range-aware for real, which is the whole reason the app can treat a torrent as an ordinary
     * URL — and it has to be, not merely claim to be.
     *
     * The first version advertised `Accept-Ranges: bytes` and then ignored every `Range` header,
     * answering 200 with the whole file. Locally the player opens at zero and never notices; on
     * CI's slower emulator a rebuffer asked for a range, got the whole file back with a 200, and
     * playback never started — a flake that passed here and failed there, which is the worst kind.
     */
    private fun OutputStream.media(requestLine: String, range: String?) {
        val from = range?.substringAfter("bytes=", "")?.substringBefore('-')?.toLongOrNull() ?: 0L
        val start = from.coerceIn(0, media.size.toLong()).toInt()
        val body = media.copyOfRange(start, media.size)
        val status = if (range == null) "200 OK" else "206 Partial Content"
        val contentRange = if (range == null) {
            ""
        } else {
            "Content-Range: bytes $start-${media.size - 1}/${media.size}\r\n"
        }
        write(
            (
                "HTTP/1.1 $status\r\nContent-Type: video/mp4\r\nAccept-Ranges: bytes\r\n" +
                    contentRange + "Content-Length: ${body.size}\r\n\r\n"
                ).toByteArray(),
        )
        if (!requestLine.startsWith("HEAD")) write(body)
    }

    private fun lastSource(): String? =
        controller.player?.currentMediaItem?.localConfiguration?.uri?.toString()

    private suspend fun awaitControllerConnected() {
        val connected = withTimeoutOrNull(START_TIMEOUT_MS) {
            while (controller.player == null) delay(POLL_MS)
            true
        }
        assertEquals("the media controller never connected to the playback service", true, connected)
    }

    private suspend fun awaitPlaying(): Boolean =
        PlaybackWaits.awaitStateOf(controller, ITEM_ID, START_TIMEOUT_MS) { it.isPlaying } != null

    /**
     * Why it is not playing, for the failure message.
     *
     * "the torrent never started playing" has now failed three CI runs in five and said nothing
     * either time, on commits that could not have affected it — so there is no way to tell a
     * refused audio focus from a dead socket from a player that errored. Guessing at a cure for a
     * failure that will not describe itself is how the last two days went; this makes the next
     * failure answer its own question, which is the same rule the app follows for reports.
     */
    private fun whyNotPlaying(): String {
        val player = controller.player
        val state = controller.state.value
        return "player=" + when (player?.playbackState) {
            Player.STATE_IDLE -> "IDLE"
            Player.STATE_BUFFERING -> "BUFFERING"
            Player.STATE_READY -> "READY"
            Player.STATE_ENDED -> "ENDED"
            else -> "no player"
        } +
            " playWhenReady=${player?.playWhenReady}" +
            " error=${player?.playerError?.errorCodeName ?: "none"}" +
            " item=${state?.itemId?.value ?: "nothing"}" +
            " source=${lastSource() ?: "none"}" +
            " queue=${queue.state.value.entries.size}" +
            " trail=" + Breadcrumbs.snapshot().takeLast(TRAIL_LINES).map { crumb -> crumb.message }
    }

    private suspend fun awaitPositionBeyond(target: Long): Boolean =
        PlaybackWaits.awaitStateOf(controller, ITEM_ID, START_TIMEOUT_MS) { it.positionMs > target } != null

    private companion object {
        /**
         * Genuinely public domain: its copyright notice was omitted from the original 1968 release
         * prints, which is why it is the standard example rather than a hopeful one.
         */
        const val PUBLIC_DOMAIN_FILM = "Night of the Living Dead 1968"

        /** What [TorrentPlayables] derives from the fixture's hash and file index. */
        val ITEM_ID = MediaItemId("torrent:0123456789abcdef0123456789abcdef01234567:1")

        /**
         * Generous, because CI's emulator is far slower than a local one and this waits on real
         * audio focus and a real decoder — the flake this test hit was a timeout, not a wrong answer.
         */
        const val START_TIMEOUT_MS = 60_000L
        const val DOWNLOAD_TIMEOUT_MS = 60_000L
        const val PROGRESS_MS = 1_000L
        const val POLL_MS = 200L

        /** Enough of the trail to see what the player was asked to do and what it said back. */
        const val TRAIL_LINES = 12

        /**
         * Shaped like the real thing, including the trap: `magnetUrl` is Prowlarr's download-proxy
         * URL and `guid` holds the actual magnet (verified against the live service 2026-08-01).
         * A regression that prefers the obvious-sounding field fails here.
         */
        val PROWLARR_RESULT = """
            [{
              "title": "Night of the Living Dead (1968) [Public Domain]",
              "guid": "magnet:?xt=urn:btih:0123456789abcdef0123456789abcdef01234567&dn=notld",
              "magnetUrl": "http://127.0.0.1/1/download?apikey=test-key",
              "seeders": 42,
              "size": 1048576,
              "indexer": "stand-in"
            }]
        """.trimIndent()

        const val ADDED =
            """{"hash":"0123456789abcdef0123456789abcdef01234567","name":"Night of the Living Dead (1968)"}"""

        val FILE_LIST = """
            {"file_stats":[{"id":1,"path":"Night of the Living Dead (1968)/feature.mp4","length":1048576}]}
        """.trimIndent()
    }
}
