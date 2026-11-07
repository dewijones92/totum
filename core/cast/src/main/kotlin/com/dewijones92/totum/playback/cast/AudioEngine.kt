package com.dewijones92.totum.playback.cast

public const val UNKNOWN_US: Long = Long.MIN_VALUE + 1

public class AudioProbe(public val sampleRate: Int, public val channels: Int, public val durationUs: Long)

public class TranscodePlan(
    public val format: AacFormat,
    public val framesPerSegment: Int,
    public val first: Int,
    public val label: String,
    public val startUs: Long,
    public val durationUs: Long,
    public val trustedEndUs: Long? = null,
) {
    public val segmentSamples: Long get() = framesPerSegment.toLong() * Fmp4Aac.SAMPLES_PER_FRAME

    public fun samplesToUs(samples: Long): Long = samples * MICROS / format.sampleRate

    public companion object {
        public const val MICROS: Long = 1_000_000L
    }
}

public class EarlyEnd(public val atUs: Long, message: String) : java.io.IOException(message)

public interface TranscodeSink {
    public fun waitWhileAhead(segment: Int)
    public fun stale(): Boolean
    public fun publish(segment: Int, bytes: ByteArray, tvEndUs: Long, sourceEndUs: Long)
    public fun ended(tvEndUs: Long)
}

public interface AudioEngine {
    public val summary: String
    public fun probe(): AudioProbe
    public fun transcode(plan: TranscodePlan, sink: TranscodeSink)
    public fun silentFrame(format: AacFormat): ByteArray
    public fun close()
}

public class AudioRequest(
    public val mediaId: String,
    public val source: String,
    public val label: String,
    public val cuts: Boolean,
)

public fun interface AudioEngines {
    public fun open(request: AudioRequest): AudioEngine
}
