package com.dewijones92.totum.playback

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin

class SilenceCutterTest {

    @Test
    fun `a pause shorter than the minimum is left exactly as it was`() {
        val input = speech(TONE_MS) + silence(SHORT_PAUSE_MS) + speech(TONE_MS)

        val output = cut(input)

        assertArrayEquals(input, output)
    }

    @Test
    fun `pauses of every length over the minimum come down to forty milliseconds`() {
        for (pauseMs in listOf(200, 300, 1_000, 4_000, 10_000)) {
            val input = speech(TONE_MS) + silence(pauseMs) + speech(TONE_MS)

            val output = cut(input)

            assertEquals(
                "a ${pauseMs}ms pause",
                frames(2 * TONE_MS + KEPT_MS),
                output.size,
            )
        }
    }

    @Test
    fun `the sound either side of a pause is passed through untouched`() {
        val before = speech(TONE_MS)
        val after = speech(TONE_MS, phase = 1.3)

        val output = cut(before + silence(1_000) + after)

        assertArrayEquals(before, output.copyOfRange(0, before.size))
        assertArrayEquals(after, output.copyOfRange(output.size - after.size, output.size))
    }

    @Test
    fun `the join fades to nothing so it cannot click`() {
        val input = speech(TONE_MS) + noiseFloor(1_000, level = 900) + speech(TONE_MS)

        val output = cut(input)
        val join = frames(TONE_MS) + frames(KEPT_MS) / 2

        assertTrue(
            "the samples either side of the splice must be near zero, not the ${abs(output[join].toInt())} " +
                "of the noise floor they were cut from",
            abs(output[join - 1].toInt()) <= FADED && abs(output[join].toInt()) <= FADED,
        )
        assertTrue("the fade starts at full level", abs(abs(output[frames(TONE_MS)].toInt()) - 900) <= FADE_START_SLACK)
    }

    @Test
    fun `what it says it skipped is exactly what it removed`() {
        val input = speech(TONE_MS) + silence(700) + speech(TONE_MS) + silence(2_300) + speech(TONE_MS)
        val cutter = SilenceCutter(RATE, 1)

        val output = drive(cutter, input, chunk = 997)

        assertEquals(input.size - output.size, cutter.skippedFrames.toInt())
        assertEquals(2L, cutter.gapsCut)
    }

    @Test
    fun `the result does not depend on how the audio arrives`() {
        val input = speech(TONE_MS) + silence(450) + speech(TONE_MS) + silence(160) + speech(90) + silence(3_000)

        val whole = drive(SilenceCutter(RATE, 1), input, chunk = input.size)

        for (chunk in listOf(1, 7, 64, 1_024, 4_410)) {
            assertArrayEquals("in chunks of $chunk", whole, drive(SilenceCutter(RATE, 1), input, chunk))
        }
    }

    @Test
    fun `stereo is only a pause when both channels are quiet`() {
        val talkingLeft = interleave(speech(1_000), silence(1_000))
        val bothQuiet = interleave(silence(1_000), silence(1_000))
        val input = interleave(speech(TONE_MS), speech(TONE_MS)) + talkingLeft + bothQuiet

        val output = drive(SilenceCutter(RATE, 2), input, chunk = 2_000, channels = 2)

        assertEquals(
            "the one-sided second must stay and the silent one must go",
            (frames(TONE_MS) + frames(1_000) + frames(KEPT_MS)) * 2,
            output.size,
        )
    }

    @Test
    fun `speech just above the cut level is never mistaken for a pause`() {
        val quiet = ShortArray(
            frames(1_000)
        ) { if (it % 2 == 0) CUT_LEVEL_PLUS_ONE else (-CUT_LEVEL_PLUS_ONE).toShort() }

        assertArrayEquals(quiet, cut(quiet))
    }

    @Test
    fun `a pause is only counted once playback reaches it`() {
        val cutter = SilenceCutter(RATE, 1)
        drive(cutter, speech(TONE_MS) + silence(1_000) + speech(TONE_MS), chunk = 512)
        val cutAt = (frames(TONE_MS) + frames(KEPT_MS / 2)).toLong()

        assertEquals("before the cut is heard", 0L, cutter.heard.skippedBy(cutAt - 1))
        assertEquals("once it is heard", cutter.skippedFrames, cutter.heard.skippedBy(cutAt))
        assertEquals("and from then on", cutter.skippedFrames, cutter.heard.skippedBy(cutAt + frames(TONE_MS)))
    }

    @Test
    fun `a long pause heard while it is still being cut keeps counting`() {
        val cutter = SilenceCutter(RATE, 1)
        cutter.process(speech(TONE_MS) + silence(500), frames(TONE_MS + 500))
        val cutAt = (frames(TONE_MS) + frames(KEPT_MS / 2)).toLong()
        val heardEarly = cutter.heard.skippedBy(cutAt)

        cutter.process(silence(2_000), frames(2_000))

        assertEquals(cutter.skippedFrames, cutter.heard.skippedBy(cutAt))
        assertTrue("it grew while the pause went on", cutter.heard.skippedBy(cutAt) > heardEarly)
    }

    @Test
    fun `a recording that ends in silence ends with its pause cut`() {
        val input = speech(TONE_MS) + silence(3_000)

        val output = cut(input)

        assertEquals(frames(TONE_MS + KEPT_MS), output.size)
    }

    @Test
    fun `a recording that ends in a short pause keeps it`() {
        val input = speech(TONE_MS) + silence(SHORT_PAUSE_MS)

        assertArrayEquals(input, cut(input))
    }

    @Test
    fun `quiet speech is never cut away as if it were silence`() {
        val input = bursts(speechPeak = 197, noise = 0, count = 6)

        val output = cut(input)

        assertTrue(
            "lost ${input.size - output.size} of ${frames(6 * TONE_MS)} frames of speech",
            output.size >= frames(6 * TONE_MS)
        )
    }

    @Test
    fun `a quiet recording with hiss in its pauses still has them cut`() {
        val input = bursts(speechPeak = 1_000, noise = 120, count = 6)

        val output = cut(input)

        assertTrue(
            "only ${input.size - output.size} frames removed",
            input.size - output.size >= frames(5 * (PAUSE_MS - KEPT_MS))
        )
    }

    @Test
    fun `a normally mastered recording is cut the way PipePipe cuts it`() {
        val input = bursts(speechPeak = 20_000, noise = 800, count = 6)

        val output = cut(input)

        assertTrue(
            "only ${input.size - output.size} frames removed",
            input.size - output.size >= frames(5 * (PAUSE_MS - KEPT_MS))
        )
    }

    @Test
    fun `pauses louder than PipePipe's cut level are kept, as PipePipe keeps them`() {
        val input = bursts(speechPeak = 20_000, noise = 1_500, count = 6)

        assertEquals(input.size, cut(input).size)
    }

    @Test
    fun `quiet speech after loud music loses at most its opening word`() {
        val music = ShortArray(frames(5_000)) { i -> (20_000 * sin(2 * PI * 330 * i / RATE)).toInt().toShort() }

        val output = cut(music + sentences(speechPeak = 600, noise = 30, count = 4))

        assertTrue(
            "kept ${millisOf(output.size - music.size)}ms after the music, of ${4 * SENTENCE_SPEECH_MS}ms of speech",
            output.size - music.size >= frames(4 * SENTENCE_SPEECH_MS - WORD_MS),
        )
    }

    @Test
    fun `one loud click does not make the quiet speech after it disappear`() {
        val output = cut(ShortArray(1) { 30_000 } + sentences(speechPeak = 600, noise = 30, count = 4))

        assertTrue(
            "kept ${millisOf(output.size)}ms of ${4 * SENTENCE_SPEECH_MS}ms of speech",
            output.size >= frames(4 * SENTENCE_SPEECH_MS - WORD_MS),
        )
    }

    @Test
    fun `pauses PipePipe cuts in a moderately quiet recording are cut too`() {
        for ((peak, noise) in listOf(3_000 to 500, 6_000 to 800, 8_000 to 900)) {
            val input = bursts(speechPeak = peak, noise = noise, count = 6)

            val removed = input.size - cut(input).size

            assertTrue("$peak/$noise: only $removed frames removed", removed >= frames(5 * (PAUSE_MS - KEPT_MS)))
        }
    }

    @Test
    fun `quiet speech keeps its words and loses its pauses whatever came before it`() {
        val before = mapOf(
            "nothing" to ShortArray(0),
            "a click" to ShortArray(1) { 30_000 },
            "a cough" to cough(),
            "5s of music" to ShortArray(frames(5_000)) { i ->
                (20_000 * sin(2 * PI * 330 * i / RATE)).toInt().toShort()
            },
        )
        val clearlyUnderTheSpeech = listOf(
            600 to 20,
            800 to 40,
            1_000 to 60,
            2_000 to 150,
            3_000 to 250,
            800 to 100,
            1_000 to 140
        )
        val barelyUnderTheSpeech = listOf(800 to 150, 600 to 100)
        for ((peak, hiss) in clearlyUnderTheSpeech + barelyUnderTheSpeech) {
            val speech = spokenSentences(peak, hiss)
            for ((what, lead) in before) {
                val output = cut(lead + speech.audio)
                val kept = output.size - lead.size
                val energy = energyOf(output, from = lead.size) / energyOf(speech.audio)
                assertTrue(
                    "$peak/$hiss after $what: kept only ${"%.3f".format(energy)} of the speech's energy",
                    energy >= ENERGY_KEPT,
                )
                if ((peak to hiss) in barelyUnderTheSpeech) continue
                assertTrue(
                    "$peak/$hiss after $what: removed only ${speech.totalMs - millisOf(kept)}ms " +
                        "of ${speech.pauseMs}ms of pauses",
                    speech.totalMs - millisOf(kept) >= speech.pauseMs * MOSTLY_CUT,
                )
            }
        }
    }

    @Test
    fun `a music bed well under the speech lets its pauses be cut`() {
        val bed = ShortArray(frames(30_000)) { i -> (900 * sin(2 * PI * 110 * i / RATE)).toInt().toShort() }
        val speech = spokenSentences(peak = 8_000, hiss = 0)
        val mixed = ShortArray(speech.audio.size) { (speech.audio[it] + bed[it % bed.size]).toShort() }

        val removedMs = millisOf(mixed.size - cut(mixed).size)

        assertTrue("removed only ${removedMs}ms of ${speech.pauseMs}ms", removedMs >= speech.pauseMs * MOSTLY_CUT)
    }

    @Test
    fun `a quiet recording that starts talking straight away keeps its opening`() {
        val speech = spokenSentences(peak = 600, hiss = 100)

        val energy = energyOf(cut(speech.audio)) / energyOf(speech.audio)

        assertTrue("kept only ${"%.3f".format(energy)} of the speech's energy", energy >= ENERGY_KEPT)
    }

    @Test
    fun `a long pause being cut is not mistaken for nothing to cut`() {
        val cutter = SilenceCutter(RATE, 1)
        val input = speech(TONE_MS) + silence(70_000)
        cutter.process(input, input.size)

        assertTrue(
            "${cutter.levels.framesSinceCut} frames since the last cut, in the middle of one",
            cutter.levels.framesSinceCut < frames(1_000),
        )
    }

    private fun energyOf(samples: ShortArray, from: Int = 0): Double {
        var sum = 0.0
        for (i in from until samples.size) sum += samples[i].toDouble() * samples[i]
        return sum
    }

    private class Spoken(val audio: ShortArray, val speechMs: Int, val pauseMs: Int) {
        val totalMs: Int get() = speechMs + pauseMs
    }

    private fun spokenSentences(peak: Int, hiss: Int): Spoken {
        val random = java.util.Random(SEED)
        var audio = ShortArray(0)
        var speechMs = 0
        var pauseMs = 0
        repeat(SENTENCES) {
            repeat(WORDS) { word ->
                val loudness = 0.6 + 0.4 * random.nextDouble()
                audio += ShortArray(frames(WORD_MS)) { i ->
                    val envelope = sin(PI * i / frames(WORD_MS))
                    val voice = peak * loudness * envelope * sin(2 * PI * VOICE_HZ * i / RATE)
                    (voice + hiss * random.nextGaussian() / GAUSSIAN_PEAK).toInt().toShort()
                }
                speechMs += WORD_MS
                val gap = if (word == WORDS - 1) PAUSE_MS else WORD_GAP_MS
                audio += ShortArray(frames(gap)) { (hiss * random.nextGaussian() / GAUSSIAN_PEAK).toInt().toShort() }
                if (word == WORDS - 1) pauseMs += gap else speechMs += gap
            }
        }
        return Spoken(audio, speechMs, pauseMs)
    }

    private fun cough(): ShortArray {
        val random = java.util.Random(SEED)
        return ShortArray(frames(COUGH_MS)) { (COUGH_PEAK * random.nextGaussian() / GAUSSIAN_PEAK).toInt().toShort() }
    }

    private fun sentences(speechPeak: Int, noise: Int, count: Int): ShortArray {
        var all = ShortArray(0)
        repeat(count) {
            repeat(WORDS) { word ->
                all += ShortArray(frames(WORD_MS)) { i ->
                    (speechPeak * sin(2 * PI * VOICE_HZ * i / RATE)).toInt().toShort()
                }
                all += noise(if (word == WORDS - 1) PAUSE_MS else WORD_GAP_MS, noise)
            }
        }
        return all
    }

    private fun millisOf(samples: Int): Int = samples * 1_000 / RATE

    private fun noise(ms: Int, level: Int) = ShortArray(
        frames(ms)
    ) { i -> (if (i % 2 == 0) level else -level).toShort() }

    private fun bursts(speechPeak: Int, noise: Int, count: Int): ShortArray {
        var all = ShortArray(0)
        repeat(count) {
            all += ShortArray(frames(TONE_MS)) { i ->
                (speechPeak * sin(2 * PI * VOICE_HZ * i / RATE)).toInt().toShort()
            }
            all += ShortArray(frames(PAUSE_MS)) { i -> (if (i % 2 == 0) noise else -noise).toShort() }
        }
        return all
    }

    private fun cut(input: ShortArray): ShortArray = drive(SilenceCutter(RATE, 1), input, chunk = 1_024)

    private fun drive(cutter: SilenceCutter, input: ShortArray, chunk: Int, channels: Int = 1): ShortArray {
        val out = mutableListOf<Short>()
        var at = 0
        while (at < input.size) {
            val end = minOf(input.size, at + chunk * channels)
            val piece = input.copyOfRange(at, end)
            val result = cutter.process(piece, piece.size / channels)
            for (i in 0 until cutter.outputSamples) out += result[i]
            at = end
        }
        val tail = cutter.endOfStream()
        for (i in 0 until cutter.outputSamples) out += tail[i]
        return out.toShortArray()
    }

    private fun speech(ms: Int, phase: Double = 0.0) = ShortArray(frames(ms)) { i ->
        val envelope = 0.5 + 0.5 * sin(2 * PI * SYLLABLE_HZ * i / RATE + phase)
        (AMPLITUDE * envelope * sin(2 * PI * VOICE_HZ * i / RATE + phase) + AMPLITUDE / 2).toInt().toShort()
    }

    private fun silence(ms: Int) = ShortArray(frames(ms))

    private fun noiseFloor(ms: Int, level: Int) = ShortArray(frames(ms)) {
        if (it % 2 == 0) level.toShort() else (-level).toShort()
    }

    private fun interleave(left: ShortArray, right: ShortArray) =
        ShortArray(left.size * 2) { if (it % 2 == 0) left[it / 2] else right[it / 2] }

    private fun frames(ms: Int) = SilenceCutter.framesIn(ms, RATE)

    private operator fun ShortArray.plus(other: ShortArray): ShortArray = ShortArray(size + other.size) {
        if (it < size) this[it] else other[it - size]
    }

    private companion object {
        const val RATE = 44_100
        const val TONE_MS = 500
        const val PAUSE_MS = 1_000
        const val WORDS = 5
        const val SENTENCES = 8
        const val SEED = 7L
        const val GAUSSIAN_PEAK = 3.0
        const val COUGH_MS = 300
        const val COUGH_PEAK = 25_000
        const val MOSTLY_CUT = 0.7
        const val ENERGY_KEPT = 0.99
        const val WORD_MS = 250
        const val WORD_GAP_MS = 80
        const val SENTENCE_SPEECH_MS = WORDS * WORD_MS + (WORDS - 1) * WORD_GAP_MS
        const val SHORT_PAUSE_MS = 120
        const val KEPT_MS = 2 * SilenceCutter.PAD_MS
        const val AMPLITUDE = 8_000.0
        const val VOICE_HZ = 180.0
        const val SYLLABLE_HZ = 4.0
        const val FADED = 50
        const val FADE_START_SLACK = 50
        const val CUT_LEVEL_PLUS_ONE: Short = (SilenceCutter.THRESHOLD + 1).toShort()
    }
}
