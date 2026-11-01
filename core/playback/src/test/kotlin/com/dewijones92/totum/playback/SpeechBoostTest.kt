package com.dewijones92.totum.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin
import kotlin.math.sqrt

class SpeechBoostTest {

    private val rate = 44_100
    private val musicSeconds = 3f
    private val speechSeconds = 3f
    private val musicEnd = (rate * musicSeconds).toLong()

    private fun tone(amplitude: Float, seconds: Float, hz: Float): ShortArray =
        ShortArray((rate * seconds).toInt()) { i -> (sin(2 * PI * hz * i / rate) * amplitude * FULL).toInt().toShort() }

    private val musicThenSpeech = tone(LOUD_MUSIC, musicSeconds, 440f) + tone(QUIET_SPEECH, speechSeconds, 200f)

    private fun isSpeechAt(frame: Long) = frame >= musicEnd

    private fun run(gate: SpeechGate, lagFrames: Long = 0, detector: Boolean = true): ShortArray {
        var fed = 0L
        val boost = LoudnessBoost(rate).apply {
            level = VolumeBoost.AUTO
            speechGate = gate
            if (detector) speech = { frame -> if (frame > fed - lagFrames) null else isSpeechAt(frame) }
        }
        val out = ArrayList<Short>(musicThenSpeech.size)
        var at = 0
        while (at < musicThenSpeech.size) {
            val end = minOf(musicThenSpeech.size, at + CHUNK)
            fed = end.toLong()
            val result = boost.process(musicThenSpeech.copyOfRange(at, end), end - at)
            for (i in 0 until boost.outputSamples) out += result[i]
            at = end
        }
        val tail = boost.drain()
        for (i in 0 until boost.outputSamples) out += tail[i]
        return out.toShortArray()
    }

    private fun ShortArray.rms(fromSeconds: Float, toSeconds: Float): Double {
        val from = (rate * fromSeconds).toInt()
        val to = minOf(size, (rate * toSeconds).toInt())
        var sum = 0.0
        for (i in from until to) sum += this[i].toDouble() * this[i]
        return sqrt(sum / (to - from))
    }

    private val speechIn = musicThenSpeech.rms(4.5f, 6f)
    private val musicIn = musicThenSpeech.rms(1.5f, 3f)

    @Test
    fun `today the loud music sets the level, so the quiet speech after it stays quiet`() {
        val out = run(SpeechGate.OFF)
        assertTrue("speech lifted only ${out.rms(4.5f, 6f) / speechIn}x", out.rms(4.5f, 6f) < speechIn * 3)
    }

    @Test
    fun `measuring only the speech lifts the speech to the comfortable level`() {
        val out = run(SpeechGate.MEASURE)
        val lift = out.rms(4.5f, 6f) / speechIn
        assertTrue("speech lifted ${"%.1f".format(lift)}x", lift > 6)
    }

    @Test
    fun `lifting only the speech leaves the music alone and lifts the speech`() {
        val out = run(SpeechGate.LIFT)
        val music = out.rms(1.5f, 3f) / musicIn
        val speech = out.rms(4.5f, 6f) / speechIn
        assertEquals("music kept at ${"%.2f".format(music)}x", 1.0, music, 0.15)
        assertTrue("speech lifted ${"%.1f".format(speech)}x", speech > 6)
    }

    @Test
    fun `a detector that answers late still gets there, the music never pumped up`() {
        val out = run(SpeechGate.LIFT, lagFrames = (rate * 0.3).toLong())
        assertEquals(1.0, out.rms(1.5f, 3f) / musicIn, 0.15)
        assertTrue(out.rms(4.5f, 6f) / speechIn > 6)
    }

    @Test
    fun `with no speech model it boosts as it does today`() {
        val today = run(SpeechGate.OFF)
        val noModel = run(SpeechGate.LIFT, detector = false)
        assertEquals(today.rms(4.5f, 6f), noModel.rms(4.5f, 6f), today.rms(4.5f, 6f) * 0.05)
    }

    @Test
    fun `nothing clips in either mode`() {
        listOf(SpeechGate.MEASURE, SpeechGate.LIFT).forEach { gate ->
            assertEquals(gate.name, 0, run(gate).count { abs(it.toInt()) >= Short.MAX_VALUE - 1 })
        }
    }

    private companion object {
        const val FULL = 32_767f
        const val LOUD_MUSIC = 0.3f
        const val QUIET_SPEECH = 0.01f
        const val CHUNK = 1_024
    }
}
