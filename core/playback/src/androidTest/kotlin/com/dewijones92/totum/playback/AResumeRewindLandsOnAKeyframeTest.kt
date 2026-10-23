package com.dewijones92.totum.playback

import android.os.Handler
import android.os.Looper
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

@UnstableApi
@RunWith(AndroidJUnit4::class)
class AResumeRewindLandsOnAKeyframeTest {

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
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

    private fun video(): File = File(instrumentation.targetContext.cacheDir, ASSET).also { out ->
        instrumentation.context.assets.open(ASSET).use { input -> out.outputStream().use(input::copyTo) }
    }

    private fun landing(seek: ExoPlayer.() -> Unit): Long {
        val file = video()
        val player = onMain {
            ExoPlayer.Builder(instrumentation.targetContext).build().apply {
                addListener(
                    object : Player.Listener {
                        override fun onPositionDiscontinuity(
                            old: Player.PositionInfo,
                            new: Player.PositionInfo,
                            reason: Int,
                        ) {
                            android.util.Log.i(
                                "dewidebug",
                                "keyframe-rewind: jump ${old.positionMs} -> ${new.positionMs} reason $reason"
                            )
                        }
                    },
                )
                setMediaItem(MediaItem.fromUri(file.toURI().toString()))
                prepare()
                play()
            }
        }
        val deadline = System.currentTimeMillis() + PLAY_WAIT_MS
        while (onMain { player.currentPosition } < PLAYED_TO_MS && System.currentTimeMillis() < deadline) {
            Thread.sleep(POLL_MS)
        }
        onMain {
            player.pause()
            player.seek()
        }
        val ready = System.currentTimeMillis() + READY_WAIT_MS
        while (onMain { player.playbackState } != Player.STATE_READY && System.currentTimeMillis() < ready) {
            Thread.sleep(POLL_MS)
        }
        val landed = onMain { player.currentPosition }
        val state = onMain { player.playbackState }
        onMain { player.release() }
        android.util.Log.i(
            "dewidebug",
            "keyframe-rewind: landed at ${landed}ms, state $state (${Player.STATE_READY} = ready)"
        )
        return landed
    }

    @Test
    fun theResumeRewindLandsOnTheKeyframeBeforeItsTarget() {
        val landed = landing { rewindToKeyframeBefore(TARGET_MS) }
        assertTrue(
            "landed at ${landed}ms, want the keyframe at ${KEYFRAME_MS}ms",
            landed in KEYFRAME_MS..KEYFRAME_MS + SLACK_MS
        )
    }

    @Test
    fun anOrdinarySeekStillLandsExactly() {
        val landed = landing { seekTo(TARGET_MS) }
        assertTrue("landed at ${landed}ms, want ${TARGET_MS}ms", landed in TARGET_MS..TARGET_MS + SLACK_MS)
    }

    private companion object {
        const val ASSET = "keyframes-every-5s.mp4"
        const val PLAYED_TO_MS = 12_000L
        const val TARGET_MS = 8_000L
        const val KEYFRAME_MS = 5_000L
        const val SLACK_MS = 200L
        const val PLAY_WAIT_MS = 30_000L
        const val POLL_MS = 200L
        const val READY_WAIT_MS = 15_000L
        const val MAIN_WAIT_S = 5L
    }
}
