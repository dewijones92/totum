package com.dewijones92.totum.cli.serve.cast

import com.dewijones92.totum.cli.serve.sound.AdtsFrames
import com.dewijones92.totum.cli.serve.sound.ProcessedSound
import com.dewijones92.totum.data.targets.SoundSettings
import com.dewijones92.totum.playback.cast.AacFormat
import com.dewijones92.totum.playback.cast.Fmp4Aac
import com.dewijones92.totum.playback.cast.TranscodePlan
import com.dewijones92.totum.playback.cast.TranscodeSink
import com.dewijones92.totum.playback.cast.UNKNOWN_US
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class FfmpegAudioEngineTest {

    private val fixtures = CastFixtures()

    private class Published(val segment: Int, val bytes: ByteArray, val tvEndUs: Long, val sourceEndUs: Long)

    private class RecordingSink(private val wantedAhead: Int = Int.MAX_VALUE) : TranscodeSink {
        val published = mutableListOf<Published>()
        var endedAtUs: Long? = null
        var asked = 0
        override fun waitWhileAhead(segment: Int) {
            asked++
        }
        override fun stale(): Boolean = published.size >= wantedAhead
        override fun publish(segment: Int, bytes: ByteArray, tvEndUs: Long, sourceEndUs: Long) {
            published += Published(segment, bytes, tvEndUs, sourceEndUs)
        }
        override fun ended(tvEndUs: Long) {
            endedAtUs = tvEndUs
        }
    }

    private val format = AacFormat(48_000, 2, byteArrayOf(0x11, 0x90.toByte()))

    private fun plan(first: Int = 0, startUs: Long = 0, durationUs: Long) =
        TranscodePlan(
            format,
            framesPerSegment = 188,
            first = first,
            label = "t",
            startUs = startUs,
            durationUs = durationUs
        )

    private fun engine(file: File, sound: SoundSettings = SoundSettings(), known: Long? = null) =
        FfmpegAudioEngine("t", file.path, sound, known, ProcessedSound(weights = { null }))

    @Test
    fun `a nine second clip becomes four-second segments of AAC and ends on its own clock`() {
        val sink = RecordingSink()

        engine(fixtures.tone("a.wav", 9)).transcode(plan(durationUs = 9_000_000), sink)

        assertEquals(listOf(0, 1, 2), sink.published.map { it.segment })
        assertTrue(sink.published.all { String(it.bytes, 4, 4) == "moof" })
        assertEquals(4_010_000.0, sink.published[0].tvEndUs.toDouble(), 20_000.0)
        assertEquals(9_000_000.0, sink.endedAtUs!!.toDouble(), 100_000.0)
        assertEquals(sink.published.last().tvEndUs, sink.endedAtUs)
        assertEquals(sink.published[0].tvEndUs, sink.published[0].sourceEndUs)
    }

    @Test
    fun `starting at segment 1 starts four seconds into the source, and numbers its segments from there`() {
        val sink = RecordingSink()

        engine(fixtures.tone("a.wav", 9)).transcode(plan(first = 1, startUs = 4_010_666, durationUs = 9_000_000), sink)

        assertEquals(1, sink.published.first().segment)
        assertEquals(9_000_000.0, sink.endedAtUs!!.toDouble(), 200_000.0)
    }

    @Test
    fun `a stale sink ends the run and stops ffmpeg, publishing nothing more`() {
        val sink = RecordingSink(wantedAhead = 1)

        engine(fixtures.tone("a.wav", 20)).transcode(plan(durationUs = 20_000_000), sink)

        assertEquals(1, sink.published.size)
        assertEquals(null, sink.endedAtUs)
    }

    @Test
    fun `skipped silence makes the TV's clock run behind the source's`() {
        val sink = RecordingSink()
        val clip = fixtures.toneSilenceTone("gap.wav")

        engine(clip, SoundSettings(skipSilence = true)).transcode(plan(durationUs = 10_000_000), sink)

        val last = sink.published.last()
        assertTrue("tv=${last.tvEndUs} source=${last.sourceEndUs}", last.sourceEndUs - last.tvEndUs > 1_000_000)
        assertTrue(sink.endedAtUs!! < 9_000_000)
    }

    @Test
    fun `the length is the item's when it states one, else ffprobe's`() {
        val clip = fixtures.tone("a.wav", 3)

        assertEquals(7_000_000L, engine(clip, known = 7_000_000).probe().durationUs)
        assertEquals(3_000_000.0, engine(clip).probe().durationUs.toDouble(), 100_000.0)
        assertEquals(UNKNOWN_US, engine(File(fixtures.dir, "missing.wav")).probe().durationUs)
    }

    @Test
    fun `a silent frame is a raw AAC frame the segment writer accepts`() {
        val frame = engine(fixtures.tone("a.wav", 1)).silentFrame(format)

        assertTrue(frame.size in 1..400)
        assertNotNull(Fmp4Aac.segment(1, 0, listOf(frame)))
    }

    @Test
    fun `ADTS frames come out without their headers, whole, and a cut-off last one is dropped`() {
        fun adts(payload: ByteArray): ByteArray {
            val length = payload.size + 7
            return byteArrayOf(
                0xFF.toByte(), 0xF1.toByte(), 0x4C, (0x80 or (length shr 11)).toByte(),
                (length shr 3 and 0xFF).toByte(), ((length and 7) shl 5 or 0x1F).toByte(), 0xFC.toByte(),
            ) + payload
        }
        val stream = adts(byteArrayOf(1, 2, 3)) + adts(byteArrayOf(4, 5)) + adts(byteArrayOf(6, 7, 8, 9)).copyOf(9)

        val frames = AdtsFrames.of(stream)

        assertEquals(listOf(listOf<Byte>(1, 2, 3), listOf<Byte>(4, 5)), frames.map { it.toList() })
    }
}
