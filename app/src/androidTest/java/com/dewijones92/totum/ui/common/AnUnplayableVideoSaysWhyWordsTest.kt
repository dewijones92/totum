package com.dewijones92.totum.ui.common

import androidx.test.platform.app.InstrumentationRegistry
import com.dewijones92.totum.playback.PlaybackProblem
import com.dewijones92.totum.playback.TrackKind
import com.dewijones92.totum.queue.RefusedTap
import org.junit.Assert.assertEquals
import org.junit.Test

class AnUnplayableVideoSaysWhyWordsTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun aVideoThePhoneCannotDecodeNamesItsCodec() {
        val tap = RefusedTap(
            "21 Jump Street",
            null,
            problem = PlaybackProblem.CannotDecode(TrackKind.VIDEO, "HEVC 10-bit", "ERROR_CODE_DECODING_FAILED"),
        )

        assertEquals(
            "❌ Couldn't play “21 Jump Street”: this phone can't play this video (HEVC 10-bit)",
            context.refusalMessage(tap, signedIn = true),
        )
    }

    @Test
    fun aSoundItCannotDecodeSaysSound() {
        val tap = RefusedTap(
            "Heat",
            null,
            problem = PlaybackProblem.CannotDecode(TrackKind.AUDIO, "E-AC-3", "ERROR_CODE_DECODING_FAILED"),
        )

        assertEquals(
            "❌ Couldn't play “Heat”: this phone can't play this sound (E-AC-3)",
            context.refusalMessage(tap, signedIn = true),
        )
    }

    @Test
    fun aDecoderThatStoppedOnASupportedFormatSaysSo() {
        val tap = RefusedTap(
            "Heat",
            null,
            problem = PlaybackProblem.CannotDecode(
                TrackKind.AUDIO,
                "E-AC-3",
                "ERROR_CODE_DECODING_FAILED",
                formatSupported = true,
            ),
        )

        assertEquals(
            "❌ Couldn't play “Heat”: this phone's E-AC-3 decoder stopped on this sound",
            context.refusalMessage(tap, signedIn = true),
        )
    }

    @Test
    fun aPictureBeyondTheDeviceSaysWhatItReaches() {
        val tap = RefusedTap(
            "Heat",
            null,
            problem = PlaybackProblem.CannotDecode(
                TrackKind.VIDEO,
                "VP9",
                "ERROR_CODE_DECODING_FORMAT_EXCEEDS_CAPABILITIES",
                mimeType = "video/x-vnd.on2.vp9",
                width = 3840,
                height = 2160,
            ),
        )

        assertEquals(
            "❌ Couldn't play “Heat”: VP9 at 3840×2160 is beyond this device, which reaches 1080p",
            context.refusalMessage(tap, signedIn = true, reach = { 1080 }),
        )
    }

    @Test
    fun anyOtherFailureNamesTheError() {
        val tap = RefusedTap("Heat", null, problem = PlaybackProblem.Failed("ERROR_CODE_IO_FILE_NOT_FOUND"))

        assertEquals(
            "❌ Couldn't play “Heat”: playback failed (ERROR_CODE_IO_FILE_NOT_FOUND)",
            context.refusalMessage(tap, signedIn = true),
        )
    }
}
