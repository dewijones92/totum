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

    @Test(timeout = 60_000)
    fun aFivePointOneTrackReachesTheTvAsStereo() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val file = File(instrumentation.targetContext.cacheDir, "five-point-one.m4a")
        instrumentation.context.assets.open("five-point-one.m4a").use { input ->
            file.outputStream().use { input.copyTo(it) }
        }
        val audio = ProcessedAudio(
            instrumentation.targetContext,
            Uri.fromFile(file).toString(),
            { VolumeBoost.OFF },
            "five-point-one test",
            null,
        )

        audio.prepare()
        val first = audio.segment(0)
        audio.close()

        assertEquals(2, audio.format.channels)
        assertTrue("segment 0 is ${first.size} bytes", first.size > 1_000)
    }
}
