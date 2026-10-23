package com.dewijones92.totum.playback

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class TheTailIsScannedForSpeechTest {

    private val instrumentation = InstrumentationRegistry.getInstrumentation()

    private fun weights(): SpeechWeights =
        instrumentation.targetContext.resources.openRawResource(R.raw.silero_vad).use(SpeechWeights::read)

    private fun file(asset: String): File =
        File(instrumentation.targetContext.cacheDir, asset).also { out ->
            instrumentation.context.assets.open(asset).use { input -> out.outputStream().use(input::copyTo) }
        }

    @Test
    fun aTailWithNoSpeechHeardSaysNothingAndDoesNotEndTheItem() {
        val scan = scanTailForSpeech(file("speech-then-music.m4a").path, DURATION_MS, weights())

        assertNotNull(scan)
        android.util.Log.i("dewidebug", "speech-then-music scan: $scan")
        assertNull("scan $scan", scan!!.speechEndsMs)
        assertTrue(scan.heardMs > 0)
        assertFalse(endsWithNoSpeechLeft(scan.fromMs + 4_000, scan.fromMs + 5_000, DURATION_MS, scan.speechEndsMs))
    }

    @Test
    fun speechIntoTheTailEndsOnlyAfterTheLastWords() {
        val scan = scanTailForSpeech(file("speech-into-the-tail.m4a").path, DURATION_MS, weights())

        assertNotNull(scan)
        android.util.Log.i("dewidebug", "speech-into-the-tail scan: $scan")
        val lastWords = scan!!.speechEndsMs!!
        assertTrue("scan $scan", lastWords in SPEECH_ENDS_MS - SLACK_MS..SPEECH_ENDS_MS + SLACK_MS)
        assertFalse(endsWithNoSpeechLeft(lastWords - 3_000, lastWords - 2_000, DURATION_MS, lastWords))
        assertTrue(endsWithNoSpeechLeft(lastWords + 2_000, lastWords + 3_000, DURATION_MS, lastWords))
    }

    private companion object {
        const val DURATION_MS = 60_000L
        const val SPEECH_ENDS_MS = 50_000L
        const val SLACK_MS = 1_500L
    }
}
