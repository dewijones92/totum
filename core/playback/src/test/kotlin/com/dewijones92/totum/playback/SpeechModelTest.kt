package com.dewijones92.totum.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs

class SpeechModelTest {

    private val weights by lazy { File("src/main/res/raw/silero_vad.bin").inputStream().use(SpeechWeights::read) }

    @Test
    fun `it gives the same answers as the reference runtime, chunk after chunk`() {
        val input = floats("silero_reference_input.f32")
        val expected = floats("silero_reference_probs.f32")
        val model = SpeechModel(weights)

        var worst = 0f
        for (chunk in expected.indices) {
            val actual = model.probability(
                input.copyOfRange(chunk * SpeechModel.CHUNK, (chunk + 1) * SpeechModel.CHUNK)
            )
            worst = maxOf(worst, abs(actual - expected[chunk]))
        }

        assertTrue("worst difference $worst over ${expected.size} chunks", worst < MATCH)
    }

    @Test
    fun `speech is told apart from silence`() {
        val input = floats("silero_reference_input.f32")
        val model = SpeechModel(weights)
        val probabilities = (0 until input.size / SpeechModel.CHUNK).map { chunk ->
            model.probability(input.copyOfRange(chunk * SpeechModel.CHUNK, (chunk + 1) * SpeechModel.CHUNK))
        }

        val silence = probabilities.subList(SILENCE_FROM, SILENCE_TO)
        val speech = probabilities.subList(SPEECH_FROM, probabilities.size)
        assertTrue("silence scored ${silence.max()}", silence.max() < QUIET)
        assertTrue("speech scored ${speech.average()} on average", speech.average() > SPOKEN)
    }

    @Test
    fun `the model's filter bank is the windowed transform the fast one replaces`() {
        val filter = SpeechWeights.FILTER
        var worst = 0.0
        for (bin in 0 until SpeechWeights.BINS) {
            for (n in 0 until filter) {
                val angle = 2 * Math.PI * bin * n / filter
                val window = SpeechModel.hann(n)
                val real = weights[weights.basis.start + bin * filter + n]
                val imaginary = weights[weights.basis.start + (bin + SpeechWeights.BINS) * filter + n]
                worst = maxOf(worst, abs(real - window * Math.cos(angle)), abs(imaginary + window * Math.sin(angle)))
            }
        }

        assertTrue("worst difference $worst", worst < BASIS_MATCH)
    }

    @Test
    fun `reset forgets everything it heard`() {
        val input = floats("silero_reference_input.f32")
        val model = SpeechModel(weights)
        val first = model.probability(input.copyOfRange(0, SpeechModel.CHUNK))
        repeat(REPEATS) { model.probability(input.copyOfRange(SpeechModel.CHUNK, 2 * SpeechModel.CHUNK)) }

        model.reset()

        assertEquals(first, model.probability(input.copyOfRange(0, SpeechModel.CHUNK)))
    }

    @Test
    fun `a chunk of the wrong size is refused`() {
        assertThrows(
            IllegalArgumentException::class.java
        ) { SpeechModel(weights).probability(FloatArray(SpeechModel.CHUNK - 1)) }
    }

    @Test
    fun `a file that is not the model is refused`() {
        assertThrows(IllegalArgumentException::class.java) {
            SpeechWeights.read(
                ByteArrayInputStream("NOPE\u0001\u0000\u0000\u0000\u0000\u0000\u0000\u0000".toByteArray())
            )
        }
    }

    private fun floats(name: String): FloatArray {
        val bytes = requireNotNull(javaClass.classLoader?.getResource(name)) { "missing $name" }.readBytes()
        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer()
        return FloatArray(buffer.remaining()).also { buffer.get(it) }
    }

    private companion object {
        const val MATCH = 1e-4f
        const val BASIS_MATCH = 1e-5
        const val SILENCE_FROM = 12
        const val SILENCE_TO = 18
        const val SPEECH_FROM = 24
        const val QUIET = 0.1f
        const val SPOKEN = 0.5
        const val REPEATS = 5
    }
}
