package com.dewijones92.totum.playback

import androidx.annotation.OptIn
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.common.util.UnstableApi
import com.dewijones92.totum.common.Diag
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs

/**
 * Puts [LoudnessBoost] in the sink's processing chain, so the boost is part of playback itself.
 *
 * In the chain rather than on the audio session, which is where the old platform `LoudnessEnhancer`
 * sat. Three things follow from that and all of them matter:
 *
 * - **No platform ceiling.** `LoudnessEnhancer` is a device implementation with its own cap; this is
 *   arithmetic on the samples, so +30 dB means +30 dB on every phone.
 * - **It can compress.** A session effect gets one flat gain knob. Being in the stream means seeing
 *   the waveform, which is the only way to lift a quiet passage without clipping a loud one.
 * - **One place, both pillars, and it survives a session change.** The enhancer was bound to an audio
 *   session id and had to be torn down and rebuilt whenever that changed — a stale one silently did
 *   nothing, which is a bug shape this removes rather than fixes.
 *
 * Only 16-bit PCM is touched. Anything else passes through untouched rather than being reinterpreted
 * as samples, exactly as [SilenceCuttingAudioProcessor] does beside it in the same chain.
 */
@OptIn(markerClass = [UnstableApi::class])
@UnstableApi
internal class BoostingAudioProcessor : BaseAudioProcessor() {

    private var boost: LoudnessBoost? = null
    private var samples = ShortArray(0)
    private var samplesSinceReport = 0L
    private var reportedGainDb = Float.NaN
    private var reportedClipped = 0L
    private var samplesPerReport = 0L

    /** Set from the session command; takes effect on the next buffer, glided rather than switched. */
    var level: VolumeBoost = VolumeBoost.OFF
        set(value) {
            field = value
            boost?.level = value
            Diag.log("boost", "level -> $value")
        }

    override fun onConfigure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
        samplesPerReport = inputAudioFormat.sampleRate.toLong() * SECONDS_PER_REPORT
        reportedGainDb = Float.NaN
        // Rebuilt per configuration because the smoothing coefficients depend on the sample rate —
        // a boost tuned at 44.1kHz would attack twice as slowly at 22.05.
        boost = if (inputAudioFormat.encoding == ENCODING_16BIT) {
            LoudnessBoost(inputAudioFormat.sampleRate, inputAudioFormat.channelCount.coerceAtLeast(1))
                .apply { level = this@BoostingAudioProcessor.level }
        } else {
            // Said out loud: silently passing audio through is indistinguishable from a boost that
            // does nothing, and "the booster stopped working" would have no other explanation.
            Diag.warn("boost", "encoding ${inputAudioFormat.encoding} is not 16-bit PCM; not boosting")
            null
        }
        return inputAudioFormat
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        val remaining = inputBuffer.remaining()
        if (remaining == 0) return
        val active = boost
        if (active == null) {
            // Straight through, byte for byte.
            replaceOutputBuffer(remaining).put(inputBuffer).flip()
            return
        }

        val count = remaining / BYTES_PER_SAMPLE
        if (samples.size < count) samples = ShortArray(count)
        // Little-endian: the order the sink hands 16-bit PCM over in. Reading it the other way round
        // would turn every sample into noise, which is the kind of mistake that is obvious on a
        // device and invisible in a type checker.
        val shorts = inputBuffer.order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
        shorts.get(samples, 0, count)
        // The input has been consumed; the sink checks this rather than taking it on trust.
        inputBuffer.position(inputBuffer.limit())
        write(active.process(samples, count), active.outputSamples)
        if (level != VolumeBoost.OFF) report(active, count)
    }

    override fun onQueueEndOfStream() {
        val active = boost ?: return
        write(active.drain(), active.outputSamples)
    }

    override fun onFlush(streamMetadata: AudioProcessor.StreamMetadata) {
        boost?.flushDelay()
    }

    private fun write(output: ShortArray, count: Int) {
        if (count == 0) return
        val buffer = replaceOutputBuffer(count * BYTES_PER_SAMPLE).order(ByteOrder.LITTLE_ENDIAN)
        buffer.asShortBuffer().put(output, 0, count)
        buffer.position(count * BYTES_PER_SAMPLE)
        buffer.flip()
    }

    /**
     * What the boost is actually doing, in a report sent from Dewi's phone a week later.
     *
     * Dewi's standing rule: a change is done when a report can settle whether it worked *there*. The
     * question this has to answer is the one he raised by ear — *"causes distortion"* — so it carries
     * the two numbers that decide it: the gain being applied, and how many samples hit the rail.
     * `clipped=0` is the claim this design makes, so a non-zero is the whole diagnosis in one word.
     *
     * Rate-limited by CHANGE rather than by clock: the gain settles within seconds and then sits
     * still, so a well-behaved hour of listening costs a handful of lines. Logging every interval
     * regardless would fill a bounded report buffer with the news that nothing happened — which has
     * cost real evidence in this app before (see `SILENCE_LOG_EVERY`).
     */
    private fun report(active: LoudnessBoost, count: Int) {
        samplesSinceReport += count
        if (samplesSinceReport < samplesPerReport) return
        samplesSinceReport = 0

        val gainDb = LoudnessBoost.decibels(active.currentGain)
        val clipped = active.clippedSamples
        val moved = reportedGainDb.isNaN() || abs(gainDb - reportedGainDb) >= REPORT_WHEN_DB_MOVES
        if (!moved && clipped == reportedClipped) return
        reportedGainDb = gainDb
        reportedClipped = clipped

        val line = "auto gain ${"%.1f".format(gainDb)}dB " +
            "(level ${"%.4f".format(active.measuredLoudness)}) " +
            "limiter down to ${"%.1f".format(LoudnessBoost.decibels(active.deepestLimit))}dB clipped=$clipped"
        active.forgetDeepestLimit()
        // Clipping is impossible by construction, so a non-zero count is a broken assumption, not
        // loud audio — it gets a warning rather than a note.
        if (clipped > 0) Diag.warn("boost", line) else Diag.log("boost", line)
    }

    override fun onReset() {
        boost = null
        samples = ShortArray(0)
        samplesSinceReport = 0
        reportedGainDb = Float.NaN
        reportedClipped = 0
    }

    private companion object {
        /** `C.ENCODING_PCM_16BIT`, named locally so this file needs no Media3 constant import. */
        const val ENCODING_16BIT = 2
        const val BYTES_PER_SAMPLE = 2

        /** How often the gain is even considered for reporting. */
        const val SECONDS_PER_REPORT = 15

        /** ...and how far it must have moved to be worth a line. Below this, nothing has changed. */
        const val REPORT_WHEN_DB_MOVES = 2f
    }
}
