package com.dewijones92.totum.playback

import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.DataReader
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.ParsableByteArray
import androidx.media3.common.util.UnstableApi
import androidx.media3.extractor.TrackOutput
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(UnstableApi::class)
class AviMp3FramingTest {

    private class Sample(val timeUs: Long, val bytes: ByteArray)

    private class Recording : TrackOutput {
        var format: Format? = null
        val samples = mutableListOf<Sample>()
        private var pending = ByteArray(0)

        override fun format(format: Format) {
            this.format = format
        }

        override fun sampleData(input: DataReader, length: Int, allowEndOfInput: Boolean, sampleDataPart: Int): Int {
            val buffer = ByteArray(length)
            val read = input.read(buffer, 0, length)
            if (read > 0) pending += buffer.copyOf(read)
            return read
        }

        override fun sampleData(data: ParsableByteArray, length: Int, sampleDataPart: Int) {
            val buffer = ByteArray(length)
            data.readBytes(buffer, 0, length)
            pending += buffer
        }

        override fun sampleMetadata(
            timeUs: Long,
            flags: Int,
            size: Int,
            offset: Int,
            cryptoData: TrackOutput.CryptoData?,
        ) {
            samples += Sample(timeUs, pending.copyOfRange(pending.size - size - offset, pending.size - offset))
            pending = ByteArray(0)
        }
    }

    private fun frame(fill: Int): ByteArray =
        ByteArray(FRAME_BYTES) { i -> HEADER.getOrElse(i) { fill.toByte() } }

    private fun mp3Format() = Format.Builder().setSampleMimeType(MimeTypes.AUDIO_MPEG)
        .setChannelCount(2).setSampleRate(44_100).build()

    private fun TrackOutput.chunk(bytes: ByteArray, timeUs: Long) {
        sampleData(ParsableByteArray(bytes), bytes.size, TrackOutput.SAMPLE_DATA_PART_MAIN)
        sampleMetadata(timeUs, C.BUFFER_FLAG_KEY_FRAME, bytes.size, 0, null)
    }

    @Test
    fun `MP3 cut into chunks that straddle frames reaches the decoder one whole frame at a time`() {
        val recording = Recording()
        val framer = Mp3Framer(recording)
        framer.format(mp3Format())
        val stream = ByteArray(FRAME_BYTES) + frame(1) + frame(2) + frame(3) + frame(4)
        val cuts = listOf(784, 356, 724, stream.size - 784 - 356 - 724)

        var at = 0
        cuts.forEach { size ->
            framer.chunk(stream.copyOfRange(at, at + size), timeUs = at * BYTES_TO_US)
            at += size
        }

        assertEquals(listOf(1, 2, 3, 4), recording.samples.map { it.bytes[HEADER.size].toInt() })
        assertTrue(recording.samples.all { it.bytes.size == FRAME_BYTES && it.bytes.copyOf(2).contentEquals(SYNC) })
        val times = recording.samples.map { it.timeUs }
        assertEquals((0 until 4).map { skippedUs(FRAME_BYTES) + frameUs(it) }, times)
    }

    @Test
    fun `after a seek it starts again at the next frame and the time of that chunk`() {
        val recording = Recording()
        val framer = Mp3Framer(recording)
        framer.format(mp3Format())
        framer.chunk(frame(1).copyOf(300), timeUs = 0)

        framer.reset()
        framer.chunk(frame(7).copyOfRange(100, FRAME_BYTES) + frame(8) + frame(9), timeUs = 5_000_000)

        assertEquals(listOf(8, 9), recording.samples.map { it.bytes[HEADER.size].toInt() })
        assertEquals(5_000_000 + skippedUs(FRAME_BYTES - 100), recording.samples.first().timeUs)
    }

    @Test
    fun `anything that is not MP3 passes through untouched`() {
        val recording = Recording()
        val framer = Mp3Framer(recording)
        framer.format(Format.Builder().setSampleMimeType(MimeTypes.AUDIO_AC3).build())

        framer.chunk(ByteArray(784) { 5 }, timeUs = 42)

        assertEquals(listOf(784), recording.samples.map { it.bytes.size })
        assertEquals(42L, recording.samples.single().timeUs)
    }

    private companion object {
        val SYNC = byteArrayOf(0xFF.toByte(), 0xFB.toByte())
        val HEADER = byteArrayOf(0xFF.toByte(), 0xFB.toByte(), 0x90.toByte(), 0x00)
        const val FRAME_BYTES = 417
        const val BYTES_TO_US = 1_000_000L / 16_000

        fun skippedUs(bytes: Int) = bytes * 8_000_000L / 128_000

        fun frameUs(frames: Int) = frames * 1_152L * 1_000_000 / 44_100
    }
}
