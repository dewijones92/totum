package com.dewijones92.totum.playback

import android.os.Handler
import android.os.Looper
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

@UnstableApi
@RunWith(AndroidJUnit4::class)
class AnAviWithMp3CutAcrossFramesPlaysTest {

    private val main = Handler(Looper.getMainLooper())

    private fun <T> onMain(block: () -> T): T {
        val result = AtomicReference<T>()
        val done = CountDownLatch(1)
        main.post {
            result.set(block())
            done.countDown()
        }
        done.await(5, TimeUnit.SECONDS)
        return result.get()
    }

    @Test
    fun anAviWhoseMp3ChunksStraddleFramesPlaysToTheEnd() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val avi = instrumentation.context.assets.open("mp3-cut-across-frames.avi").use { it.readBytes() }
        LoopbackHttp { LoopbackHttp.Reply("200 OK", avi) }.use { media ->
            val error = AtomicReference<PlaybackException?>(null)
            val ended = CountDownLatch(1)
            val player = onMain {
                val context = instrumentation.targetContext
                ExoPlayer.Builder(context).setMediaSourceFactory(playbackSources(context)).build().apply {
                    addListener(
                        object : Player.Listener {
                            override fun onPlayerError(e: PlaybackException) {
                                error.set(e)
                                ended.countDown()
                            }

                            override fun onPlaybackStateChanged(state: Int) {
                                if (state == Player.STATE_ENDED) ended.countDown()
                            }
                        },
                    )
                    volume = 0f
                    setMediaItem(MediaItem.fromUri(media.url))
                    prepare()
                    play()
                }
            }
            ended.await(WATCH_MS, TimeUnit.MILLISECONDS)
            val position = onMain { player.currentPosition.also { player.release() } }
            android.util.Log.i("dewidebug", "avi mp3 test: position=$position error=${error.get()?.errorCodeName}")
            assertEquals(null, error.get()?.errorCodeName)
            assertTrue("reached $position ms", position >= PLAYED_TO_MS)
        }
    }

    private companion object {
        const val WATCH_MS = 20_000L
        const val PLAYED_TO_MS = 5_500L
    }
}
