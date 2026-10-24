package com.dewijones92.totum.playback

import android.os.Handler
import android.os.Looper
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.dewijones92.totum.common.HomeServerAuth
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@UnstableApi
@RunWith(AndroidJUnit4::class)
class TheHomeServerIsSentThisDevicesTokenTest {

    private val main = Handler(Looper.getMainLooper())

    @After fun forget() = HomeServerAuth.use({ null }, { "" })

    private fun headsWhenPlaying(): List<String> {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val avi = instrumentation.context.assets.open("mp3-cut-across-frames.avi").use { it.readBytes() }
        return LoopbackHttp { LoopbackHttp.Reply("200 OK", avi) }.use { media ->
            var player: ExoPlayer? = null
            val started = CountDownLatch(1)
            main.post {
                val context = instrumentation.targetContext
                player = ExoPlayer.Builder(context).setMediaSourceFactory(playbackSources(context)).build().apply {
                    volume = 0f
                    setMediaItem(MediaItem.fromUri(media.url))
                    prepare()
                }
                started.countDown()
            }
            started.await(5, TimeUnit.SECONDS)
            val until = System.currentTimeMillis() + WAIT_MS
            while (media.requestsAnswered == 0 && System.currentTimeMillis() < until) Thread.sleep(50)
            Thread.sleep(SETTLE_MS)
            val done = CountDownLatch(1)
            main.post {
                player?.release()
                done.countDown()
            }
            done.await(5, TimeUnit.SECONDS)
            media.heads.toList()
        }
    }

    @Test
    fun everyRequestToTheHomeServerCarriesThisDevicesToken() {
        HomeServerAuth.use({ "127.0.0.1" }, { "device-token" })

        val heads = headsWhenPlaying()

        assertTrue("no request was made", heads.isNotEmpty())
        heads.forEach { assertTrue(it, it.contains("X-Totum-Token: device-token", ignoreCase = true)) }
    }

    @Test
    fun anyOtherServerIsNeverSentIt() {
        HomeServerAuth.use({ "totum.home.test" }, { "device-token" })

        val heads = headsWhenPlaying()

        assertTrue("no request was made", heads.isNotEmpty())
        heads.forEach { assertTrue(it, !it.contains("X-Totum-Token", ignoreCase = true)) }
    }

    private companion object {
        const val WAIT_MS = 10_000L
        const val SETTLE_MS = 1_000L
    }
}
