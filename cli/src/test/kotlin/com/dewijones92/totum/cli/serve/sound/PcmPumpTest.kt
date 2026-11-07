package com.dewijones92.totum.cli.serve.sound

import com.dewijones92.totum.data.targets.SoundSettings
import com.dewijones92.totum.playback.PcmChain
import com.dewijones92.totum.playback.VolumeBoost
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Random

class PcmPumpTest {

    private val chunkBytes = 4_096 * SOUND_CHANNELS * 2

    private fun bytesOf(samples: ShortArray): ByteArray {
        val buffer = ByteBuffer.allocate(samples.size * 2).order(ByteOrder.LITTLE_ENDIAN)
        buffer.asShortBuffer().put(samples)
        return buffer.array()
    }

    private fun speechThenPause(): ByteArray {
        val random = Random(4)
        val talk = ShortArray(SOUND_RATE * SOUND_CHANNELS) { (random.nextGaussian() * 4_000).toInt().toShort() }
        val pause = ShortArray(SOUND_RATE * SOUND_CHANNELS * 3)
        return bytesOf(talk + pause + talk)
    }

    private class Trickle(private val inner: InputStream, private val piece: Int) : InputStream() {
        override fun read(): Int = inner.read()

        override fun read(b: ByteArray, off: Int, len: Int): Int = inner.read(b, off, minOf(len, piece))
    }

    private fun pump(sound: SoundSettings): PcmPump = PcmPump(
        ProcessedSound(weights = { null }).chainFor(sound),
        SOUND_CHANNELS,
        SOUND_RATE,
    )

    @Test
    fun `with nothing switched on the bytes come out as they went in, however they arrive`() {
        val input = speechThenPause()
        val out = ByteArrayOutputStream()

        val written = pump(SoundSettings()).run(Trickle(ByteArrayInputStream(input), 7), out)

        assertArrayEquals(input, out.toByteArray())
        assertEquals(input.size.toLong(), written)
    }

    @Test
    fun `skipping silence shortens the output and says how much it took out`() {
        val input = speechThenPause()
        val out = ByteArrayOutputStream()
        val pump = pump(SoundSettings(skipSilence = true))

        pump.run(ByteArrayInputStream(input), out)

        assertTrue("out ${out.size()} of ${input.size}", out.size() < input.size - SOUND_RATE * SOUND_CHANNELS * 2)
        assertTrue("skipped ${pump.skippedMs}ms", pump.skippedMs > 1_000)
        assertEquals(0, out.size() % (SOUND_CHANNELS * 2))
    }

    @Test
    fun `a stopped pipeline stops writing`() {
        val input = speechThenPause()
        val out = ByteArrayOutputStream()
        var wanted = true

        pump(SoundSettings()).run(ByteArrayInputStream(input), out) { wanted.also { wanted = false } }

        assertTrue("wrote ${out.size()} of ${input.size}", out.size() in 1..chunkBytes)
    }

    @Test
    fun `the decode command seeks before the input and names the processed format`() {
        val sound = ProcessedSound(ffmpeg = "ffmpeg", weights = { null })

        val fromStart = sound.decodeCommand("https://x/a.mp3", 0)
        val later = sound.decodeCommand("https://x/a.mp3", 90_500)

        assertTrue("-ss" !in fromStart)
        assertEquals(listOf("-ss", "90.500", "-i", "https://x/a.mp3"), later.subList(4, 8))
        assertTrue(later.containsAll(listOf("-f", "s16le", "-ar", "$SOUND_RATE", "-ac", "$SOUND_CHANNELS")))
    }

    @Test
    fun `a chain with a boost lifts a quiet recording`() {
        val quiet = bytesOf(
            ShortArray(SOUND_RATE * SOUND_CHANNELS * 4) {
                (Random(it.toLong()).nextGaussian() * 300).toInt().toShort()
            }
        )
        val out = ByteArrayOutputStream()

        PcmPump(PcmChain(SOUND_RATE, SOUND_CHANNELS, false, VolumeBoost.AUTO), SOUND_CHANNELS, SOUND_RATE)
            .run(ByteArrayInputStream(quiet), out)

        assertEquals(quiet.size, out.size())
        val louder = ByteBuffer.wrap(out.toByteArray()).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
        val before = ByteBuffer.wrap(quiet).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
        var peakOut = 0
        var peakIn = 0
        while (louder.hasRemaining()) {
            peakOut = maxOf(peakOut, kotlin.math.abs(louder.get().toInt()))
            peakIn = maxOf(peakIn, kotlin.math.abs(before.get().toInt()))
        }
        assertTrue("$peakOut over $peakIn", peakOut > peakIn)
    }
}
