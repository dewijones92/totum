package com.dewijones92.totum.playback

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.util.UnstableApi
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.sin

@UnstableApi
class BoostAndSkipSilenceTogetherTest {

    @Test
    fun `with the boost on a recording with hiss in its pauses still has them cut`() {
        val input = speechWithHissyPauses()
        val chain = SilenceCuttingAudioProcessorChain(
            SilenceCuttingAudioProcessor().apply { enabled = true },
            after = arrayOf(BoostingAudioProcessor().apply { level = VolumeBoost.AUTO }),
        )

        val output = run(chain.audioProcessors.toList(), input)

        val removedMs = (input.size - output.size) * MILLIS / RATE
        assertTrue(
            "only ${removedMs}ms of ${PAUSES * PAUSE_MS}ms of pauses was cut",
            removedMs >= (PAUSES - 1) * (PAUSE_MS - KEPT_MS),
        )
    }

    private fun run(processors: List<AudioProcessor>, input: ShortArray): ShortArray {
        processors.forEach {
            it.configure(AudioProcessor.AudioFormat(RATE, 1, C.ENCODING_PCM_16BIT))
            it.flush(AudioProcessor.StreamMetadata.DEFAULT)
        }
        val out = ArrayList<Short>(input.size)
        var at = 0
        while (at < input.size) {
            val end = minOf(input.size, at + CHUNK)
            var buffer = bufferOf(input, at, end)
            for (processor in processors.filter { it.isActive }) {
                processor.queueInput(buffer)
                buffer = processor.output
            }
            drain(buffer, out)
            at = end
        }
        var tail: ByteBuffer = ByteBuffer.allocateDirect(0)
        for (processor in processors.filter { it.isActive }) {
            if (tail.hasRemaining()) processor.queueInput(tail)
            processor.queueEndOfStream()
            tail = processor.output
        }
        drain(tail, out)
        return out.toShortArray()
    }

    private fun bufferOf(input: ShortArray, from: Int, to: Int): ByteBuffer {
        val buffer = ByteBuffer.allocateDirect((to - from) * 2).order(ByteOrder.LITTLE_ENDIAN)
        buffer.asShortBuffer().put(input, from, to - from)
        return buffer
    }

    private fun drain(buffer: ByteBuffer, out: MutableList<Short>) {
        val shorts = buffer.order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
        while (shorts.hasRemaining()) out += shorts.get()
    }

    private fun speechWithHissyPauses(): ShortArray {
        val speech = RATE * SPEECH_MS / MILLIS
        val pause = RATE * PAUSE_MS / MILLIS
        return ShortArray(PAUSES * (speech + pause)) { i ->
            val t = i % (speech + pause)
            if (t < speech) {
                val envelope = 0.5 + 0.5 * sin(2 * PI * SYLLABLE_HZ * t / RATE)
                (SPEECH_PEAK * envelope * sin(2 * PI * VOICE_HZ * t / RATE)).toInt().toShort()
            } else {
                (if (t % 2 == 0) HISS else -HISS).toShort()
            }
        }
    }

    private companion object {
        const val RATE = 44_100
        const val MILLIS = 1_000
        const val CHUNK = 2_048
        const val PAUSES = 6
        const val SPEECH_MS = 1_000
        const val PAUSE_MS = 1_000
        const val KEPT_MS = 2 * SilenceCutter.PAD_MS
        const val SPEECH_PEAK = 3_000.0
        const val HISS = 300
        const val VOICE_HZ = 180.0
        const val SYLLABLE_HZ = 4.0
    }
}
