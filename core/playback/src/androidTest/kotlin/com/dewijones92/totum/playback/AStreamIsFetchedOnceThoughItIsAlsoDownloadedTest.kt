package com.dewijones92.totum.playback

import android.net.Uri
import android.os.Handler
import android.os.Looper
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.BaseDataSource
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.cache.NoOpCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.dewijones92.totum.domain.PlayerSpot
import com.dewijones92.totum.domain.TeeOutcome
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.BitSet
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

@UnstableApi
@RunWith(AndroidJUnit4::class)
class AStreamIsFetchedOnceThoughItIsAlsoDownloadedTest {

    private val main = Handler(Looper.getMainLooper())
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    private class CountingUpstream(private val data: ByteArray, private val paceMs: Long) : DataSource.Factory {
        val networkBytes = AtomicLong()
        val refetchedBytes = AtomicLong()
        private val seen = BitSet(data.size)
        val ranges = java.util.concurrent.CopyOnWriteArrayList<String>()

        override fun createDataSource(): DataSource = object : BaseDataSource(true) {
            private var position = 0L
            private var remaining = 0L
            private var opened: Uri? = null

            override fun open(dataSpec: DataSpec): Long {
                transferInitializing(dataSpec)
                position = dataSpec.position
                remaining = if (dataSpec.length == C.LENGTH_UNSET.toLong()) data.size - position else dataSpec.length
                opened = dataSpec.uri
                ranges += "$position+$remaining"
                transferStarted(dataSpec)
                return remaining
            }

            override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                if (remaining == 0L) return C.RESULT_END_OF_INPUT
                val n = minOf(length.toLong(), remaining, SLICE).toInt()
                data.copyInto(buffer, offset, position.toInt(), position.toInt() + n)
                synchronized(seen) {
                    for (i in position.toInt() until position.toInt() + n) {
                        if (seen[i]) refetchedBytes.incrementAndGet() else seen.set(i)
                    }
                }
                position += n
                remaining -= n
                networkBytes.addAndGet(n.toLong())
                bytesTransferred(n)
                Thread.sleep(paceMs)
                return n
            }

            override fun getUri(): Uri? = opened

            override fun close() {
                transferEnded()
            }
        }
    }

    private fun <T> onMain(block: () -> T): T {
        val result = AtomicReference<T>()
        val done = CountDownLatch(1)
        main.post {
            result.set(block())
            done.countDown()
        }
        check(done.await(MAIN_WAIT_S, TimeUnit.SECONDS)) { "the main thread did not answer" }
        return result.get()
    }

    private fun wav(seconds: Int): ByteArray {
        val data = SAMPLE_RATE * CHANNELS * 2 * seconds
        return ByteBuffer.allocate(WAV_HEADER + data).order(ByteOrder.LITTLE_ENDIAN).apply {
            put("RIFF".toByteArray()).putInt(WAV_HEADER - 8 + data).put("WAVE".toByteArray())
            put("fmt ".toByteArray()).putInt(16).putShort(1).putShort(CHANNELS.toShort())
            putInt(SAMPLE_RATE).putInt(SAMPLE_RATE * CHANNELS * 2).putShort((CHANNELS * 2).toShort()).putShort(16)
            put("data".toByteArray()).putInt(data)
            for (i in 0 until data / 2) putShort(((i * 7) % 2_000 - 1_000).toShort())
        }.array()
    }

    private fun scenario(playForMs: Long, keepPlaying: Boolean, label: String, paceMs: Long = FAST_PACE_MS) {
        val id = "tee${System.nanoTime()}"
        val data = wav(SECONDS)
        val url = "https://rr1.googlevideo.com/videoplayback?id=$id&itag=251&clen=${data.size}" +
            "&mime=audio%2Fwebm&expire=1"
        val upstream = CountingUpstream(data, paceMs)
        val store = SimpleCache(
            File(context.cacheDir, "tee-test-$id"),
            NoOpCacheEvictor(),
            StandaloneDatabaseProvider(context),
        )
        val factory = PlaybackCache.factory(store, upstream)
        val spot = AtomicReference<PlayerSpot?>(null)
        val player = onMain {
            ExoPlayer.Builder(context).build().apply {
                setMediaSource(ProgressiveMediaSource.Factory(factory).createMediaSource(MediaItem.fromUri(url)))
                prepare()
                playWhenReady = true
            }
        }
        val ticker = object : Runnable {
            override fun run() {
                spot.set(PlayerSpot(player.currentPosition, SECONDS * 1_000L, player.playWhenReady))
                main.postDelayed(this, TICK_MS)
            }
        }
        main.post(ticker)
        PlaybackTee.attach { asked -> spot.get().takeIf { asked == id } }

        Thread.sleep(playForMs)
        assertTrue("the player's stream was noticed", PlaybackTee.canTee(id))
        if (!keepPlaying) {
            onMain {
                main.removeCallbacks(ticker)
                player.release()
            }
            spot.set(null)
        }

        val target = File(context.cacheDir, "tee-$id.media")
        val outcome = runBlocking { withTimeout(COMPLETE_TIMEOUT_MS) { PlaybackTee.complete(id, target) { _, _ -> } } }
        if (keepPlaying) {
            onMain {
                main.removeCallbacks(ticker)
                player.release()
            }
        }
        val text = "$label: outcome=$outcome network=${upstream.networkBytes.get()} file=${data.size} " +
            "refetched=${upstream.refetchedBytes.get()} counted=${PlaybackTee.networkBytesFor(id)} " +
            "ranges=${upstream.ranges.size}"
        android.util.Log.i("dewidebug", text)
        println(text)
        store.release()

        assertTrue("completed: $text", outcome is TeeOutcome.Completed)
        assertTrue("the file is the stream", target.readBytes().contentEquals(data))
        assertTrue("about one fetch of the file: $text", upstream.networkBytes.get() <= data.size * MAX_RATIO)
        assertTrue(
            "the counter sees what the upstream served: $text",
            PlaybackTee.networkBytesFor(id) == upstream.networkBytes.get()
        )
    }

    @Test
    fun aStreamThatKeepsPlayingIsFetchedOnceBetweenThePlayerAndTheTee() = scenario(
        PLAY_MS,
        keepPlaying = true,
        label = "playing"
    )

    @Test
    fun aStreamThePlayerHasLeftIsCompletedWithoutRefetchingWhatItHeld() = scenario(
        PLAY_MS,
        keepPlaying = false,
        label = "left"
    )

    @Test
    fun aStreamOnASlowConnectionIsFetchedOnceWhileThePlayerAndTheTeeRaceForIt() =
        scenario(PLAY_MS, keepPlaying = true, label = "slow", paceMs = SLOW_PACE_MS)

    @Test
    fun aPausedStreamOnASlowConnectionIsCompletedByTheTeeAlone() =
        scenario(PLAY_MS, keepPlaying = false, label = "slow-left", paceMs = SLOW_PACE_MS)

    private companion object {
        const val SAMPLE_RATE = 44_100
        const val CHANNELS = 2
        const val SECONDS = 90
        const val WAV_HEADER = 44
        const val SLICE = 32L * 1024
        const val FAST_PACE_MS = 2L
        const val SLOW_PACE_MS = 25L
        const val TICK_MS = 200L
        const val PLAY_MS = 4_000L
        const val MAIN_WAIT_S = 5L
        const val COMPLETE_TIMEOUT_MS = 90_000L
        const val MAX_RATIO = 1.03
    }
}
