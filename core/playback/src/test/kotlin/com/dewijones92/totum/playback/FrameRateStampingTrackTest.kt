package com.dewijones92.totum.playback

import androidx.media3.common.C
import androidx.media3.common.DataReader
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.ParsableByteArray
import androidx.media3.extractor.TrackOutput
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class FrameRateStampingTrackTest {

    private class Recording : TrackOutput {
        val formats = mutableListOf<Format>()
        val events = mutableListOf<String>()
        private val data = java.io.ByteArrayOutputStream()
        val samples = mutableListOf<Pair<Long, ByteArray>>()

        override fun format(format: Format) {
            formats += format
            events += "format"
        }

        override fun sampleData(input: DataReader, length: Int, allowEndOfInput: Boolean, sampleDataPart: Int): Int {
            val bytes = ByteArray(length)
            val read = input.read(bytes, 0, length)
            data.write(bytes, 0, read)
            return read
        }

        override fun sampleData(data: ParsableByteArray, length: Int, sampleDataPart: Int) {
            val bytes = ByteArray(length)
            data.readBytes(bytes, 0, length)
            this.data.write(bytes)
        }

        override fun sampleMetadata(
            timeUs: Long,
            flags: Int,
            size: Int,
            offset: Int,
            cryptoData: TrackOutput.CryptoData?,
        ) {
            val all = data.toByteArray()
            val end = all.size - offset
            samples += timeUs to all.copyOfRange(end - size, end)
            events += "sample"
        }
    }

    private val av1 = Format.Builder().setSampleMimeType(MimeTypes.VIDEO_AV1).setWidth(1920).setHeight(1080).build()

    private fun TrackOutput.frame(index: Int, everyUs: Long = 16_667) {
        val bytes = ByteArray(10 + index) { index.toByte() }
        sampleData(ParsableByteArray(bytes), bytes.size)
        sampleMetadata(index * everyUs, if (index == 0) C.BUFFER_FLAG_KEY_FRAME else 0, bytes.size, 0, null)
    }

    @Test
    fun `the decoder's first format already carries the frame rate, and every frame arrives intact and in order`() {
        val out = Recording()
        val track = FrameRateStampingTrack(out)
        track.format(av1)
        repeat(15) { track.frame(it) }

        assertEquals("format", out.events.first())
        assertEquals(1, out.formats.size)
        assertEquals(60f, out.formats.single().frameRate, 0.001f)
        assertEquals((0 until 15).map { it * 16_667L }, out.samples.map { it.first })
        out.samples.forEachIndexed { i, (_, bytes) -> assertArrayEquals(ByteArray(10 + i) { i.toByte() }, bytes) }
    }

    @Test
    fun `a container that states its rate is passed straight through`() {
        val out = Recording()
        val track = FrameRateStampingTrack(out)
        track.format(av1.buildUpon().setFrameRate(25f).build())
        track.frame(0)

        assertEquals(listOf("format", "sample"), out.events)
        assertEquals(25f, out.formats.single().frameRate, 0.001f)
    }

    @Test
    fun `a stream that ends before enough frames still gets its format and frames`() {
        val out = Recording()
        val track = FrameRateStampingTrack(out)
        track.format(av1)
        repeat(3) { track.frame(it) }
        assertEquals(emptyList<String>(), out.events)

        track.flush("the stream ended first")

        assertEquals(listOf("format", "sample", "sample", "sample"), out.events)
        assertEquals(Format.NO_VALUE.toFloat(), out.formats.single().frameRate, 0.001f)
    }

    @Test
    fun `a seek before judging drops the held frames but still gives the format`() {
        val out = Recording()
        val track = FrameRateStampingTrack(out)
        track.format(av1)
        repeat(3) { track.frame(it) }

        track.forget("a seek came first")

        assertEquals(listOf("format"), out.events)
    }
}
