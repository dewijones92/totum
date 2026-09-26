package com.dewijones92.totum.playback

import com.dewijones92.totum.common.Diag
import com.dewijones92.totum.common.Vitals

internal class SilenceReport {

    var sampleRate: Int = 0

    private var totalSkippedFrames = 0L
    private var totalGaps = 0L
    private var saidNothingToCut = false

    fun after(active: SilenceCutter) {
        if (active.gapJustEnded()) {
            val gaps = totalGaps + active.gapsCut
            val removed = millis(totalSkippedFrames + active.skippedFrames)
            Vitals.set("silence.cut", "$gaps pauses, ${removed / MILLIS_PER_SECOND}s removed")
            if (gaps == 1L || gaps % GAP_LOG_EVERY == 0L) {
                Diag.log(
                    "silence",
                    "cut pause #$gaps (removed ${millis(active.lastGapRemovedFrames)}ms; " +
                        "${removed / MILLIS_PER_SECOND}s removed so far; ${levelsOf(active)})",
                )
            }
            saidNothingToCut = false
        }
        if (!saidNothingToCut && active.levels.framesSinceCut > sampleRate.toLong() * NOTHING_TO_CUT_SECONDS) {
            saidNothingToCut = true
            Diag.log(
                "silence",
                "no pause long enough to cut in ${NOTHING_TO_CUT_SECONDS}s of audio (${levelsOf(active)})"
            )
        }
    }

    fun banked(previous: SilenceCutter) {
        totalSkippedFrames += previous.skippedFrames
        totalGaps += previous.gapsCut
        saidNothingToCut = false
        if (previous.gapsCut > 0) {
            Diag.log(
                "silence",
                "this stretch: cut ${previous.gapsCut} pause(s), removed ${millis(previous.skippedFrames)}ms " +
                    "from ${millis(previous.outputFrames + previous.skippedFrames)}ms of audio (${levelsOf(previous)})",
            )
        }
    }

    private fun levelsOf(cutter: SilenceCutter): String =
        "cut level ${cutter.cutLevel} as of ${millis(cutter.lookaheadFrames.toLong())}ms ahead " +
            "(each frame is judged by the lower of it and the level when it arrived), " +
            "noise floor ${cutter.levels.noiseFloor}, speech peak ${cutter.levels.speechPeak}" +
            (
                cutter.speech?.let {
                    ", speech in ${it.chunksSpeech} of ${it.chunksHeard} 32ms chunks " +
                        "(${it.chunksDropped} dropped behind, " +
                        "${it.chunksWithheld} not-speech withheld while warming up after a start or a gap; " +
                        "model ${it.microsPerChunk}us per chunk); " +
                        "${cutter.smart}"
                } ?: ", not listening for speech"
                )

    private fun millis(frames: Long): Long = frames * MILLIS_PER_SECOND / sampleRate.coerceAtLeast(1)

    private companion object {
        const val GAP_LOG_EVERY = 100L
        const val NOTHING_TO_CUT_SECONDS = 60
        const val MILLIS_PER_SECOND = 1_000L
    }
}
