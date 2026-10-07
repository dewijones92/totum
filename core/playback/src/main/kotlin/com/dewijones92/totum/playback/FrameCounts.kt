package com.dewijones92.totum.playback

import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DecoderCounters

internal data class FrameCounts(
    val rendered: Int,
    val droppedLate: Int,
    val droppedToKeyframe: Int,
    val skipped: Int,
    val maxConsecutiveDropped: Int,
) {
    operator fun minus(earlier: FrameCounts): FrameCounts = FrameCounts(
        rendered = rendered - earlier.rendered,
        droppedLate = droppedLate - earlier.droppedLate,
        droppedToKeyframe = droppedToKeyframe - earlier.droppedToKeyframe,
        skipped = skipped - earlier.skipped,
        maxConsecutiveDropped = maxConsecutiveDropped,
    )

    fun describe(): String =
        "rendered=$rendered droppedLate=$droppedLate droppedToKeyframe=$droppedToKeyframe " +
            "skipped=$skipped maxConsecutiveDropped=$maxConsecutiveDropped"

    companion object {
        @UnstableApi
        fun of(counters: DecoderCounters): FrameCounts {
            counters.ensureUpdated()
            return FrameCounts(
                rendered = counters.renderedOutputBufferCount,
                droppedLate = counters.droppedBufferCount,
                droppedToKeyframe = counters.droppedToKeyframeCount,
                skipped = counters.skippedOutputBufferCount,
                maxConsecutiveDropped = counters.maxConsecutiveDroppedBufferCount,
            )
        }
    }
}

internal fun droppedFramesLine(
    droppedFrames: Int,
    elapsedMs: Long,
    speed: Float,
    skipSilence: Boolean,
    sinceLast: FrameCounts?,
): String =
    "dropped $droppedFrames frames over ${elapsedMs}ms [speed=$speed skipSilence=$skipSilence" +
        (sinceLast?.let { " since the last line: ${it.describe()}" } ?: "") + "]"
