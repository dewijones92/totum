package com.dewijones92.totum.playback

import com.dewijones92.totum.common.Diag
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.ln

/**
 * Brings quiet audio up to a comfortable level, and cannot clip while doing it.
 *
 * Dewi, 2026-08-08, on the first version: *"the volume booster setting thing in the app causes
 * distortion … i dont want distortion, i want it to allow me to hear things like quiet podcasts well
 * … compression?? or sumin????"*, at MEDIUM and above, on earphones.
 *
 * ## Why the first version distorted, and why a hard cap was never the answer
 *
 * He asked the right question — *"wont a hard DB cap prevent distortion?"* — and the answer is the
 * whole design. **A ceiling is not a wall the sound bounces off, it is a knife.** Everything above it
 * is sliced flat, and those flat tops are frequencies that were never in the recording. Hard-capping
 * IS distorting; the two are one event described twice.
 *
 * What avoids it is turning the gain down *before* the loud part is multiplied, so nothing ever
 * reaches the ceiling and the waveform keeps its shape. The first version did turn the gain down —
 * but it moved down at the same rate it moved up, over 30 ms. So a laugh after a quiet passage was
 * multiplied by the full boost for tens of milliseconds before the gain caught up, and all of it was
 * sliced: measured at **5428 samples (123 ms) at MAX and 2844 (64 ms) at MEDIUM**, per transient.
 *
 * The fix is a limiter that looks [LookaheadLimiter.LOOKAHEAD_MS] ms ahead: the gain ramps down over
 * that window before a peak arrives and recovers slowly after it. Each sample's gain is an average of
 * values that are all at or below that sample's own `CEILING / |sample|`, so the output is bounded by
 * construction and [clippedSamples] is expected to stay at zero forever. It is reported anyway,
 * because a number that should always be zero is the cheapest possible alarm.
 *
 * ## Auto, rather than a number you pick
 *
 * Dewi, 2026-08-08, choosing between fixed levels and automatic: *"Auto — make everything the same
 * loudness"*. So there is no makeup gain to set. It measures how loud the item actually is and
 * applies exactly the difference needed to reach [TARGET_LEVEL] — a quiet podcast comes up a long
 * way, a normal one barely moves, and nothing is over-driven because nothing is asked for more than
 * it needs. Picking a number by ear per item was itself the cause of the squashing.
 *
 * Two deliberate bounds:
 *
 * - **It never turns anything down** ([MIN_GAIN] is 1). Quieter-than-expected is a surprise nobody
 *   asked for, and the ask was to hear quiet things.
 * - **It never applies more than [MAX_GAIN]**, +30 dB.
 *
 * Pure arithmetic on a `ShortArray`, deliberately: no Android, no platform effect, so it behaves the
 * same on every device and — the part that matters here — the maths can be proven on the JVM.
 */
internal class LoudnessBoost(private val sampleRate: Int, private val channels: Int = 1) {

    /** Whether to do anything at all. Changing it mid-stream is picked up on the next sample. */
    var level: VolumeBoost = VolumeBoost.OFF

    /** A slow average of the audio's level, ignoring silence — what "how loud is this item" means. */
    private var loudness = 0f

    /** Recent peak: rises instantly, decays slowly. Sets the level below which audio is a pause. */
    private var recentPeak = 0f

    /** The automatic gain: drifts towards what is wanted. The limiter keeps it off the ceiling. */
    private var applied = 1f

    private val limiter = LookaheadLimiter(sampleRate, channels)
    private var out = ShortArray(0)
    private val write: (ShortArray, Int, Float) -> Unit = { source, at, gain ->
        for (channel in 0 until channels) out[outputSamples++] = clamp(source[at + channel] / FULL_SCALE * gain)
    }

    var outputSamples: Int = 0
        private set

    val deepestLimit: Float get() = limiter.deepest

    fun forgetDeepestLimit() = limiter.forgetDeepest()

    /** Samples of real audio seen so far, so the estimate can settle quickly at the start. */
    private var warmupSamples = 0L

    private var heardDynamics = false
    private var quietestBlock = Float.MAX_VALUE
    private var blockPeak = 0f
    private var blockFill = 0
    private var audibleFrames = 0L
    private val blockFrames = framesOf(DYNAMICS_BLOCK_MS).toInt().coerceAtLeast(1)
    private val dynamicsTimeout = framesOf(DYNAMICS_TIMEOUT_MS)

    /**
     * Samples that hit the rail. Expected to be zero for the life of the app: the look-ahead limiter
     * makes exceeding the ceiling arithmetically impossible, so anything here is a broken assumption
     * rather than loud audio, and is worth a line in a report from Dewi's phone.
     */
    var clippedSamples: Long = 0L
        private set

    /** The gain in effect, for diagnostics. */
    val currentGain: Float get() = applied

    /** The measured level of the item, for diagnostics. */
    val measuredLoudness: Float get() = loudness

    /**
     * The level below which audio counts as a pause rather than as content.
     *
     * **Relative to the recent peak, not an absolute number**, and that is not a refinement — a fixed
     * floor got this exactly backwards. Set high enough to reject tape hiss it also rejected genuinely
     * quiet speech, which is the audio this whole feature exists to rescue: a recording peaking at
     * -46 dBFS sat entirely underneath a -45 dBFS floor and was measured as *nothing*, so it received
     * no boost whatsoever. Set low enough to admit that speech, it admitted the hiss too. There is no
     * absolute number that separates them, because the difference between quiet speech and loud hiss
     * is not a level — it is a level *relative to the rest of the recording*.
     *
     * So the gate follows the content, 20 dB below its recent peak. The absolute part only rejects
     * digital silence and dither, where there is genuinely nothing to measure.
     */
    private fun gateFor(peak: Float): Float = maxOf(SILENCE_FLOOR, peak * RELATIVE_GATE)

    private val gainRise = coefficientFor(GAIN_RISE_MS, sampleRate)
    private val peakDecay = 1f - coefficientFor(PEAK_DECAY_MS, sampleRate)
    private val loudnessSettle = coefficientFor(LOUDNESS_SETTLE_MS, sampleRate)
    private val loudnessWarmup = coefficientFor(LOUDNESS_WARMUP_MS, sampleRate)
    private val warmupLimit = sampleRate.toLong() * WARMUP_SECONDS

    /**
     * Boosts [count] samples of [input], returning [outputSamples] of them from the returned array.
     */
    fun process(input: ShortArray, count: Int): ShortArray {
        val frames = count / channels
        ensureRoom(frames + limiter.heldFrames)
        outputSamples = 0
        // OFF is bit-exact passthrough, not a gain of one: a multiply-and-round on every sample of
        // every stream forever, to change nothing, is worth skipping.
        if (level == VolumeBoost.OFF) {
            limiter.releaseAll(applied, write)
            input.copyInto(out, outputSamples, 0, frames * channels)
            outputSamples += frames * channels
            return out
        }
        for (frame in 0 until frames) accept(input, frame * channels)
        return out
    }

    fun drain(): ShortArray {
        ensureRoom(limiter.heldFrames)
        outputSamples = 0
        limiter.releaseAll(applied, write)
        return out
    }

    fun flushDelay() = limiter.reset()

    private fun accept(input: ShortArray, at: Int) {
        var peak = 0
        for (channel in 0 until channels) peak = maxOf(peak, abs(input[at + channel].toInt()))
        val magnitude = peak / FULL_SCALE

        // Track the recent peak, up at once and down slowly, so a pause is measured against the
        // speech either side of it rather than against a fixed number.
        recentPeak = if (magnitude > recentPeak) magnitude else recentPeak * peakDecay

        noteDynamics(magnitude)

        if (heardDynamics && magnitude > gateFor(recentPeak)) {
            // Only real audio counts. Silence between sentences would otherwise drag the estimate
            // down and wind the gain up, so every pause would end in a blast.
            // The first couple of seconds settle fast, or an episode would start unboosted and
            // audibly swell — the estimate has to be roughly right by the time speech begins.
            val rate = if (warmupSamples < warmupLimit) loudnessWarmup else loudnessSettle
            loudness += (magnitude - loudness) * rate
            warmupSamples++
        }

        // Exactly the gain that brings this item to a comfortable level, and no more.
        val wanted = if (loudness > EPSILON) {
            (TARGET_LEVEL / loudness).coerceIn(MIN_GAIN, MAX_GAIN)
        } else {
            MIN_GAIN
        }

        // Drift towards it — slowly, so a change of level is never heard as the gain moving...
        applied += (wanted - applied) * gainRise
        val allowed = if (magnitude > EPSILON) minOf(applied, CEILING / magnitude) else applied

        limiter.push(input, at, allowed, applied, write)
    }

    private fun noteDynamics(magnitude: Float) {
        if (heardDynamics) return
        blockPeak = maxOf(blockPeak, magnitude)
        if (++blockFill < blockFrames) return
        if (blockPeak > SILENCE_FLOOR) {
            quietestBlock = minOf(quietestBlock, blockPeak)
            audibleFrames += blockFill
        }
        val contrast = blockPeak > quietestBlock * DYNAMICS_OVER_QUIETEST
        val waitedLongEnough = audibleFrames >= dynamicsTimeout
        if (contrast || waitedLongEnough) {
            heardDynamics = true
            val why = if (contrast) {
                "a sound ${DYNAMICS_OVER_QUIETEST.toInt()}x over the quietest so far"
            } else {
                "no contrast, steady audio"
            }
            Diag.log(
                "boost",
                "measuring from ${audibleFrames * MILLIS_PER_SECOND.toLong() / sampleRate.coerceAtLeast(1)}ms in: $why"
            )
        }
        blockPeak = 0f
        blockFill = 0
    }

    private fun framesOf(milliseconds: Long): Long = sampleRate.toLong() * milliseconds / MILLIS_PER_SECOND.toLong()

    private fun ensureRoom(frames: Int) {
        val needed = frames * channels
        if (out.size < needed) out = ShortArray(needed)
    }

    /**
     * Never expected to do anything — see [clippedSamples]. Kept as the belt to the clamp's braces,
     * because the alternative to clamping a stray value is wrapping it, which inverts the waveform
     * and is heard as a crack rather than as loudness.
     */
    private fun clamp(value: Float): Short {
        val scaled = value * FULL_SCALE
        return when {
            scaled >= MAX_SAMPLE -> {
                clippedSamples++
                Short.MAX_VALUE
            }
            scaled <= MIN_SAMPLE -> {
                clippedSamples++
                Short.MIN_VALUE
            }
            else -> scaled.toInt().toShort()
        }
    }

    internal companion object {
        /**
         * A one-pole smoothing coefficient for a given time constant.
         *
         * Per sample rather than per buffer: buffer sizes vary with the device and the format, and a
         * smoothing rate that changed with them would make the boost sound different on each.
         */
        fun coefficientFor(milliseconds: Float, sampleRate: Int): Float {
            if (sampleRate <= 0 || milliseconds <= 0f) return 1f
            return 1f - exp(-1f / (milliseconds / MILLIS_PER_SECOND * sampleRate))
        }

        /** The gain in decibels, for logs and tests — the unit the ear thinks in. */
        fun decibels(gain: Float): Float =
            if (gain <= 0f) 0f else DECIBELS_PER_DECADE * ln(gain) / LN_10

        /**
         * The average level to aim for, as a fraction of full scale.
         *
         * Speech averaging around an eighth of full scale is loud without living against the ceiling,
         * which leaves the limiter room to catch peaks without engaging hard.
         */
        private const val TARGET_LEVEL = 0.125f

        /** Never quieter than the recording: turning things down is a surprise nobody asked for. */
        private const val MIN_GAIN = 1f

        /** +30 dB. */
        private const val MAX_GAIN = 31.6f

        private const val DYNAMICS_OVER_QUIETEST = 4f
        private const val DYNAMICS_BLOCK_MS = 20L
        private const val DYNAMICS_TIMEOUT_MS = 500L

        /** Slow: the rate the automatic gain drifts. The limiter has its own release. */
        private const val GAIN_RISE_MS = 400f

        /** How fast the item's level estimate follows the audio once it has settled. */
        private const val LOUDNESS_SETTLE_MS = 2_000f

        /** ...and at the very start, where waiting two seconds would be an audible swell. */
        private const val LOUDNESS_WARMUP_MS = 150f
        private const val WARMUP_SECONDS = 2

        /** Just under full scale, so rounding cannot take a limited peak over it. */
        private const val CEILING = 0.95f

        /** 20 dB below the recent peak — see [gateFor] for why this cannot be an absolute level. */
        private const val RELATIVE_GATE = 0.1f

        /**
         * About -66 dBFS: digital silence and dither, where there is nothing to measure at all.
         *
         * Only the *measurement* ignores what is below the gate — the gain is not tapered away down
         * there any more. That taper existed to stop a fixed +30 dB turning hiss into a roar, and it
         * chopped the quiet ends of words. Automatic gain removes the need for it: the lift applied
         * is proportionate to the item, so its noise floor rises with its speech, exactly as it
         * would if you simply turned the volume up.
         */
        private const val SILENCE_FLOOR = 0.0005f

        /** How long a pause can be before it starts counting as content. */
        private const val PEAK_DECAY_MS = 2_000f

        private const val FULL_SCALE = 32_768f
        private const val MAX_SAMPLE = 32_767f
        private const val MIN_SAMPLE = -32_768f
        private const val MILLIS_PER_SECOND = 1_000f
        private const val EPSILON = 1e-6f
        private const val DECIBELS_PER_DECADE = 20f
        private const val LN_10 = 2.302_585f
    }
}
