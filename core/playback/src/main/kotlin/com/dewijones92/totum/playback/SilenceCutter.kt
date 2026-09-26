package com.dewijones92.totum.playback

import kotlin.math.abs

internal class SilenceCutter(sampleRate: Int, private val channels: Int) {

    private val minSilentFrames = framesIn(MIN_SILENCE_MS, sampleRate)
    private val padFrames = framesIn(PAD_MS, sampleRate).coerceAtMost(minSilentFrames / 2)

    private val pending = ShortArray(minSilentFrames * channels)
    private var pendingFrames = 0

    private val tail = ShortArray(padFrames * channels)
    private var tailStart = 0
    private var tailFrames = 0

    private var cutting = false
    private var removedThisGap = 0L
    private var justEnded = false

    private var out = ShortArray(0)

    val heard = HeardCuts()

    val levels = CutLevel(framesIn(BLOCK_MS, sampleRate).coerceAtLeast(1))

    val cutLevel: Int get() = levels.level

    var outputSamples: Int = 0
        private set

    var outputFrames: Long = 0L
        private set

    var skippedFrames: Long = 0L
        private set

    var gapsCut: Long = 0L
        private set

    var lastGapRemovedFrames: Long = 0L
        private set

    fun process(input: ShortArray, frames: Int): ShortArray {
        ensureRoom(frames + minSilentFrames + padFrames)
        for (frame in 0 until frames) {
            val at = frame * channels
            val peak = framePeak(input, at, channels)
            val silent = peak <= levels.level
            levels.track(peak)
            when {
                cutting && silent -> pushTail(input, at)
                cutting -> {
                    endCut()
                    emit(input, at)
                }
                silent -> hold(input, at)
                pendingFrames > 0 -> {
                    releasePending()
                    emit(input, at)
                }
                else -> emit(input, at)
            }
        }
        return out
    }

    fun endOfStream(): ShortArray {
        ensureRoom(minSilentFrames + padFrames)
        if (cutting) endCut()
        if (pendingFrames > 0) releasePending()
        return out
    }

    fun gapJustEnded(): Boolean = justEnded.also { justEnded = false }

    private fun hold(input: ShortArray, at: Int) {
        input.copyInto(pending, pendingFrames * channels, at, at + channels)
        pendingFrames++
        if (pendingFrames < minSilentFrames) return
        for (frame in 0 until padFrames) emit(pending, frame * channels, 1f - (frame + 1).toFloat() / padFrames)
        tailStart = 0
        tailFrames = padFrames
        pending.copyInto(tail, 0, (minSilentFrames - padFrames) * channels, minSilentFrames * channels)
        val removed = (minSilentFrames - 2 * padFrames).toLong()
        skippedFrames += removed
        removedThisGap = removed
        pendingFrames = 0
        cutting = true
        heard.open(outputFrames, skippedFrames)
    }

    private fun pushTail(input: ShortArray, at: Int) {
        if (padFrames > 0) {
            input.copyInto(tail, tailStart * channels, at, at + channels)
            tailStart = (tailStart + 1) % padFrames
        }
        skippedFrames++
        removedThisGap++
        heard.grow(skippedFrames)
    }

    private fun endCut() {
        for (i in 0 until tailFrames) emit(tail, ((tailStart + i) % padFrames) * channels, i.toFloat() / padFrames)
        tailFrames = 0
        tailStart = 0
        cutting = false
        gapsCut++
        lastGapRemovedFrames = removedThisGap
        removedThisGap = 0
        levels.cutHappened()
        heard.close()
        justEnded = true
    }

    private fun releasePending() {
        for (frame in 0 until pendingFrames) emit(pending, frame * channels)
        pendingFrames = 0
    }

    private fun emit(source: ShortArray, at: Int, gain: Float = 1f) {
        if (gain == 1f) {
            source.copyInto(out, outputSamples, at, at + channels)
            outputSamples += channels
        } else {
            for (channel in 0 until channels) out[outputSamples++] = (source[at + channel] * gain).toInt().toShort()
        }
        outputFrames++
    }

    private fun ensureRoom(frames: Int) {
        val needed = frames * channels
        if (out.size < needed) out = ShortArray(needed)
        outputSamples = 0
    }

    internal companion object {
        const val THRESHOLD = 1024
        const val FLOOR = 32
        const val MIN_SILENCE_MS = 150
        const val PAD_MS = 20
        private const val BLOCK_MS = 20
        private const val MILLIS_PER_SECOND = 1_000

        fun framesIn(milliseconds: Int, sampleRate: Int): Int =
            (milliseconds.toLong() * sampleRate / MILLIS_PER_SECOND).toInt()
    }
}

internal class HeardCuts {

    private val cuts = ArrayDeque<Cut>()
    private var open: Cut? = null
    private var lastHeard: Cut? = null

    fun open(atOutputFrame: Long, skippedSoFar: Long) {
        open = Cut(atOutputFrame, skippedSoFar).also(cuts::addLast)
    }

    fun grow(skippedSoFar: Long) {
        open?.skippedAfter = skippedSoFar
    }

    fun close() {
        open = null
    }

    fun skippedBy(heardOutputFrames: Long): Long {
        while (cuts.isNotEmpty() && cuts.first().outputFrame <= heardOutputFrames) lastHeard = cuts.removeFirst()
        return lastHeard?.skippedAfter ?: 0L
    }

    private class Cut(val outputFrame: Long, var skippedAfter: Long)
}

internal class CutLevel(private val blockFrames: Int) {

    private val blocks = IntArray(FLOOR_BLOCKS) { UNKNOWN }
    private var blockAt = 0
    private val sounds = IntArray(SOUND_BLOCKS) { UNKNOWN }
    private var soundAt = 0
    private var blockPeak = 0
    private var blockFill = 0

    var level: Int = SilenceCutter.FLOOR
        private set

    var noiseFloor: Int = UNKNOWN
        private set

    var speechPeak: Int = UNKNOWN
        private set

    var framesSinceCut: Long = 0L
        private set

    fun track(framePeak: Int) {
        framesSinceCut++
        blockPeak = maxOf(blockPeak, framePeak)
        if (++blockFill < blockFrames) return
        blocks[blockAt] = blockPeak
        blockAt = (blockAt + 1) % blocks.size
        if (blockPeak > level) {
            sounds[soundAt] = blockPeak
            soundAt = (soundAt + 1) % sounds.size
        }
        blockPeak = 0
        blockFill = 0
        noiseFloor = blocks.filter { it != UNKNOWN }.minOrNull() ?: UNKNOWN
        speechPeak = sounds.maxOrNull()?.takeIf { it != UNKNOWN } ?: UNKNOWN
        val aboveFloor = if (noiseFloor == UNKNOWN) Int.MAX_VALUE else noiseFloor * ABOVE_FLOOR
        val underSpeech = if (speechPeak == UNKNOWN) Int.MAX_VALUE else speechPeak / UNDER_SPEECH
        level = minOf(aboveFloor, underSpeech).coerceIn(SilenceCutter.FLOOR, SilenceCutter.THRESHOLD)
    }

    fun cutHappened() {
        framesSinceCut = 0
    }

    internal companion object {
        const val UNKNOWN = -1
        const val ABOVE_FLOOR = 8
        const val UNDER_SPEECH = 4
        private const val FLOOR_BLOCKS = 250
        private const val SOUND_BLOCKS = 50
    }
}

private fun framePeak(input: ShortArray, at: Int, channels: Int): Int {
    var peak = 0
    for (channel in 0 until channels) peak = maxOf(peak, abs(input[at + channel].toInt()))
    return peak
}
