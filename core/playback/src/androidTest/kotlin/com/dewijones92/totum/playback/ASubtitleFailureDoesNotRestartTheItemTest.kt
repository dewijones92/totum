package com.dewijones92.totum.playback

import android.net.Uri
import android.os.Handler
import android.os.Looper
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@UnstableApi
@RunWith(AndroidJUnit4::class)
class ASubtitleFailureDoesNotRestartTheItemTest {

    private data class Jump(val fromMs: Long, val toMs: Long, val reason: Int)

    private class Run(
        val jumps: List<Jump>,
        val lowestAfterChoosingMs: Long,
        val choseAtMs: Long,
        val subtitleRequests: Int,
    ) {
        override fun toString() =
            "chose subtitles at ${choseAtMs}ms, lowest after ${lowestAfterChoosingMs}ms, " +
                "$subtitleRequests subtitle request(s), jumps $jumps"
    }

    private val main = Handler(Looper.getMainLooper())

    private fun <T> onMain(block: () -> T): T {
        var result: T? = null
        val done = CountDownLatch(1)
        main.post {
            result = block()
            done.countDown()
        }
        done.await(MAIN_WAIT_S, TimeUnit.SECONDS)
        @Suppress("UNCHECKED_CAST")
        return result as T
    }

    private fun play(guarded: Boolean): Run {
        val wav = wav()
        LoopbackHttp { LoopbackHttp.Reply("200 OK", wav) }.use { media ->
            TimedTextServer(hangOn = 0).use { subtitles ->
                val jumps = Collections.synchronizedList(mutableListOf<Jump>())
                val player = onMain { start(media.url, subtitles.url, guarded, jumps) }
                val deadline = System.currentTimeMillis() + START_WAIT_MS
                while (onMain { player.currentPosition } < CHOOSE_AT_MS && System.currentTimeMillis() < deadline) {
                    Thread.sleep(POLL_MS)
                }
                val choseAt = onMain {
                    player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
                        .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
                        .setPreferredTextLanguage("en")
                        .build()
                    player.currentPosition
                }
                var lowest = Long.MAX_VALUE
                val watchUntil = System.currentTimeMillis() + WATCH_MS
                while (System.currentTimeMillis() < watchUntil) {
                    lowest = minOf(lowest, onMain { player.currentPosition })
                    Thread.sleep(POLL_MS)
                }
                onMain { player.release() }
                return Run(jumps.toList(), lowest, choseAt, subtitles.requests)
            }
        }
    }

    private fun start(mediaUrl: String, subtitleUrl: String, guarded: Boolean, jumps: MutableList<Jump>): ExoPlayer {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        return ExoPlayer.Builder(context)
            .setMediaSourceFactory(
                DefaultMediaSourceFactory(context).setDataSourceFactory(
                    DefaultHttpDataSource.Factory().setReadTimeoutMs(TIMEOUT_MS).setConnectTimeoutMs(TIMEOUT_MS),
                ),
            )
            .build()
            .apply {
                addListener(
                    object : Player.Listener {
                        override fun onPositionDiscontinuity(
                            oldPosition: Player.PositionInfo,
                            newPosition: Player.PositionInfo,
                            reason: Int,
                        ) {
                            jumps += Jump(oldPosition.positionMs, newPosition.positionMs, reason)
                        }
                    },
                )
                if (guarded) addListener(UnaskedRewindGuard { this })
                val subtitle = MediaItem.SubtitleConfiguration.Builder(Uri.parse(subtitleUrl))
                    .setMimeType(MimeTypes.TEXT_VTT)
                    .setLanguage("en")
                    .build()
                setMediaItem(
                    MediaItem.Builder().setUri(mediaUrl).setSubtitleConfigurations(listOf(subtitle)).build()
                )
                trackSelectionParameters = trackSelectionParameters.buildUpon()
                    .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
                    .build()
                prepare()
                play()
            }
    }

    @Test
    fun withoutTheGuardAFailedSubtitleLoadSendsTheItemBackToTheStart() {
        val run = play(guarded = false)
        android.util.Log.i("dewidebug", "subtitle-reset control: $run")

        assertTrue(
            "the control must reproduce the bug, or the test below proves nothing: $run",
            run.jumps.any { it.reason == Player.DISCONTINUITY_REASON_INTERNAL && it.toMs < REWOUND_MS },
        )
    }

    @Test
    fun withTheGuardTheItemCarriesOnFromWhereItWas() {
        val run = play(guarded = true)
        android.util.Log.i("dewidebug", "subtitle-reset guarded: $run")

        assertTrue(
            "playback must carry on from where it was: $run",
            run.lowestAfterChoosingMs >= run.choseAtMs - LEEWAY_MS,
        )
    }

    /**
     * Serves a subtitle the way /api/timedtext did on the phone: with no length (the body simply ends
     * when the connection closes), and with one request that is taken and never answered.
     */
    private class TimedTextServer(private val hangOn: Int) : AutoCloseable {
        private val listener = java.net.ServerSocket(0)
        private val held = Collections.synchronizedList(mutableListOf<java.net.Socket>())
        private val served = java.util.concurrent.atomic.AtomicInteger()
        val url: String = "http://127.0.0.1:${listener.localPort}/api/timedtext"
        val requests: Int get() = served.get()

        init {
            kotlin.concurrent.thread(isDaemon = true, name = "timedtext-http") {
                while (!listener.isClosed) runCatching { answer(listener.accept()) }
            }
        }

        private fun answer(client: java.net.Socket) {
            val n = served.getAndIncrement()
            if (n == hangOn) {
                held += client
                return
            }
            client.use {
                val input = it.getInputStream()
                val head = StringBuilder()
                while (!head.endsWith("\r\n\r\n")) head.append(input.read().takeIf { b -> b >= 0 }?.toChar() ?: break)
                it.getOutputStream().apply {
                    write("HTTP/1.1 200 OK\r\nContent-Type: text/vtt\r\nConnection: close\r\n\r\n".toByteArray())
                    write(VTT)
                    flush()
                }
            }
        }

        override fun close() {
            held.forEach { runCatching { it.close() } }
            listener.close()
        }
    }

    private fun wav(): ByteArray {
        val samples = SAMPLE_RATE * SECONDS
        val data = samples * 2
        return ByteBuffer.allocate(WAV_HEADER + data).order(ByteOrder.LITTLE_ENDIAN).apply {
            put("RIFF".toByteArray()).putInt(WAV_HEADER - 8 + data).put("WAVE".toByteArray())
            put("fmt ".toByteArray()).putInt(16).putShort(1).putShort(1)
            putInt(SAMPLE_RATE).putInt(SAMPLE_RATE * 2).putShort(2).putShort(16)
            put("data".toByteArray()).putInt(data)
        }.array()
    }

    private companion object {
        val VTT = "WEBVTT\n\n00:00:00.000 --> 01:00:00.000\nA placeholder caption\n".toByteArray()
        const val SAMPLE_RATE = 8_000
        const val SECONDS = 60
        const val WAV_HEADER = 44
        const val TIMEOUT_MS = 2_000
        const val CHOOSE_AT_MS = 6_000L
        const val START_WAIT_MS = 20_000L
        const val WATCH_MS = 8_000L
        const val POLL_MS = 100L
        const val MAIN_WAIT_S = 5L
        const val REWOUND_MS = 1_000L
        const val LEEWAY_MS = 1_500L
    }
}
