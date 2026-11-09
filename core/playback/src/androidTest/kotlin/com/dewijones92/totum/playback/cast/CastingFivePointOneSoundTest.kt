package com.dewijones92.totum.playback.cast

import android.net.Uri
import androidx.media3.common.util.UnstableApi
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.dewijones92.totum.playback.VolumeBoost
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@UnstableApi
@RunWith(AndroidJUnit4::class)
class CastingFivePointOneSoundTest {

    private class Collect : TranscodeSink {
        val segments = mutableMapOf<Int, ByteArray>()
        override fun waitWhileAhead(segment: Int) = Unit
        override fun stale(): Boolean = segments.isNotEmpty()
        override fun publish(segment: Int, bytes: ByteArray, tvEndUs: Long, sourceEndUs: Long) {
            segments[segment] = bytes
        }
        override fun ended(tvEndUs: Long) = Unit
    }

    @Test(timeout = 60_000)
    fun aFivePointOneTrackReachesTheTvAsStereo() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val file = File(instrumentation.targetContext.cacheDir, "five-point-one.m4a")
        instrumentation.context.assets.open("five-point-one.m4a").use { input ->
            file.outputStream().use { input.copyTo(it) }
        }
        val engine = MediaCodecAudioEngine(
            instrumentation.targetContext,
            Uri.fromFile(file).toString(),
            "five-point-one test",
            { VolumeBoost.OFF },
            null,
        )

        val probed = engine.probe()
        val stereo = AacFormat(probed.sampleRate, 2, aacLcStereo(probed.sampleRate))
        val sink = Collect()
        engine.transcode(TranscodePlan(stereo, FRAMES, 0, "five-point-one test", 0, probed.durationUs), sink)
        engine.close()

        assertEquals("the source really is 5.1", 6, probed.channels)
        val first = checkNotNull(sink.segments[0]) { "no first segment was produced" }
        assertTrue("segment 0 is ${first.size} bytes", first.size > 1_000)
    }

    private fun aacLcStereo(rate: Int): ByteArray {
        val index = listOf(96_000, 88_200, 64_000, 48_000, 44_100, 32_000, 24_000, 22_050, 16_000).indexOf(rate)
        val v = (2 shl 11) or (index shl 7) or (2 shl 3)
        return byteArrayOf((v shr 8).toByte(), v.toByte())
    }

    private companion object {
        const val FRAMES = 188
    }
}
