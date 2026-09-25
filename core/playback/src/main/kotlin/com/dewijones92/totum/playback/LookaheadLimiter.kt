package com.dewijones92.totum.playback

internal class LookaheadLimiter(sampleRate: Int, private val channels: Int) {

    private val lookahead = (sampleRate.toLong() * LOOKAHEAD_MS / MILLIS_PER_SECOND).toInt().coerceAtLeast(1)
    private val window = lookahead + 1
    private val release = LoudnessBoost.coefficientFor(RELEASE_MS, sampleRate)
    private val delay = ShortArray(window * channels)
    private val minIndex = LongArray(window + 1)
    private val minValue = FloatArray(window + 1)
    private val box = FloatArray(window)
    private var delayed = 0
    private var framesIn = 0L
    private var minHead = 0
    private var minSize = 0
    private var boxSum = 0.0
    private var boxAt = 0
    private var boxPrimed = false
    private var released = 1f

    val heldFrames: Int get() = window

    var deepest: Float = 1f
        private set

    fun push(input: ShortArray, at: Int, allowed: Float, asked: Float, emit: (ShortArray, Int, Float) -> Unit) {
        pushMin(framesIn, allowed)
        input.copyInto(delay, slotOf(framesIn), at, at + channels)
        framesIn++
        if (delayed < lookahead) {
            delayed++
        } else {
            val oldest = framesIn - window
            emit(oldest, windowMin(oldest, asked), asked, emit)
        }
    }

    fun releaseAll(asked: Float, emit: (ShortArray, Int, Float) -> Unit) {
        for (frame in framesIn - delayed until framesIn) emit(frame, windowMin(frame, asked), asked, emit)
        reset()
    }

    fun reset() {
        delayed = 0
        minSize = 0
        minHead = 0
        boxPrimed = false
    }

    fun forgetDeepest() {
        deepest = 1f
    }

    private fun emit(frame: Long, floor: Float, asked: Float, emit: (ShortArray, Int, Float) -> Unit) {
        if (!boxPrimed) {
            box.fill(floor)
            boxSum = floor.toDouble() * window
            released = floor
            boxPrimed = true
        }
        released = if (floor < released) floor else released + (floor - released) * release
        boxSum += released - box[boxAt]
        box[boxAt] = released
        boxAt = (boxAt + 1) % window
        val gain = (boxSum / window).toFloat()
        if (asked > EPSILON) deepest = minOf(deepest, gain / asked)
        emit(delay, slotOf(frame), gain)
    }

    private fun slotOf(frame: Long): Int = (frame % window).toInt() * channels

    private fun pushMin(index: Long, value: Float) {
        while (minSize > 0 && minValue[(minHead + minSize - 1) % minValue.size] >= value) minSize--
        val tail = (minHead + minSize) % minValue.size
        minIndex[tail] = index
        minValue[tail] = value
        minSize++
    }

    private fun windowMin(from: Long, fallback: Float): Float {
        while (minSize > 0 && minIndex[minHead] < from) {
            minHead = (minHead + 1) % minValue.size
            minSize--
        }
        return if (minSize > 0) minValue[minHead] else fallback
    }

    internal companion object {
        const val LOOKAHEAD_MS = 5L
        private const val RELEASE_MS = 250f
        private const val MILLIS_PER_SECOND = 1_000L
        private const val EPSILON = 1e-6f
    }
}
