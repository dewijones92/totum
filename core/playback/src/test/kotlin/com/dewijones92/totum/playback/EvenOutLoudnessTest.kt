package com.dewijones92.totum.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin
import kotlin.math.sqrt

class EvenOutLoudnessTest {

    private val rate = 44_100

    private fun tone(amplitude: Float, seconds: Float): ShortArray {
        val count = (rate * seconds).toInt()
        return ShortArray(count) { i -> (sin(2 * PI * TONE_HZ * i / rate) * amplitude * FULL_SCALE).toInt().toShort() }
    }

    private fun ShortArray.rms(from: Int, to: Int = size): Double {
        var sum = 0.0
        for (i in from until to) sum += this[i].toDouble() * this[i].toDouble()
        return sqrt(sum / (to - from))
    }

    private fun run(level: VolumeBoost, input: ShortArray): ShortArray {
        val boost = LoudnessBoost(rate).apply { this.level = level }
        val out = ArrayList<Short>(input.size)
        var at = 0
        while (at < input.size) {
            val end = minOf(input.size, at + CHUNK)
            val result = boost.process(input.copyOfRange(at, end), end - at)
            for (i in 0 until boost.outputSamples) out += result[i]
            at = end
        }
        val tail = boost.drain()
        for (i in 0 until boost.outputSamples) out += tail[i]
        return out.toShortArray()
    }

    private fun seconds(s: Float) = (rate * s).toInt()

    private fun shortQuietSpell(level: VolumeBoost): Double {
        val input = tone(LOUD, seconds = 4f) + tone(QUIET, seconds = 3f) + tone(LOUD, seconds = 2f)
        val out = run(level, input)
        val loud = out.rms(seconds(3f), seconds(4f))
        val quiet = out.rms(seconds(6f), seconds(7f))
        return loud / quiet
    }

    @Test
    fun `a short quiet spell between loud ones comes up close to them`() {
        val ratio = shortQuietSpell(VolumeBoost.EVEN_OUT)
        assertTrue("the quiet spell came out ${"%.1f".format(ratio)}x below the loud; wanted within 2x", ratio < 2.0)
    }

    @Test
    fun `the item-level boost leaves the same spell far quieter, which is the difference`() {
        val ratio = shortQuietSpell(VolumeBoost.AUTO)
        assertTrue("AUTO evened the spell to ${"%.1f".format(ratio)}x, so the new mode adds nothing", ratio > 4.0)
    }

    @Test
    fun `digital silence stays silent`() {
        val input = tone(LOUD, seconds = 3f) + ShortArray(seconds(4f))
        val out = run(VolumeBoost.EVEN_OUT, input)

        val after = out.copyOfRange(seconds(3.2f), seconds(7f))
        assertEquals(0, after.count { abs(it.toInt()) > 1 })
    }

    @Test
    fun `swinging between loud and quiet never clips`() {
        val input = (1..4).fold(ShortArray(0)) { acc, _ -> acc + tone(QUIET, 2f) + tone(VERY_LOUD, 1f) }
        val out = run(VolumeBoost.EVEN_OUT, input)

        assertEquals(0, out.count { abs(it.toInt()) >= Short.MAX_VALUE - 1 })
    }

    private companion object {
        const val FULL_SCALE = 32_768f
        const val TONE_HZ = 200.0
        const val LOUD = 0.3f
        const val VERY_LOUD = 0.9f
        const val QUIET = 0.01f
        const val CHUNK = 1_024
    }
}
