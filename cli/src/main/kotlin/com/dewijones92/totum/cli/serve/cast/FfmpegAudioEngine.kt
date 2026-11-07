package com.dewijones92.totum.cli.serve.cast

import com.dewijones92.totum.cli.serve.sound.AdtsFrames
import com.dewijones92.totum.cli.serve.sound.ProcessedSound
import com.dewijones92.totum.cli.serve.sound.SOUND_CHANNELS
import com.dewijones92.totum.cli.serve.sound.SOUND_RATE
import com.dewijones92.totum.cli.serve.sound.SoundJob
import com.dewijones92.totum.common.Diag
import com.dewijones92.totum.data.targets.SoundSettings
import com.dewijones92.totum.playback.cast.AacFormat
import com.dewijones92.totum.playback.cast.AudioEngine
import com.dewijones92.totum.playback.cast.AudioProbe
import com.dewijones92.totum.playback.cast.Fmp4Aac
import com.dewijones92.totum.playback.cast.TranscodePlan
import com.dewijones92.totum.playback.cast.TranscodeSink
import com.dewijones92.totum.playback.cast.UNKNOWN_US
import java.io.IOException

internal class FfmpegAudioEngine(
    private val label: String,
    private val source: String,
    private val sound: SoundSettings,
    private val knownDurationUs: Long?,
    private val processed: ProcessedSound,
    private val onClose: () -> Unit = {},
) : AudioEngine {

    override val summary: String get() = "boost=${sound.boost}"

    override fun probe(): AudioProbe {
        val duration = knownDurationUs ?: processed.durationUs(source)
        Diag.log(
            "cast-target",
            "$label: ffmpeg engine, ${SOUND_RATE}Hz ${SOUND_CHANNELS}ch, duration " +
                (duration?.let { "${it / MILLI}ms" } ?: "unknown") +
                if (knownDurationUs != null) " (from the item)" else " (asked of ffprobe)",
        )
        return AudioProbe(SOUND_RATE, SOUND_CHANNELS, duration ?: UNKNOWN_US)
    }

    override fun silentFrame(format: AacFormat): ByteArray =
        processed.silentAacFrame() ?: throw IOException("ffmpeg gave no silent AAC frame")

    override fun transcode(plan: TranscodePlan, sink: TranscodeSink) {
        val pipeline = processed.toAac(SoundJob(label, source, sound, plan.startUs / MILLI))
        val frames = ArrayList<ByteArray>()
        val segments = Segmenter(plan, sink)
        try {
            val input = pipeline.output ?: throw IOException("no encoder output for $label")
            for (frame in AdtsFrames.read(input)) {
                if (sink.stale()) return
                frames.add(frame)
                if (frames.size < plan.framesPerSegment) continue
                segments.publish(frames, pipeline.skippedFrames)
                sink.waitWhileAhead(segments.next)
            }
            if (sink.stale()) return
            if (frames.isNotEmpty()) segments.publish(frames, pipeline.skippedFrames)
            sink.ended(segments.tvEndUs())
            Diag.log(
                "cast-target",
                "$label: ended at ${segments.tvEndUs() / MILLI}ms on the TV's clock; silences cut " +
                    "${plan.samplesToUs(pipeline.skippedFrames) / MILLI}ms in this run",
            )
        } finally {
            pipeline.stop()
        }
    }

    private class Segmenter(private val plan: TranscodePlan, private val sink: TranscodeSink) {
        var next = plan.first
            private set
        private var tvSamples = 0L

        fun tvEndUs(): Long = plan.samplesToUs(plan.first * plan.segmentSamples + tvSamples)

        fun publish(frames: MutableList<ByteArray>, skippedFrames: Long) {
            val take = minOf(plan.framesPerSegment, frames.size)
            val bytes = Fmp4Aac.segment(next + 1, next * plan.segmentSamples, frames.subList(0, take).toList())
            frames.subList(0, take).clear()
            tvSamples += take.toLong() * Fmp4Aac.SAMPLES_PER_FRAME
            sink.publish(next, bytes, tvEndUs(), plan.startUs + plan.samplesToUs(tvSamples + skippedFrames))
            next++
        }
    }

    override fun close() = onClose()

    private companion object {
        const val MILLI = 1000L
    }
}
