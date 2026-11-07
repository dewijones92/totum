package com.dewijones92.totum.playback

import androidx.media3.common.C
import androidx.media3.common.DataReader
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.ParsableByteArray
import androidx.media3.extractor.Extractor
import androidx.media3.extractor.ExtractorInput
import androidx.media3.extractor.ExtractorOutput
import androidx.media3.extractor.PositionHolder
import androidx.media3.extractor.SeekMap
import androidx.media3.extractor.TrackOutput
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class StampsFrameRateTest {

    private class Sink : TrackOutput {
        val formats = mutableListOf<Format>()
        val times = mutableListOf<Long>()
        val events = mutableListOf<String>()

        override fun format(format: Format) {
            formats += format
            events += "format"
        }

        override fun sampleData(input: DataReader, length: Int, allowEndOfInput: Boolean, sampleDataPart: Int): Int {
            val bytes = ByteArray(length)
            return input.read(bytes, 0, length)
        }

        override fun sampleData(data: ParsableByteArray, length: Int, sampleDataPart: Int) {
            data.skipBytes(length)
        }

        override fun sampleMetadata(
            timeUs: Long,
            flags: Int,
            size: Int,
            offset: Int,
            cryptoData: TrackOutput.CryptoData?,
        ) {
            times += timeUs
            events += "sample"
        }
    }

    private class Output(val video: Sink = Sink(), val audio: Sink = Sink()) : ExtractorOutput {
        var ended = false
        override fun track(id: Int, type: Int): TrackOutput = if (type == C.TRACK_TYPE_VIDEO) video else audio
        override fun endTracks() {
            ended = true
        }

        override fun seekMap(seekMap: SeekMap) = Unit
    }

    private object NoInput : ExtractorInput {
        override fun read(buffer: ByteArray, offset: Int, length: Int): Int = error("unused")
        override fun readFully(target: ByteArray, offset: Int, length: Int, allowEndOfInput: Boolean) = error("unused")
        override fun readFully(target: ByteArray, offset: Int, length: Int) = error("unused")
        override fun skip(length: Int): Int = error("unused")
        override fun skipFully(length: Int, allowEndOfInput: Boolean) = error("unused")
        override fun skipFully(length: Int) = error("unused")
        override fun peek(target: ByteArray, offset: Int, length: Int): Int = error("unused")
        override fun peekFully(target: ByteArray, offset: Int, length: Int, allowEndOfInput: Boolean) = error("unused")
        override fun peekFully(target: ByteArray, offset: Int, length: Int) = error("unused")
        override fun advancePeekPosition(length: Int, allowEndOfInput: Boolean) = error("unused")
        override fun advancePeekPosition(length: Int) = error("unused")
        override fun resetPeekPosition() = Unit
        override fun getPeekPosition(): Long = 0
        override fun getPosition(): Long = 0
        override fun getLength(): Long = C.LENGTH_UNSET.toLong()
        override fun <E : Throwable?> setRetryPosition(position: Long, e: E) = throw e!!
    }

    private class Matroska(
        private val videoFormat: Format,
        private val videoTimesUs: List<Long>,
        private val audioFrames: Int = 3,
    ) : Extractor {
        lateinit var output: ExtractorOutput
        var sniffed = false
        var seekedTo: Pair<Long, Long>? = null
        private var next = 0
        private val videoOut by lazy { output.track(1, C.TRACK_TYPE_VIDEO) }
        private val audioOut by lazy { output.track(2, C.TRACK_TYPE_AUDIO) }

        override fun sniff(input: ExtractorInput): Boolean = true.also { sniffed = true }

        override fun init(output: ExtractorOutput) {
            this.output = output
            videoOut.format(videoFormat)
            audioOut.format(Format.Builder().setSampleMimeType(MimeTypes.AUDIO_OPUS).build())
            output.endTracks()
        }

        override fun read(input: ExtractorInput, seekPosition: PositionHolder): Int {
            if (next >= videoTimesUs.size) return Extractor.RESULT_END_OF_INPUT
            val bytes = ParsableByteArray(ByteArray(8))
            videoOut.sampleData(bytes, 8)
            videoOut.sampleMetadata(videoTimesUs[next], C.BUFFER_FLAG_KEY_FRAME, 8, 0, null)
            if (next < audioFrames) {
                audioOut.sampleData(ParsableByteArray(ByteArray(4)), 4)
                audioOut.sampleMetadata(next * 20_000L, C.BUFFER_FLAG_KEY_FRAME, 4, 0, null)
            }
            next++
            return Extractor.RESULT_CONTINUE
        }

        override fun seek(position: Long, timeUs: Long) {
            seekedTo = position to timeUs
        }

        override fun release() = Unit
    }

    private val vp9 = Format.Builder().setSampleMimeType(MimeTypes.VIDEO_VP9).setWidth(1920).setHeight(1080).build()

    private fun webmTimes(fps: Double, count: Int) = (0 until count).map { Math.round(it * 1000.0 / fps) * 1_000L }

    private fun drain(extractor: Extractor) {
        while (extractor.read(NoInput, PositionHolder()) != Extractor.RESULT_END_OF_INPUT) Unit
    }

    @Test
    fun `a webm video track that states no rate reaches the decoder stamped with its real one, whole`() {
        val inner = Matroska(vp9, webmTimes(60.0, 30))
        val out = Output()
        val extractor = StampsFrameRate(inner)
        extractor.init(out)

        drain(extractor)

        assertEquals(1, out.video.formats.size)
        assertEquals(60f, out.video.formats.single().frameRate, 0.1f)
        assertEquals(webmTimes(60.0, 30), out.video.times)
        assertEquals("the format comes before any frame", "format", out.video.events.first())
        assertTrue(out.ended)
    }

    @Test
    fun `the same holds for thirty frames a second, which whole milliseconds used to blur`() {
        val out = Output()
        val extractor = StampsFrameRate(Matroska(vp9, webmTimes(30.0, 20)))
        extractor.init(out)

        drain(extractor)

        assertEquals(30f, out.video.formats.single().frameRate, 0.1f)
    }

    @Test
    fun `the audio track is never held back while the video waits for its frames`() {
        val out = Output()
        val extractor = StampsFrameRate(Matroska(vp9, webmTimes(60.0, 30), audioFrames = 5))
        extractor.init(out)

        repeat(4) { extractor.read(NoInput, PositionHolder()) }

        assertEquals(1, out.audio.formats.size)
        assertEquals(4, out.audio.times.size)
        assertTrue("video still held: no format yet", out.video.formats.isEmpty())
    }

    @Test
    fun `a stream that ends before enough frames still delivers its format and every frame`() {
        val out = Output()
        val extractor = StampsFrameRate(Matroska(vp9, webmTimes(60.0, 5)))
        extractor.init(out)

        drain(extractor)

        assertEquals(1, out.video.formats.size)
        assertEquals(5, out.video.times.size)
    }

    @Test
    fun `a container that already states the rate is not touched`() {
        val stated = vp9.buildUpon().setFrameRate(29.97f).build()
        val out = Output()
        val extractor = StampsFrameRate(Matroska(stated, webmTimes(60.0, 20)))
        extractor.init(out)

        drain(extractor)

        assertEquals(29.97f, out.video.formats.single().frameRate, 0.0001f)
    }

    @Test
    fun `a seek before the rate was worked out lets the format through and carries the seek to the real extractor`() {
        val inner = Matroska(vp9, webmTimes(60.0, 30))
        val out = Output()
        val extractor = StampsFrameRate(inner)
        extractor.init(out)
        repeat(3) { extractor.read(NoInput, PositionHolder()) }

        extractor.seek(1_000L, 500_000L)

        assertEquals(1_000L to 500_000L, inner.seekedTo)
        assertEquals(1, out.video.formats.size)
        assertTrue(out.video.times.isEmpty())
    }

    @Test
    fun `the wrapper is transparent about the extractor it wraps`() {
        val inner = Matroska(vp9, emptyList())
        val extractor = StampsFrameRate(inner)

        assertTrue(extractor.sniff(NoInput))
        assertTrue(inner.sniffed)
        assertSame(inner, extractor.underlyingImplementation)
    }
}
