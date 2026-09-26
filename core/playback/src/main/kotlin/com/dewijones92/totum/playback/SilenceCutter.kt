package com.dewijones92.totum.playback

import kotlin.math.abs

internal class SilenceCutter(
    sampleRate: Int,
    private val channels: Int,
    val levels: CutLevel = CutLevel(framesIn(BLOCK_MS, sampleRate).coerceAtLeast(1)),
    val lookaheadFrames: Int = framesIn(LOOKAHEAD_MS, sampleRate),
    speech: SpeechTrack? = null,
) {

    var speech: SpeechTrack? = speech
        set(value) {
            if (field !== value) field?.retire()
            field = value
            speechFrom = framesArrived
        }

    private var speechFrom = 0L

    private var framesArrived = 0L

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

    private val ahead = LookAhead(lookaheadFrames, channels)

    private var decidedFrames = 0L

    val smart = SmartTally()

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
        ensureRoom(frames + minSilentFrames + padFrames + lookaheadFrames)
        for (frame in 0 until frames) {
            val at = frame * channels
            val peak = framePeak(input, at, channels)
            val before = levels.level
            levels.track(peak)
            speech?.push(frameMean(input, at, channels))
            framesArrived++
            if (lookaheadFrames == 0) {
                decide(input, at, peak <= minOf(before, levels.level))
                continue
            }
            if (ahead.full) decideOldest()
            ahead.push(input, at, peak, before)
        }
        return out
    }

    fun endOfStream(): ShortArray {
        ensureRoom(minSilentFrames + padFrames + lookaheadFrames)
        speech?.finish()
        while (ahead.held > 0) decideOldest()
        if (cutting) endCut()
        if (pendingFrames > 0) releasePending()
        return out
    }

    fun gapJustEnded(): Boolean = justEnded.also { justEnded = false }

    private val decideOldest: () -> Unit = {
        val level = minOf(ahead.oldestLevel, levels.level)
        val quiet = ahead.oldestPeak <= level
        val heard = decidedFrames - speechFrom
        val track = this.speech
        val verdict = if (heard >= 0) track?.isSpeech(heard) else null
        val notSpeech = verdict == false
        val extra = !quiet && notSpeech && ahead.oldestPeak <= levels.nonSpeechLevel
        if (track != null) smart.count(verdict, extra)
        val silent = quiet || extra
        decide(ahead.samples, ahead.oldestAt, silent)
        ahead.drop()
        decidedFrames++
    }

    private fun decide(source: ShortArray, at: Int, silent: Boolean) {
        when {
            cutting && silent -> pushTail(source, at)
            cutting -> {
                endCut()
                emit(source, at)
            }
            silent -> hold(source, at)
            pendingFrames > 0 -> {
                releasePending()
                emit(source, at)
            }
            else -> emit(source, at)
        }
    }

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
        levels.cutHappened()
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
        const val LOOKAHEAD_MS = 500
        const val PAD_MS = 20
        const val BLOCK_MS = 20
        private const val MILLIS_PER_SECOND = 1_000

        fun framesIn(milliseconds: Int, sampleRate: Int): Int =
            (milliseconds.toLong() * sampleRate / MILLIS_PER_SECOND).toInt()
    }
}

internal class SmartTally {
    var speechFrames = 0L
        private set
    var notSpeechFrames = 0L
        private set
    var unknownFrames = 0L
        private set
    var cutBeyondStandard = 0L
        private set

    fun count(verdict: Boolean?, extra: Boolean) {
        when (verdict) {
            true -> speechFrames++
            false -> notSpeechFrames++
            null -> unknownFrames++
        }
        if (extra) cutBeyondStandard++
    }

    override fun toString(): String =
        "smart judged $speechFrames frames speech, $notSpeechFrames not, $unknownFrames not yet known; " +
            "$cutBeyondStandard cut beyond standard"
}

internal class LookAhead(private val capacity: Int, private val channels: Int) {

    val samples = ShortArray(capacity.coerceAtLeast(1) * channels)
    private val peaks = IntArray(capacity.coerceAtLeast(1))
    private val levels = IntArray(capacity.coerceAtLeast(1))
    private var start = 0

    var held = 0
        private set

    val full: Boolean get() = held == capacity

    val oldestAt: Int get() = start * channels

    val oldestPeak: Int get() = peaks[start]

    val oldestLevel: Int get() = levels[start]

    fun push(input: ShortArray, at: Int, peak: Int, levelBefore: Int) {
        val slot = (start + held) % capacity
        input.copyInto(samples, slot * channels, at, at + channels)
        peaks[slot] = peak
        levels[slot] = levelBefore
        held++
    }

    fun drop() {
        start = (start + 1) % capacity
        held--
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

    private val blocks = IntArray(FLOOR_BLOCKS)
    private var blockCount = 0
    private var blockAt = 0
    private val sounds = IntArray(SOUND_BLOCKS)
    private var soundCount = 0
    private var soundAt = 0
    private val sorted = IntArray(SOUND_BLOCKS)
    private var blockPeak = 0
    private var blockFill = 0

    var level: Int = SilenceCutter.FLOOR
        private set

    var noiseFloor: Int = UNKNOWN
        private set

    var speechPeak: Int = UNKNOWN
        private set

    val nonSpeechLevel: Int
        get() = if (speechPeak == UNKNOWN) SilenceCutter.FLOOR else speechPeak / UNDER_SPEECH_WHEN_NOT_SPEECH

    var framesSinceCut: Long = 0L
        private set

    fun track(framePeak: Int) {
        framesSinceCut++
        blockPeak = maxOf(blockPeak, framePeak)
        if (++blockFill < blockFrames) return
        if (blockPeak >= DIGITAL_SILENCE) {
            blocks[blockAt] = blockPeak
            blockAt = (blockAt + 1) % blocks.size
            blockCount = minOf(blockCount + 1, blocks.size)
            noiseFloor = floorOf()
        }
        if (noiseFloor != UNKNOWN && blockPeak > maxOf(noiseFloor * SOUND_OVER_FLOOR, SilenceCutter.FLOOR)) {
            sounds[soundAt] = blockPeak
            soundAt = (soundAt + 1) % sounds.size
            soundCount = minOf(soundCount + 1, sounds.size)
            speechPeak = medianSound()
        }
        blockPeak = 0
        blockFill = 0
        level = if (speechPeak == UNKNOWN) {
            SilenceCutter.FLOOR
        } else {
            (speechPeak / UNDER_SPEECH)
                .coerceIn(SilenceCutter.FLOOR, SilenceCutter.THRESHOLD)
        }
    }

    fun cutHappened() {
        framesSinceCut = 0
    }

    private fun floorOf(): Int {
        var lowest = Int.MAX_VALUE
        for (i in 0 until blockCount) lowest = minOf(lowest, blocks[i])
        return lowest
    }

    private fun medianSound(): Int {
        sounds.copyInto(sorted, 0, 0, soundCount)
        sorted.sort(0, soundCount)
        return sorted[soundCount * SPEECH_PERCENTILE / PERCENT]
    }

    internal companion object {
        const val UNKNOWN = -1
        const val UNDER_SPEECH = 8
        const val UNDER_SPEECH_WHEN_NOT_SPEECH = 4
        private const val SOUND_OVER_FLOOR = 2
        private const val DIGITAL_SILENCE = 16
        private const val FLOOR_BLOCKS = 250
        private const val SOUND_BLOCKS = 20
        private const val SPEECH_PERCENTILE = 80
        private const val PERCENT = 100
    }
}

private fun frameMean(input: ShortArray, at: Int, channels: Int): Float {
    var sum = 0
    for (channel in 0 until channels) sum += input[at + channel]
    return sum / (channels * FULL_SCALE)
}

private const val FULL_SCALE = 32_768f

private fun framePeak(input: ShortArray, at: Int, channels: Int): Int {
    var peak = 0
    for (channel in 0 until channels) peak = maxOf(peak, abs(input[at + channel].toInt()))
    return peak
}
