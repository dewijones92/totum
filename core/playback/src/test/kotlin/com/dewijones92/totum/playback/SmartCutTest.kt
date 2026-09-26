package com.dewijones92.totum.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Random

class SmartCutTest {

    private val weights by lazy { File("src/main/res/raw/silero_vad.bin").inputStream().use(SpeechWeights::read) }

    private val speech: ShortArray by lazy {
        val bytes = requireNotNull(javaClass.classLoader?.getResource("silero_reference_input.f32")).readBytes()
        val floats = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer()
        val all = FloatArray(floats.remaining()).also { floats.get(it) }
        val start = SPEECH_FROM_CHUNK * SpeechModel.CHUNK
        ShortArray(all.size - start) { (all[start + it] * FULL_SCALE * LOUDER).toInt().toShort() }
    }

    @Test
    fun `the track says speech where people talk and not in a hiss`() {
        val track = SpeechTrack(SpeechModel(weights), RATE)
        val hiss = hiss(seconds = 1f, sigma = 40.0)
        (hiss + speech).forEach { track.push(it / FULL_SCALE) }

        val inHiss = (0 until hiss.size step STEP).mapNotNull { track.isSpeech(it.toLong()) }
        val inSpeech = (hiss.size until hiss.size + speech.size step STEP).mapNotNull { track.isSpeech(it.toLong()) }
        assertTrue("hiss called speech ${inHiss.count { it }} of ${inHiss.size} times", inHiss.count { it } == 0)
        assertTrue(
            "speech called speech ${inSpeech.count { it }} of ${inSpeech.size} times",
            inSpeech.count { it } > inSpeech.size * MOSTLY
        )
    }

    @Test
    fun `the track will not guess about audio it has not heard yet`() {
        val track = SpeechTrack(SpeechModel(weights), RATE)
        speech.copyOfRange(0, SpeechModel.CHUNK).forEach { track.push(it / FULL_SCALE) }

        assertNull(track.isSpeech(SpeechModel.CHUNK.toLong() * FUTURE_CHUNKS))
    }

    @Test
    fun `the track reads audio at the player's own rate`() {
        val native = SpeechTrack(SpeechModel(weights), RATE)
        val tripled = SpeechTrack(SpeechModel(weights), RATE * 3)
        speech.forEach { native.push(it / FULL_SCALE) }
        speech.forEach { sample -> repeat(3) { tripled.push(sample / FULL_SCALE) } }

        val agree = (0 until speech.size step STEP).count { at ->
            native.isSpeech(at.toLong()) == tripled.isSpeech(at * 3L)
        }
        assertTrue("agreed on $agree of ${speech.size / STEP}", agree > speech.size / STEP * MOSTLY)
    }

    @Test
    fun `until the detector answers, a frame is judged as standard would judge it`() {
        val held = ArrayList<Runnable>()
        val slow = SpeechWorker { held += it }
        val input = withHiss(speech, BACKGROUND_HISS, SEED) + hiss(seconds = 1f, sigma = NOISY_HISS) +
            withHiss(speech, BACKGROUND_HISS, SEED + 1)

        val waiting = SilenceCutter(RATE, 1, speech = SpeechTrack(SpeechModel(weights), RATE, slow)).cutAll(input)
        val standard = SilenceCutter(RATE, 1).cutAll(input)

        assertEquals(standard, waiting)
        assertTrue("nothing was handed to the detector", held.isNotEmpty())
    }

    @Test
    fun `a detector on its own thread reaches the same verdicts`() {
        val input = withHiss(speech + speech, BACKGROUND_HISS, SEED)
        val inline = SpeechTrack(SpeechModel(weights), RATE)
        val executor = java.util.concurrent.Executors.newSingleThreadExecutor()
        val background = SpeechTrack(SpeechModel(weights), RATE, SpeechWorker(executor::execute))

        input.forEach { inline.push(it / FULL_SCALE) }
        input.forEach { background.push(it / FULL_SCALE) }
        inline.finish()
        background.finish()
        executor.shutdown()
        executor.awaitTermination(TEN, java.util.concurrent.TimeUnit.SECONDS)

        assertEquals(inline.chunksHeard, background.chunksHeard)
        assertEquals(inline.chunksSpeech, background.chunksSpeech)
    }

    @Test
    fun `a chunk that arrives after its frames were decided is not worth a verdict`() {
        val held = ArrayList<Runnable>()
        val late = SpeechTrack(SpeechModel(weights), RATE, SpeechWorker { held += it })
        val input = speech + speech
        SilenceCutter(RATE, 1, speech = late).cutAll(input)

        held.forEach(Runnable::run)

        val stillAhead = SpeechTrack.BEFORE_CHUNKS + 1L
        assertTrue(
            "dropped ${late.chunksDropped} of ${late.chunksHeard}",
            late.chunksDropped >= late.chunksHeard - stillAhead
        )
        assertNull(late.isSpeech(0))
    }

    @Test
    fun `a track that has been replaced does no more work`() {
        val held = ArrayList<Runnable>()
        val cutter =
            SilenceCutter(RATE, 1, speech = SpeechTrack(SpeechModel(weights), RATE, SpeechWorker { held += it }))
        val old = requireNotNull(cutter.speech)
        cutter.process(speech.copyOf(), speech.size)

        cutter.speech = SpeechTrack(SpeechModel(weights), RATE)
        held.forEach(Runnable::run)

        assertEquals(0L, old.chunksHeard)
    }

    @Test
    fun `gaps where chunks were dropped never turn speech into not-speech`() {
        val input = withHiss(speech + speech + speech, BACKGROUND_HISS, SEED)
        val steady = SpeechTrack(SpeechModel(weights), RATE)
        input.forEach { steady.push(it / FULL_SCALE) }
        steady.finish()
        val gappy = SpeechTrack(SpeechModel(weights), RATE)
        val block = SpeechTrack.BLOCK
        for (start in input.indices step block) {
            if ((start / block) % 2 == 0) gappy.isSpeech((start + block + SpeechModel.CHUNK * 3).toLong())
            input.copyOfRange(start, minOf(input.size, start + block)).forEach { gappy.push(it / FULL_SCALE) }
        }
        gappy.finish()

        val judged = input.indices.filter { (it / block) % 2 == 1 }.filter { it % STEP == 0 }
        val flipped = judged.count { frame ->
            steady.isSpeech(frame.toLong()) == true && gappy.isSpeech(frame.toLong()) == false
        }
        assertTrue("dropped ${gappy.chunksDropped} chunks", gappy.chunksDropped > 0)
        assertEquals("frames the steady detector called speech that the gappy one called not-speech", 0, flipped)
    }

    @Test
    fun `smart cuts a noisy pause that standard has to keep`() {
        val pauseMs = MILLIS

        val standard = extraRemovedMs(pauseMs, NOISY_HISS) { SilenceCutter(RATE, 1) }
        val smart = extraRemovedMs(
            pauseMs,
            NOISY_HISS
        ) { SilenceCutter(RATE, 1, speech = SpeechTrack(SpeechModel(weights), RATE)) }

        assertTrue("standard removed ${standard}ms of a ${pauseMs}ms pause", standard < SOME_MS)
        assertTrue("smart removed ${smart}ms of a ${pauseMs}ms pause", smart > MOST_OF_THE_PAUSE_MS)
    }

    @Test
    fun `smart switched on after the cutter was made cuts the same as smart from the start`() {
        val fromTheStart = extraRemovedMs(MILLIS, NOISY_HISS) {
            SilenceCutter(RATE, 1, speech = SpeechTrack(SpeechModel(weights), RATE))
        }
        val switchedOn = extraRemovedMs(MILLIS, NOISY_HISS) {
            SilenceCutter(RATE, 1).also { it.speech = SpeechTrack(SpeechModel(weights), RATE) }
        }

        assertEquals(fromTheStart, switchedOn)
    }

    @Test
    fun `smart switched on while audio is already flowing judges the frames that follow`() {
        val cutter = SilenceCutter(RATE, 1)
        val lead = withHiss(speech, BACKGROUND_HISS, SEED)
        cutter.process(lead.copyOf(), lead.size)

        cutter.speech = SpeechTrack(SpeechModel(weights), RATE)
        val rest = hiss(seconds = 1f, sigma = NOISY_HISS) + withHiss(speech, BACKGROUND_HISS, SEED + 1)
        cutter.process(rest.copyOf(), rest.size)
        cutter.endOfStream()

        assertTrue("${cutter.smart}", cutter.smart.notSpeechFrames > RATE / 2)
        assertTrue("${cutter.smart}", cutter.smart.cutBeyondStandard > 0)
    }

    @Test
    fun `smart never cuts a loud stretch that is not speech`() {
        val smart = extraRemovedMs(
            MILLIS,
            LOUD_NOISE
        ) { SilenceCutter(RATE, 1, speech = SpeechTrack(SpeechModel(weights), RATE)) }

        assertTrue("removed ${smart}ms of a loud stretch", smart < SOME_MS)
    }

    private fun extraRemovedMs(pauseMs: Long, pauseSigma: Double, cutter: () -> SilenceCutter): Long {
        val hissUnder = { samples: ShortArray, seed: Long -> withHiss(samples, BACKGROUND_HISS, seed) }
        val without = hissUnder(speech + speech, SEED)
        val gap = hiss(seconds = pauseMs / MILLIS.toFloat(), sigma = pauseSigma)
        val with = hissUnder(speech, SEED) + gap + hissUnder(speech, SEED + 1)
        return cutter().cutAll(with) - cutter().cutAll(without)
    }

    private fun withHiss(samples: ShortArray, sigma: Double, seed: Long): ShortArray {
        val random = Random(seed)
        return ShortArray(samples.size) {
            (samples[it] + random.nextGaussian() * sigma).toInt().coerceIn(
                Short.MIN_VALUE.toInt(),
                Short.MAX_VALUE.toInt()
            ).toShort()
        }
    }

    private fun SilenceCutter.cutAll(input: ShortArray): Long {
        process(input.copyOf(), input.size)
        endOfStream()
        return skippedFrames * MILLIS / RATE
    }

    private fun hiss(seconds: Float, sigma: Double): ShortArray {
        val random = Random(SEED)
        return ShortArray((RATE * seconds).toInt()) { (random.nextGaussian() * sigma).toInt().toShort() }
    }

    private operator fun ShortArray.plus(other: ShortArray): ShortArray =
        ShortArray(size + other.size) { if (it < size) this[it] else other[it - size] }

    private companion object {
        const val RATE = 16_000
        const val FULL_SCALE = 32_768f
        const val LOUDER = 3f
        const val SPEECH_FROM_CHUNK = 24
        const val STEP = 160
        const val MOSTLY = 0.7
        const val FUTURE_CHUNKS = 3L
        const val NOISY_HISS = 400.0
        const val BACKGROUND_HISS = 400.0
        const val LOUD_NOISE = 4_000.0
        const val SOME_MS = 200L
        const val MOST_OF_THE_PAUSE_MS = 700L
        const val MILLIS = 1_000L
        const val SEED = 3L
        const val TEN = 10L
    }
}
