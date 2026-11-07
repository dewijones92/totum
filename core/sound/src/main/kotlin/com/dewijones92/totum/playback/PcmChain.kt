package com.dewijones92.totum.playback

public class PcmChain(
    sampleRate: Int,
    private val channels: Int,
    skipSilence: Boolean,
    boost: VolumeBoost,
    speech: SpeechWeights? = null,
) {
    private val cutter: SilenceCutter? = if (skipSilence) {
        SilenceCutter(sampleRate, channels, speech = speech?.let { SpeechTrack(SpeechModel(it), sampleRate) })
    } else {
        null
    }
    private val booster: LoudnessBoost? = if (boost == VolumeBoost.OFF) {
        null
    } else {
        LoudnessBoost(sampleRate, channels).also { it.level = boost }
    }

    public val changesSound: Boolean get() = cutter != null || booster != null

    public val skippedFrames: Long get() = cutter?.skippedFrames ?: 0L

    public fun process(input: ShortArray, samples: Int = input.size): ShortArray {
        val frames = samples / channels
        val cut = cutter?.let { it.process(input, frames).copyOf(it.outputSamples) }
            ?: input.copyOf(frames * channels)
        return boosted(cut)
    }

    public fun finish(): ShortArray {
        val tail = cutter?.let { it.endOfStream().copyOf(it.outputSamples) } ?: ShortArray(0)
        val boostedTail = boosted(tail)
        val drained = booster?.let { it.drain().copyOf(it.outputSamples) } ?: ShortArray(0)
        return boostedTail + drained
    }

    private fun boosted(cut: ShortArray): ShortArray {
        val active = booster ?: return cut
        if (cut.isEmpty()) return cut
        return active.process(cut, cut.size).copyOf(active.outputSamples)
    }
}
