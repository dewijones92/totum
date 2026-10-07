package com.dewijones92.totum.playback

import android.os.Handler
import android.os.Looper
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.upstream.DefaultLoadErrorHandlingPolicy
import androidx.media3.exoplayer.upstream.LoadErrorHandlingPolicy
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

@UnstableApi
@RunWith(AndroidJUnit4::class)
class AYoungUrlPlaysWhenYouTubeAcceptsItTest {

    private sealed interface Outcome {
        data class Ready(val afterIssueMs: Long) : Outcome
        data class Failed(val afterIssueMs: Long, val error: PlaybackException) : Outcome
    }

    private fun playFreshUrl(policy: LoadErrorHandlingPolicy): Pair<Outcome, Int> {
        val issuedMs = System.currentTimeMillis() - ISSUED_BEFORE_PLAY_MS
        val acceptsFromMs = issuedMs + YOUTUBE_ACCEPTS_AFTER_MS
        val wav = wav()
        LoopbackHttp { _ ->
            if (System.currentTimeMillis() < acceptsFromMs) {
                LoopbackHttp.Reply("403 Forbidden", ByteArray(0))
            } else {
                LoopbackHttp.Reply("200 OK", wav)
            }
        }.use { server ->
            val expire = (issuedMs + YoungStreamUrl.LEASE_MS) / MILLIS_PER_SECOND
            val url = "${server.url}?expire=$expire&c=WEB_EMBEDDED_PLAYER"
            val outcome = AtomicReference<Outcome>()
            val done = CountDownLatch(1)
            val context = InstrumentationRegistry.getInstrumentation().targetContext
            val main = Handler(Looper.getMainLooper())
            var player: ExoPlayer? = null
            main.post {
                player = ExoPlayer.Builder(context)
                    .setMediaSourceFactory(
                        DefaultMediaSourceFactory(context)
                            .setDataSourceFactory(DefaultHttpDataSource.Factory())
                            .setLoadErrorHandlingPolicy(policy),
                    )
                    .build()
                    .apply {
                        addListener(
                            object : Player.Listener {
                                override fun onPlaybackStateChanged(state: Int) {
                                    if (state == Player.STATE_READY && outcome.get() == null) {
                                        outcome.set(Outcome.Ready(System.currentTimeMillis() - issuedMs))
                                        done.countDown()
                                    }
                                }

                                override fun onPlayerError(error: PlaybackException) {
                                    outcome.compareAndSet(
                                        null,
                                        Outcome.Failed(System.currentTimeMillis() - issuedMs, error),
                                    )
                                    done.countDown()
                                }
                            },
                        )
                        setMediaItem(MediaItem.fromUri(url))
                        prepare()
                    }
            }
            done.await(WAIT_S, TimeUnit.SECONDS)
            main.post { player?.release() }
            return (outcome.get() ?: error("neither ready nor failed in ${WAIT_S}s")) to server.requestsAnswered
        }
    }

    @Test
    fun aFreshUrlStartsSoonAfterYouTubeAcceptsIt() {
        val (outcome, requests) = playFreshUrl(DoNotRetryWhatSabrHasGivenUpOn())

        assertTrue("expected playback, got $outcome after $requests requests", outcome is Outcome.Ready)
        val readyAt = (outcome as Outcome.Ready).afterIssueMs
        assertTrue(
            "ready ${readyAt}ms after issue; YouTube accepted it at $YOUTUBE_ACCEPTS_AFTER_MS ms",
            readyAt in YOUTUBE_ACCEPTS_AFTER_MS..YOUTUBE_ACCEPTS_AFTER_MS + PROMPT_MS,
        )
    }

    @Test
    fun theDefaultPolicyGivesUpOnTheSameUrl() {
        val (outcome, requests) = playFreshUrl(DefaultLoadErrorHandlingPolicy())

        assertTrue("the control must fail, or the test above proves nothing: $outcome", outcome is Outcome.Failed)
        assertEquals(DEFAULT_ATTEMPTS, requests)
    }

    private fun wav(): ByteArray {
        val samples = SAMPLE_RATE
        val data = samples * 2
        return ByteBuffer.allocate(WAV_HEADER + data).order(ByteOrder.LITTLE_ENDIAN).apply {
            put("RIFF".toByteArray()).putInt(WAV_HEADER - 8 + data).put("WAVE".toByteArray())
            put("fmt ".toByteArray()).putInt(16).putShort(1).putShort(1)
            putInt(SAMPLE_RATE).putInt(SAMPLE_RATE * 2).putShort(2).putShort(16)
            put("data".toByteArray()).putInt(data)
        }.array()
    }

    private companion object {
        const val ISSUED_BEFORE_PLAY_MS = 1_000L
        const val YOUTUBE_ACCEPTS_AFTER_MS = 4_500L
        const val PROMPT_MS = 1_500L
        const val DEFAULT_ATTEMPTS = 4
        const val WAIT_S = 20L
        const val MILLIS_PER_SECOND = 1_000L
        const val SAMPLE_RATE = 8_000
        const val WAV_HEADER = 44
    }
}
