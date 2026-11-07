package com.dewijones92.totum.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaybackProblemTest {

    private val emulatorMessage = "MediaCodecVideoRenderer error, index=0, format=Format(1, null, null, video/hevc, " +
        "hvc1.2.4.L153.B0, -1, null, [1920, 800, 23.976025, ColorInfo(Unset, Unset, Unset, false, 8, 8)], " +
        "[-1, -1]), format_supported=NO_EXCEEDS_CAPABILITIES"

    @Test
    fun `the message the emulator gave for a 10-bit HEVC film names its codec and bit depth`() {
        val format = ErroredFormat.fromMessage(emulatorMessage)

        val problem = problemOf("ERROR_CODE_DECODING_FAILED", format)

        assertEquals(
            PlaybackProblem.CannotDecode(TrackKind.VIDEO, "HEVC 10-bit", "ERROR_CODE_DECODING_FAILED"),
            problem,
        )
        assertTrue(problem.isPicture)
    }

    @Test
    fun `every decoder error with a known format is a decode problem, any other error is just a failure`() {
        val format = ErroredFormat("video/hevc", "hvc1.1.6.L93.B0")

        listOf(
            "ERROR_CODE_DECODER_INIT_FAILED",
            "ERROR_CODE_DECODER_QUERY_FAILED",
            "ERROR_CODE_DECODING_FAILED",
            "ERROR_CODE_DECODING_FORMAT_EXCEEDS_CAPABILITIES",
            "ERROR_CODE_DECODING_FORMAT_UNSUPPORTED",
        ).forEach { name ->
            assertEquals(name, PlaybackProblem.CannotDecode(TrackKind.VIDEO, "HEVC", name), problemOf(name, format))
        }
        assertEquals(
            PlaybackProblem.Failed("ERROR_CODE_IO_FILE_NOT_FOUND"),
            problemOf("ERROR_CODE_IO_FILE_NOT_FOUND", format),
        )
    }

    @Test
    fun `a decode error whose format is not known stays a plain failure rather than a guess`() {
        assertEquals(
            PlaybackProblem.Failed("ERROR_CODE_DECODING_FAILED"),
            problemOf("ERROR_CODE_DECODING_FAILED", null),
        )
        assertNull(ErroredFormat.fromMessage("Source error"))
        assertNull(ErroredFormat.fromMessage(null))
        assertFalse(PlaybackProblem.Failed("x").isPicture)
    }

    @Test
    fun `a sound the phone cannot decode is not a picture problem`() {
        val problem = problemOf("ERROR_CODE_DECODING_FAILED", ErroredFormat("audio/eac3", "ec-3"))

        assertEquals(PlaybackProblem.CannotDecode(TrackKind.AUDIO, "E-AC-3", "ERROR_CODE_DECODING_FAILED"), problem)
        assertFalse(problem.isPicture)
    }

    @Test
    fun `codecs read in plain words`() {
        assertEquals("HEVC", CodecWords.of("video/hevc", "hvc1.1.6.L120.90"))
        assertEquals("HEVC 10-bit", CodecWords.of("video/hevc", "hev1.2.4.L150.B0"))
        assertEquals("H.264", CodecWords.of("video/avc", "avc1.640028"))
        assertEquals("H.264 10-bit", CodecWords.of("video/avc", "avc1.6E001F"))
        assertEquals("AV1", CodecWords.of("video/av01", "av01.0.08M.08"))
        assertEquals("AV1 10-bit", CodecWords.of("video/av01", "av01.0.08M.10"))
        assertEquals("VP9 10-bit", CodecWords.of("video/x-vnd.on2.vp9", "vp09.02.10.10"))
        assertEquals("VP9", CodecWords.of("video/x-vnd.on2.vp9", null))
        assertEquals("DTS", CodecWords.of("audio/vnd.dts", "dtsc"))
        assertEquals("XYZ", CodecWords.of("video/xyz", null))
        assertEquals("an unknown format", CodecWords.of(null, null))
    }
}
