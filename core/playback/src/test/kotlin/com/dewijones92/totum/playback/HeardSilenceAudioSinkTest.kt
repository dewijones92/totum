package com.dewijones92.totum.playback

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.audio.AudioSink
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.lang.reflect.Proxy
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.sin

@UnstableApi
class HeardSilenceAudioSinkTest {

    private val cutter = SilenceCuttingAudioProcessor().apply {
        enabled = true
        configure(AudioProcessor.AudioFormat(RATE, 1, C.ENCODING_PCM_16BIT))
        flush(AudioProcessor.StreamMetadata.DEFAULT)
    }
    private var sinkPositionUs = 0L
    private var onBuffer: () -> Unit = {}
    private var innerListener: AudioSink.Listener? = null
    private var announced = 0

    private val inner = Proxy.newProxyInstance(
        AudioSink::class.java.classLoader,
        arrayOf(AudioSink::class.java)
    ) { _, method, args ->
        when (method.name) {
            "getCurrentPositionUs" -> sinkPositionUs
            "handleBuffer" -> {
                onBuffer()
                true
            }
            "setListener" -> {
                innerListener = args[0] as AudioSink.Listener
                null
            }
            else -> defaultFor(method.returnType)
        }
    } as AudioSink

    private val sink = HeardSilenceAudioSink(inner, cutter).apply {
        setListener(object : AudioSink.Listener {
            override fun onPositionDiscontinuity() = Unit
            override fun onUnderrun(bufferSize: Int, bufferSizeMs: Long, elapsedSinceLastFeedMs: Long) = Unit
            override fun onSkipSilenceEnabledChanged(skipSilenceEnabled: Boolean) = Unit
            override fun onSilenceSkipped() {
                announced++
            }
        })
        flush()
    }

    @Test
    fun `a cut is announced to the player when it is heard, not when the sink counts it`() {
        val output = play(bursts(2))
        val skippedUs = cutter.framesToUs(cutter.skippedFrames)

        innerListener?.onSilenceSkipped()
        sinkPositionUs = FIRST_CUT_US - 50_000 + skippedUs
        val beforeTheCut = sink.getCurrentPositionUs(false)
        assertEquals("the sink's own early report is swallowed, and nothing is heard yet", 0, announced)

        sinkPositionUs = FIRST_CUT_US + 50_000 + skippedUs
        val afterTheCut = sink.getCurrentPositionUs(false)
        sink.getCurrentPositionUs(false)

        assertTrue(output > 0)
        assertEquals(FIRST_CUT_US - 50_000, beforeTheCut)
        assertTrue("the clock jumps once the cut is heard ($afterTheCut)", afterTheCut > FIRST_CUT_US + PAUSE_US)
        assertEquals("and the player hears about it exactly once", 1, announced)
    }

    @Test
    fun `speed changes in quick succession keep every pause already cut`() {
        val positions = (1..3).map { flushes ->
            val fresh = HeardSilenceAudioSinkTest()
            fresh.positionAfterFlushes(flushes)
        }

        assertTrue("one, two and three flushes must agree: $positions", positions.distinct().size == 1)
    }

    @Test
    fun `once the sink has all the audio the last cut counts even if the playhead stops just short`() {
        play(bursts(1))
        val skippedUs = cutter.framesToUs(cutter.skippedFrames)
        sinkPositionUs = FIRST_CUT_US - 50_000 + skippedUs
        sink.getCurrentPositionUs(false)

        sink.playToEndOfStream()
        sinkPositionUs = FIRST_CUT_US - 1_000 + skippedUs
        val atTheEnd = sink.getCurrentPositionUs(true)

        assertEquals(FIRST_CUT_US - 1_000 + skippedUs, atTheEnd)
    }

    private fun positionAfterFlushes(flushes: Int): Long {
        play(bursts(5))
        val skippedUs = cutter.framesToUs(cutter.skippedFrames)
        val heardUs = 4_000_000L
        sinkPositionUs = heardUs + skippedUs
        sink.getCurrentPositionUs(false)
        onBuffer = {
            repeat(flushes) {
                cutter.queueEndOfStream()
                cutter.output
                cutter.flush(AudioProcessor.StreamMetadata.DEFAULT)
            }
            feed(tone(SPEECH_MS))
        }
        sink.handleBuffer(ByteBuffer.allocate(0), 5L * (SPEECH_US + PAUSE_US), 1)
        sinkPositionUs = heardUs + 100_000
        return sink.getCurrentPositionUs(false)
    }

    private fun play(input: ShortArray): Int {
        var output = 0
        onBuffer = { output = feed(input) }
        sink.handleBuffer(ByteBuffer.allocate(0), 0L, 1)
        onBuffer = {}
        return output
    }

    private fun feed(input: ShortArray): Int {
        var output = 0
        var at = 0
        while (at < input.size) {
            val end = minOf(input.size, at + CHUNK)
            val buffer = ByteBuffer.allocateDirect((end - at) * 2).order(ByteOrder.LITTLE_ENDIAN)
            buffer.asShortBuffer().put(input, at, end - at)
            cutter.queueInput(buffer)
            output += cutter.output.remaining() / 2
            at = end
        }
        return output
    }

    private fun bursts(count: Int): ShortArray {
        var all = ShortArray(0)
        repeat(count) { all += tone(SPEECH_MS) + ShortArray(RATE * PAUSE_MS / MILLIS) }
        return all
    }

    private fun tone(ms: Int) = ShortArray(
        RATE * ms / MILLIS
    ) { (PEAK * sin(2 * PI * VOICE_HZ * it / RATE)).toInt().toShort() }

    private operator fun ShortArray.plus(other: ShortArray) = ShortArray(size + other.size) {
        if (it < size) this[it] else other[it - size]
    }

    private fun defaultFor(type: Class<*>): Any? = when (type) {
        java.lang.Boolean.TYPE -> false
        java.lang.Long.TYPE -> 0L
        Integer.TYPE -> 0
        java.lang.Float.TYPE -> 0f
        java.lang.Double.TYPE -> 0.0
        else -> null
    }

    private companion object {
        const val RATE = 44_100
        const val MILLIS = 1_000
        const val CHUNK = 2_048
        const val SPEECH_MS = 500
        const val PAUSE_MS = 1_000
        const val SPEECH_US = 500_000L
        const val PAUSE_US = 1_000_000L
        const val FIRST_CUT_US = SPEECH_US + SilenceCutter.PAD_MS * 1_000L
        const val PEAK = 8_000.0
        const val VOICE_HZ = 180.0
    }
}
