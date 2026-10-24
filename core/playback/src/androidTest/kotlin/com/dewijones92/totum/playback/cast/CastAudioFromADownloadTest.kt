package com.dewijones92.totum.playback.cast

import android.media.MediaFormat
import android.net.Uri
import androidx.media3.common.util.UnstableApi
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@UnstableApi
@RunWith(AndroidJUnit4::class)
class CastAudioFromADownloadTest {

    @Test
    fun aDownloadedFileOpensForCastingAndOffersItsSound() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val file = File(instrumentation.targetContext.cacheDir, "cast-from-disk.m4a")
        instrumentation.context.assets.open("speech-into-the-tail.m4a").use { input ->
            file.outputStream().use { input.copyTo(it) }
        }

        val extractor = castExtractor(instrumentation.targetContext, Uri.fromFile(file))

        val mimes = (0 until extractor.trackCount).map { extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME) }
        extractor.release()
        assertTrue("tracks: $mimes", mimes.any { it?.startsWith("audio/") == true })
    }
}
