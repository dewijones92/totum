package com.dewijones92.totum.playback

import androidx.test.ext.junit.rules.ActivityScenarioRule
import androidx.test.platform.app.InstrumentationRegistry
import com.dewijones92.totum.MainActivity
import com.dewijones92.totum.TotumApplication
import com.dewijones92.totum.common.Breadcrumbs
import com.dewijones92.totum.common.HttpUrl
import com.dewijones92.totum.domain.MediaItem
import com.dewijones92.totum.domain.MediaItemId
import com.dewijones92.totum.domain.PlayHandle
import com.dewijones92.totum.domain.PlayableItem
import com.dewijones92.totum.domain.SourceId
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.net.ServerSocket
import java.net.Socket
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread

/**
 * A stream that stops sending bytes mid-item must recover on its own.
 *
 * **The bug this exists for, from report 0.1.332 (3 August 2026).** A Dwarkesh video froze at
 * 652353ms with 48ms buffered, on a connection measuring 125Mbps. Four consecutive 30-second
 * snapshots show the position unchanged. It ended after **2 minutes 16 seconds**, because Dewi
 * dismissed the player and pressed play again — and the log shows exactly what that achieved: a
 * fresh extraction and a new URL, after which it played on normally.
 *
 * Every component was individually correct:
 *
 *  - [StallWatchdog] detected it at 20 seconds — and then deliberately returned, because it only
 *    acted on stalls near the END of an item.
 *  - [ExpiredStreamRecovery] knows how to re-resolve and replay — but listens for an ERROR, and a
 *    request that hangs raises none. Silence is not an error.
 *
 * So nothing was watching the one failure that actually happened. No unit test could catch it:
 * each part passes against its fake, and the gap is in the composition.
 *
 * **Why the fault is injected at the socket, not the network.** Turning the radios off makes
 * Android report itself offline and takes a different path entirely; `adb emu network delay`
 * barely bites (measured — throughput stayed in the megabits). Neither reproduces *this*: a
 * connection that is up, a server that answered 200, and bytes that simply stop arriving. A
 * socket that goes quiet mid-body is that exact failure, deterministically, in about half a
 * minute.
 *
 * **Every request hangs, and that is deliberate.** An earlier version served the media on a later
 * request, and it passed with the fix REMOVED — because ExoPlayer retries a dead connection by
 * itself and the retry got served. Recovery therefore proves nothing about our rescue. It also is
 * not what happened on the phone: `loadsOutstanding` grew 2 → 4 → 6 while nothing completed, so
 * every retry was failing too. With this server it runs 19 requests deep, which is that signature.
 *
 * So the assertion is on the watchdog's OWN decision, read from the breadcrumb trail — the
 * instrumented test shares a process with the app, so its trail is readable here. Verified both
 * ways: it passes with the mid-item replay and fails without it.
 *
 * **What it does NOT cover.** [PlaybackQueue.replayCurrent] only invalidates the resolver cache
 * for a [PlayHandle.Video], so replaying a plain URL like this one asks the same dead address
 * again. The rescue that genuinely rescues — a fresh extraction giving a new URL, which is what
 * Dewi's manual replay did — applies to videos, and reaching it needs yt-dlp and a network. This
 * test proves the watchdog now ACTS; that the action cures a hung YouTube stream rests on the
 * report's own evidence that a fresh URL fixed it by hand.
 */
class StalledStreamRecoveryTest {

    /** Foreground, or the platform refuses audio focus and nothing ever plays. */
    @get:Rule
    val activity = ActivityScenarioRule(MainActivity::class.java)

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val app = context.applicationContext as TotumApplication
    private val container get() = app.container
    private val controller get() = container.playbackController
    private val queue get() = container.playbackQueue

    private lateinit var server: ServerSocket
    private val requests = AtomicInteger(0)

    private val media = silentWav(seconds = MEDIA_SECONDS)

    @Before
    fun startServerAndEmptyTheQueue() {
        server = ServerSocket(0)
        thread(isDaemon = true, name = "stalling-media-server") { serveUntilClosed() }
        runBlocking(Dispatchers.Main) {
            awaitControllerConnected()
            // Explicitly OFF, not merely assumed off. This media is a SILENT wav, so if a
            // previous test left skip-silence on, sample-removal deletes the entire file and
            // playback never starts — which reads as "the item never played" and looks like a
            // playback bug. CI hit exactly that; the local order happened to hide it.
            controller.setSkipSilence(false)
            queue.clear()
            controller.player?.stop()
            controller.player?.clearMediaItems()
        }
    }

    @After
    fun tearDown() {
        runBlocking(Dispatchers.Main) {
            queue.clear()
            controller.player?.stop()
            controller.player?.clearMediaItems()
        }
        runCatching { server.close() }
    }

    @Test
    fun `a stream that goes quiet mid-item is replayed without being touched`() =
        runBlocking(Dispatchers.Main) {
            queue.playNow(hostedItem())

            // It starts fine — the first request does serve real audio, it just stops early.
            awaitPlaying()
            val frozen = awaitFrozenPosition()
            assertTrue(
                "the stream should have stalled mid-item once the bytes stopped, but playback " +
                    "reached ${controller.state.value?.positionMs}ms and kept going",
                frozen != null,
            )

            // Nothing below touches the player. The rescue has to come from the app itself.
            val replayed = awaitBreadcrumb("stall replay=")

            assertTrue(
                "playback froze at ${frozen}ms and the stall watchdog never replayed it — it " +
                    "either did not see the stall or declined to act, which is exactly report " +
                    "0.1.332: 2m16s of spinner, ended by hand. Requests served: ${requests.get()}",
                replayed,
            )
            assertTrue(
                "the replay must issue a FRESH request rather than waiting on the hung one",
                requests.get() >= 2,
            )
        }

    /**
     * And when it never recovers, the queue moves on instead of replaying forever.
     *
     * This is the reported-online-but-broken shape: the network says VALIDATED, nothing arrives,
     * and no number of fresh connections to the same dead address changes that. Each rescue moves
     * the position, which re-arms the watchdog — so before the budget existed, a dead stream was
     * replayed every ~25 seconds indefinitely, restarting the spinner each time and saying nothing.
     *
     * Slow by nature: two rescues plus the give-up is over a minute of real stalling. It is the
     * only way to observe the whole escalation against a real player, so it earns the wall clock.
     */
    @Test
    fun `a stream that never recovers is given up on and the queue moves on`() =
        runBlocking(Dispatchers.Main) {
            // Something playable to land on, so "moved on" cannot be confused with "gave up and
            // stopped" — a local file needs no network and cannot itself stall.
            queue.enqueue(playableFromDisk())

            queue.playNow(hostedItem())
            awaitPlaying()

            val movedOn = withTimeoutOrNull(GIVE_UP_TIMEOUT_MS) {
                while (controller.state.value?.itemId?.value != NEXT_ID) delay(POLL_MS)
                true
            } ?: false

            val trail = Breadcrumbs.snapshot().map { it.message }
            assertTrue(
                "a stream that never recovers must be given up on rather than replayed forever. " +
                    "Requests served: ${requests.get()}. Trail: " +
                    trail.filter { "rescue" in it || "Giving up" in it || "exhausted" in it },
                trail.any { "Giving up on this stream" in it },
            )
            assertTrue("after giving up, the queue must move on to something playable", movedOn)
        }

    /**
     * Serves [SERVED_BEFORE_HANG_BYTES] and then holds the socket open sending nothing more, which
     * is the failure being reproduced. A CLOSED socket would raise an error and take the
     * [ExpiredStreamRecovery] path instead; the whole point of this one is that it is silent.
     */
    private fun serveUntilClosed() {
        while (!server.isClosed) {
            val socket = runCatching { server.accept() }.getOrNull() ?: return
            requests.incrementAndGet()
            thread(isDaemon = true) { runCatching { respond(socket, hang = true) } }
        }
    }

    private fun respond(socket: Socket, hang: Boolean) {
        socket.use {
            val request = StringBuilder()
            val input = socket.getInputStream().bufferedReader()
            var line = input.readLine()
            while (!line.isNullOrBlank()) {
                request.append(line).append('\n')
                line = input.readLine()
            }
            // Range is ignored on purpose: every request gets the whole thing from the start, so
            // the replay is a clean fetch and the test does not depend on range arithmetic.
            val out = socket.getOutputStream()
            out.write(
                (
                    "HTTP/1.1 200 OK\r\n" +
                        "Content-Type: audio/wav\r\n" +
                        "Content-Length: ${media.size}\r\n" +
                        "Accept-Ranges: none\r\n\r\n"
                    ).toByteArray(),
            )
            if (hang) {
                out.write(media, 0, SERVED_BEFORE_HANG_BYTES)
                out.flush()
                // Quiet, but not gone. Long enough to outlast the watchdog and the assertions.
                Thread.sleep(HANG_MS)
            } else {
                out.write(media)
                out.flush()
            }
        }
    }

    /** A silent local file: playable with no network, so it cannot stall in its own right. */
    private fun playableFromDisk(): PlayableItem {
        val file = java.io.File(context.cacheDir, "$NEXT_ID.wav")
        file.writeBytes(media)
        return PlayableItem(
            item = MediaItem(
                id = MediaItemId(NEXT_ID),
                sourceId = SourceId("test"),
                title = "something that works",
                publishedAt = null,
                duration = null,
                mediaUrl = null,
            ),
            handle = PlayHandle.Podcast(file.absolutePath),
        )
    }

    private fun hostedItem() = PlayableItem(
        item = MediaItem(
            id = MediaItemId("stalling"),
            sourceId = SourceId("test"),
            title = "a stream that goes quiet",
            publishedAt = null,
            duration = null,
            mediaUrl = HttpUrl.of("http://127.0.0.1:${server.localPort}/media.wav"),
        ),
        // No local path: the bytes must come over the socket, or nothing is being tested.
        handle = PlayHandle.Podcast(),
    )

    private suspend fun awaitControllerConnected() {
        val connected = withTimeoutOrNull(START_TIMEOUT_MS) {
            while (controller.player == null) delay(POLL_MS)
            true
        }
        assertEquals("the media controller never connected to the playback service", true, connected)
    }

    private suspend fun awaitPlaying() {
        val playing = withTimeoutOrNull(START_TIMEOUT_MS) {
            while (controller.state.value?.isPlaying != true) delay(POLL_MS)
            true
        }
        assertEquals(whyItNeverPlayed(), true, playing)
    }

    /**
     * What the player actually reported, rather than a guess at why.
     *
     * The old message asserted "audio focus was refused" — a plausible cause that was never
     * evidence. This failed once in a full-suite run on 2026-08-19 and passed both in isolation and
     * in CI, so a recurrence needs the state, not another theory. Same rule as the reel test's
     * waitForPlaying: a wait that times out has to say what it was looking at.
     */
    private fun whyItNeverPlayed(): String {
        val state = controller.state.value
            ?: return "the hosted item never started: play() produced NO state at all after " +
                "${START_TIMEOUT_MS}ms, so it never reached the player"
        return "the hosted item never started playing after ${START_TIMEOUT_MS}ms — " +
            "playing=${state.isPlaying} buffering=${state.isBuffering} wants=${state.wantsToPlay} " +
            "ended=${state.hasEnded} position=${state.positionMs} item=${state.itemId.value}. " +
            "A started-but-not-playing state on a device usually means audio focus was refused, " +
            "which happens when the screen is off or the app is not foreground."
    }

    /** The position that stops moving, or null if playback never stalls. */
    private suspend fun awaitFrozenPosition(): Long? = withTimeoutOrNull(STALL_TIMEOUT_MS) {
        var last = -1L
        var stillFor = 0L
        while (stillFor < FROZEN_MS) {
            delay(POLL_MS)
            val now = controller.state.value?.positionMs ?: 0
            stillFor = if (now == last) stillFor + POLL_MS else 0
            last = now
        }
        last
    }

    /** Whether the app's own trail shows [text], which is how a decision is observed here. */
    private suspend fun awaitBreadcrumb(text: String): Boolean =
        withTimeoutOrNull(RECOVERY_TIMEOUT_MS) {
            while (Breadcrumbs.snapshot().none { text in it.message }) delay(POLL_MS)
            true
        } ?: false

    /**
     * Silent 8-bit 8kHz PCM, generated rather than committed so the repository carries no audio.
     *
     * Long enough that a stall a few seconds in is comfortably mid-item: [StallWatchdog] treats
     * anything within 15 seconds of the end as "over" and ADVANCES instead of replaying, so a
     * short clip would exercise the wrong branch and quietly prove nothing.
     */
    private fun silentWav(seconds: Int): ByteArray {
        val samples = SAMPLE_RATE * seconds
        val header = ByteBuffer.allocate(WAV_HEADER_BYTES).order(ByteOrder.LITTLE_ENDIAN)
        header.put("RIFF".toByteArray())
        header.putInt(WAV_HEADER_BYTES - RIFF_PREAMBLE + samples)
        header.put("WAVEfmt ".toByteArray())
        header.putInt(FMT_CHUNK_BYTES)
        header.putShort(PCM_FORMAT)
        header.putShort(MONO)
        header.putInt(SAMPLE_RATE)
        header.putInt(SAMPLE_RATE)
        header.putShort(BLOCK_ALIGN)
        header.putShort(BITS_PER_SAMPLE)
        header.put("data".toByteArray())
        header.putInt(samples)
        val out = ByteArrayOutputStream()
        out.write(header.array())
        // Silence at eight-bit unsigned depth is 0x80, not zero.
        out.write(ByteArray(samples) { SILENCE })
        return out.toByteArray()
    }

    private companion object {
        const val MEDIA_SECONDS = 120

        /** ~5 seconds of audio: enough to start playing, then nothing. */
        const val SERVED_BEFORE_HANG_BYTES = 40_000
        const val HANG_MS = 120_000L

        const val START_TIMEOUT_MS = 30_000L

        /** Must outlast StallWatchdog.STALL_MS (20s) plus its 5s sampling interval. */
        const val STALL_TIMEOUT_MS = 40_000L
        const val RECOVERY_TIMEOUT_MS = 60_000L
        const val FROZEN_MS = 3_000L
        const val POLL_MS = 200L
        const val NEXT_ID = "something-that-works"

        /** Two rescues at ~25s each, the give-up, and the advance — plus room for a slow emulator. */
        const val GIVE_UP_TIMEOUT_MS = 150_000L
        const val SAMPLE_RATE = 8_000
        const val WAV_HEADER_BYTES = 44
        const val RIFF_PREAMBLE = 8
        const val FMT_CHUNK_BYTES = 16
        const val PCM_FORMAT: Short = 1
        const val MONO: Short = 1
        const val BLOCK_ALIGN: Short = 1
        const val BITS_PER_SAMPLE: Short = 8
        const val SILENCE: Byte = -128
    }
}
