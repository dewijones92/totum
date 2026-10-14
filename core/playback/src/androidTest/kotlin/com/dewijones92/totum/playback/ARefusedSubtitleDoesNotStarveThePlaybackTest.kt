package com.dewijones92.totum.playback

import android.net.Uri
import android.os.Handler
import android.os.Looper
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

@UnstableApi
@RunWith(AndroidJUnit4::class)
class ARefusedSubtitleDoesNotStarveThePlaybackTest {

    private val main = Handler(Looper.getMainLooper())

    private fun <T> onMain(block: () -> T): T {
        val result = AtomicReference<T>()
        val done = CountDownLatch(1)
        main.post {
            result.set(block())
            done.countDown()
        }
        done.await(MAIN_WAIT_S, TimeUnit.SECONDS)
        return result.get()
    }

    @Test
    fun aRefusedSubtitlePlaysOnWithoutRebuffering() {
        val wav = wav()
        LoopbackHttp { LoopbackHttp.Reply("200 OK", wav) }.use { media ->
            LoopbackHttp { LoopbackHttp.Reply("429 Too Many Requests", ByteArray(0)) }.use { refusing ->
                val subtitleUrl = refusing.url.substringBeforeLast("/videoplayback") +
                    "/api/timedtext?v=x&lang=ar&tlang=en&fmt=ttml"
                val rebuffers = AtomicInteger()
                val error = AtomicReference<PlaybackException?>()
                val player = onMain { start(media.url, subtitleUrl, rebuffers, error) }
                Thread.sleep(WATCH_MS)
                val position = onMain { player.currentPosition }
                onMain { player.release() }
                android.util.Log.i(
                    "dewidebug",
                    "refused-subtitle: rebuffers=${rebuffers.get()} position=${position}ms " +
                        "subtitleRequests=${refusing.requestsAnswered} error=${error.get()}",
                )

                assertNull("playback must not fail", error.get())
                assertTrue("the subtitles must have been asked for", refusing.requestsAnswered > 0)
                assertEquals("no rebuffering once playing (reached ${position}ms)", 0, rebuffers.get())
            }
        }
    }

    private fun start(
        mediaUrl: String,
        subtitleUrl: String,
        rebuffers: AtomicInteger,
        error: AtomicReference<PlaybackException?>,
    ): ExoPlayer {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        return ExoPlayer.Builder(context)
            .setMediaSourceFactory(playbackSources(context))
            .build()
            .apply {
                var started = false
                addListener(
                    object : Player.Listener {
                        override fun onPlaybackStateChanged(state: Int) {
                            if (state == Player.STATE_READY) started = true
                            if (state == Player.STATE_BUFFERING && started) rebuffers.incrementAndGet()
                        }

                        override fun onPlayerError(e: PlaybackException) = error.set(e)
                    },
                )
                val subtitle = MediaItem.SubtitleConfiguration.Builder(Uri.parse(subtitleUrl))
                    .setMimeType(MimeTypes.APPLICATION_TTML)
                    .setLanguage("en")
                    .build()
                setMediaItem(MediaItem.Builder().setUri(mediaUrl).setSubtitleConfigurations(listOf(subtitle)).build())
                trackSelectionParameters = trackSelectionParameters.buildUpon().setPreferredTextLanguage("en").build()
                prepare()
                play()
            }
    }

    private fun wav(): ByteArray {
        val data = SAMPLE_RATE * CHANNELS * 2 * SECONDS
        return ByteBuffer.allocate(WAV_HEADER + data).order(ByteOrder.LITTLE_ENDIAN).apply {
            put("RIFF".toByteArray()).putInt(WAV_HEADER - 8 + data).put("WAVE".toByteArray())
            put("fmt ".toByteArray()).putInt(16).putShort(1).putShort(CHANNELS.toShort())
            putInt(SAMPLE_RATE).putInt(SAMPLE_RATE * CHANNELS * 2).putShort((CHANNELS * 2).toShort()).putShort(16)
            put("data".toByteArray()).putInt(data)
        }.array()
    }

    private companion object {
        const val SAMPLE_RATE = 44_100
        const val CHANNELS = 2
        const val SECONDS = 90
        const val WAV_HEADER = 44
        const val WATCH_MS = 25_000L
        const val MAIN_WAIT_S = 5L
    }
}
