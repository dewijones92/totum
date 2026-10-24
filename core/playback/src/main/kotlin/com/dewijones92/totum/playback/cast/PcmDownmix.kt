package com.dewijones92.totum.playback.cast

import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.roundToInt

internal object PcmDownmix {

    fun toChannels(pcm: ByteBuffer, from: Int, to: Int): ByteBuffer {
        if (from == to) return pcm
        val left = leftWeights(from)
        val right = rightWeights(from)
        val scale = 1f / maxOf(left.sum(), right.sum())
        val input = pcm.duplicate().order(ByteOrder.nativeOrder()).asShortBuffer()
        val frames = input.remaining() / from
        val out = ByteBuffer.allocateDirect(frames * to * Short.SIZE_BYTES).order(ByteOrder.nativeOrder())
        val frame = FloatArray(from)
        repeat(frames) {
            for (c in 0 until from) frame[c] = input.get().toFloat()
            val l = mix(frame, left) * scale
            val r = mix(frame, right) * scale
            if (to == 1) out.putShort(clamp((l + r) / 2)) else out.putShort(clamp(l)).putShort(clamp(r))
        }
        return out.apply { flip() }
    }

    private fun mix(frame: FloatArray, weights: FloatArray): Float {
        var sum = 0f
        for (c in frame.indices) sum += frame[c] * weights[c]
        return sum
    }

    private fun clamp(value: Float): Short =
        value.roundToInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()

    @Suppress("MagicNumber")
    private fun leftWeights(channels: Int): FloatArray = when (channels) {
        1 -> floatArrayOf(1f)
        2 -> floatArrayOf(1f, 0f)
        3 -> floatArrayOf(1f, 0f, M3)
        4 -> floatArrayOf(1f, 0f, M3, 0f)
        5 -> floatArrayOf(1f, 0f, M3, M3, 0f)
        6 -> floatArrayOf(1f, 0f, M3, 0f, M3, 0f)
        8 -> floatArrayOf(1f, 0f, M3, 0f, M3, 0f, M3, 0f)
        else -> FloatArray(channels) { if (it % 2 == 0) 1f else 0f }
    }

    @Suppress("MagicNumber")
    private fun rightWeights(channels: Int): FloatArray = when (channels) {
        1 -> floatArrayOf(1f)
        2 -> floatArrayOf(0f, 1f)
        3 -> floatArrayOf(0f, 1f, M3)
        4 -> floatArrayOf(0f, 1f, 0f, M3)
        5 -> floatArrayOf(0f, 1f, M3, 0f, M3)
        6 -> floatArrayOf(0f, 1f, M3, 0f, 0f, M3)
        8 -> floatArrayOf(0f, 1f, M3, 0f, 0f, M3, 0f, M3)
        else -> FloatArray(channels) { if (it % 2 == 1) 1f else 0f }
    }

    private const val M3 = 0.7071f
}
