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
    private var videoPlaying = false
    private var lateFrames = 0L
    private var reportedOwedUs = 0L
    private val owedReports = mutableListOf<Long>()

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
            "flush" -> {
                cutter.flush(AudioProcessor.StreamMetadata.DEFAULT)
                null
            }
            "setListener" -> {
                innerListener = args[0] as AudioSink.Listener
                null
            }
            else -> defaultFor(method.returnType)
        }
    } as AudioSink

    private val sink = HeardSilenceAudioSink(inner, cutter, { videoPlaying }, { lateFrames }) {
        reportedOwedUs = it
        owedReports += it
    }.apply {
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

    @Test
    fun `a seek or a new item learns the cut level again but a speed change keeps it`() {
        play(
            ShortArray(RATE * 6) { i ->
                val voice = if (i % RATE < RATE / 2) PEAK * sin(2 * PI * VOICE_HZ * i / RATE) else 0.0
                (voice + if (i % 2 == 0) HISS else -HISS).toInt().toShort()
            },
        )
        val learned = cutter.cutLevel

        cutter.queueEndOfStream()
        cutter.output
        cutter.flush(AudioProcessor.StreamMetadata.DEFAULT)
        val afterASpeedChange = cutter.cutLevel
        sink.flush()
        cutter.flush(AudioProcessor.StreamMetadata.DEFAULT)
        val afterASeek = cutter.cutLevel

        assertTrue("something was learned ($learned)", learned > SilenceCutter.FLOOR)
        assertEquals("a speed change keeps what was learned", learned, afterASpeedChange)
        assertEquals("a seek or a new item starts again", SilenceCutter.FLOOR, afterASeek)
    }

    @Test
    fun `on a video a long pause is released as jumps no longer than the cap`() {
        videoPlaying = true
        val jumps = jumpsWhilePlayingPast(sevenSecondPause)

        assertTrue("the pause was cut and heard: $jumps", jumps.sum() > MOST_OF_THE_PAUSE_US)
        assertTrue("no jump over the cap: $jumps", jumps.max() <= VIDEO_JUMP_CAP_US)
        assertTrue("it took several jumps: $jumps", jumps.count { it > 0 } > 10)
    }

    @Test
    fun `with no video the same pause is still released as one jump`() {
        videoPlaying = false
        val jumps = jumpsWhilePlayingPast(sevenSecondPause)

        assertEquals("one jump: $jumps", 1, jumps.count { it > 0 })
        assertTrue("and it is the whole pause: $jumps", jumps.max() > MOST_OF_THE_PAUSE_US)
    }

    @Test
    fun `a video pause released in several jumps is announced to the player each time`() {
        videoPlaying = true
        val jumps = jumpsWhilePlayingPast(sevenSecondPause)

        assertEquals(jumps.count { it > 0 }, announced)
    }

    @Test
    fun `on a video the cut still to move is reported while it is paid out and is zero once paid`() {
        videoPlaying = true
        jumpsWhilePlayingPast(sevenSecondPause)

        assertTrue(
            "the renderer was told a cut was still to move: $owedReports",
            owedReports.max() > MOST_OF_THE_PAUSE_US,
        )
        assertEquals("and that it was all moved in the end", 0L, reportedOwedUs)
    }

    @Test
    fun `with no video nothing is ever reported as still to move`() {
        videoPlaying = false
        jumpsWhilePlayingPast(sevenSecondPause)

        assertEquals("nothing owed: $owedReports", 0L, owedReports.max())
    }

    @Test
    fun `a seek tells the renderer that nothing is still to move`() {
        videoPlaying = true
        play(sevenSecondPause)
        val skippedUs = cutter.framesToUs(cutter.skippedFrames)
        sinkPositionUs = FIRST_CUT_US - 50_000 + skippedUs
        sink.getCurrentPositionUs(false)
        repeat(PAST_THE_CUT_STEPS) {
            sinkPositionUs += STEP_US
            sink.getCurrentPositionUs(false)
        }
        assertTrue("a cut is still owed before the seek: $reportedOwedUs", reportedOwedUs > 0)

        sink.flush()

        assertEquals(0L, reportedOwedUs)
    }

    private fun jumpsWhilePlayingPast(input: ShortArray): List<Long> {
        play(input)
        val skippedUs = cutter.framesToUs(cutter.skippedFrames)
        sinkPositionUs = FIRST_CUT_US - 50_000 + skippedUs
        var last = sink.getCurrentPositionUs(false)
        val jumps = mutableListOf<Long>()
        repeat(STEPS) {
            sinkPositionUs += STEP_US
            val position = sink.getCurrentPositionUs(false)
            jumps += position - last - STEP_US
            last = position
        }
        return jumps
    }

    private val sevenSecondPause: ShortArray
        get() = tone(SPEECH_MS) + ShortArray(RATE * 7) + tone(SPEECH_MS)

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
        const val STEP_US = 10_000L
        const val STEPS = 100
        const val PAST_THE_CUT_STEPS = 10
        const val MOST_OF_THE_PAUSE_US = 6_500_000L
        const val VIDEO_JUMP_CAP_US = 400_000L
        const val PEAK = 8_000.0
        const val HISS = 100
        const val VOICE_HZ = 180.0
    }
}
