package com.dewijones92.totum.playback

import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.ForwardingAudioSink
import com.dewijones92.totum.common.Diag
import java.nio.ByteBuffer

@OptIn(markerClass = [UnstableApi::class])
@UnstableApi
internal class HeardSilenceAudioSink(
    sink: AudioSink,
    private val cutter: SilenceCuttingAudioProcessor,
) : ForwardingAudioSink(sink) {

    private var baselineUs: Long? = null
    private var seenFlushes = -1L
    private var heldBackUs = 0L
    private var released = 0L

    override fun handleBuffer(buffer: ByteBuffer, presentationTimeUs: Long, encodedAccessUnitCount: Int): Boolean {
        if (cutter.flushes != seenFlushes) baselineUs = null
        val handled = super.handleBuffer(buffer, presentationTimeUs, encodedAccessUnitCount)
        if (cutter.flushes != seenFlushes || baselineUs == null) {
            seenFlushes = cutter.flushes
            baselineUs = presentationTimeUs
        }
        return handled
    }

    override fun getCurrentPositionUs(sourceEnded: Boolean): Long {
        val counted = super.getCurrentPositionUs(sourceEnded)
        val baseline = baselineUs
        if (counted == AudioSink.CURRENT_POSITION_NOT_SET || baseline == null || cutter.flushes != seenFlushes) {
            return counted
        }
        val allSkippedUs = cutter.framesToUs(cutter.skippedFrames)
        if (allSkippedUs == 0L) return counted
        val heardUs = counted - allSkippedUs
        val heardFrames = cutter.usToFrames(heardUs - baseline)
        val heardSkippedUs = cutter.framesToUs(cutter.heard?.skippedBy(heardFrames) ?: 0L)
        val holdingUs = allSkippedUs - heardSkippedUs
        if (holdingUs < heldBackUs) {
            released++
            if (released == 1L || released % RELEASE_LOG_EVERY == 0L) {
                Diag.log(
                    "silence",
                    "cut #$released heard ${(heardUs - baseline) / MICROS_PER_MS}ms into the stream: clock moved on " +
                        "${(heldBackUs - holdingUs) / MICROS_PER_MS}ms when it was heard, not when it was cut",
                )
            }
        }
        heldBackUs = holdingUs
        return heardUs + heardSkippedUs
    }

    override fun flush() {
        super.flush()
        baselineUs = null
        heldBackUs = 0L
    }

    private companion object {
        const val RELEASE_LOG_EVERY = 100L
        const val MICROS_PER_MS = 1_000L
    }
}
