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
    private val videoPlaying: () -> Boolean = { false },
    private val lateVideoFrames: () -> Long = { 0L },
    private val cutOwed: (Long) -> Unit = {},
) : ForwardingAudioSink(sink) {

    private val clock = HeardClock()
    private var seenFlushes = -1L
    private val stats = SilenceCutStats()
    private var listener: AudioSink.Listener? = null

    override fun setListener(listener: AudioSink.Listener) {
        this.listener = listener
        super.setListener(SkipsReportedWhenHeard(listener))
    }

    override fun handleBuffer(buffer: ByteBuffer, presentationTimeUs: Long, encodedAccessUnitCount: Int): Boolean {
        noticeFlush()
        val handled = super.handleBuffer(buffer, presentationTimeUs, encodedAccessUnitCount)
        noticeFlush()
        clock.streamStarts(presentationTimeUs)
        return handled
    }

    override fun getCurrentPositionUs(sourceEnded: Boolean): Long {
        val counted = super.getCurrentPositionUs(sourceEnded)
        if (counted == AudioSink.CURRENT_POSITION_NOT_SET || cutter.flushes != seenFlushes) return counted
        clock.maxJumpUs = if (videoPlaying()) VIDEO_JUMP_CAP_US else Long.MAX_VALUE
        val position = clock.position(counted, cutter.framesToUs(cutter.skippedFrames)) { heardUs ->
            cutter.framesToUs(cutter.heard?.skippedBy(cutter.usToFrames(heardUs)) ?: 0L)
        }
        cutOwed(clock.owingUs)
        if (clock.releasedUs > 0) cutHeard()
        return position
    }

    override fun playToEndOfStream() {
        super.playToEndOfStream()
        clock.inputEnded()
    }

    override fun flush() {
        cutter.relearnLevels = true
        super.flush()
        seenFlushes = cutter.flushes
        cutter.takePreviousSkippedBy()
        clock.seeked()
        cutOwed(0L)
    }

    private fun noticeFlush() {
        if (cutter.flushes == seenFlushes) return
        seenFlushes = cutter.flushes
        val midStream = clock.started
        clock.processorsFlushed(cutter.takePreviousSkippedBy())
        cutOwed(0L)
        if (midStream) {
            Diag.log(
                "silence",
                "processors flushed mid-stream: the previous stretch's cuts count as they are heard " +
                    "(${clock.waitingOnFlushes} flush(es) not yet heard)",
            )
        }
    }

    private fun cutHeard() {
        listener?.onSilenceSkipped()
        val line = stats.released(
            cutUs = clock.cutHeardUs,
            jumpUs = clock.releasedUs,
            owedUs = clock.owingUs,
            video = videoPlaying(),
            droppedFrames = lateVideoFrames(),
            nowMs = System.nanoTime() / NANOS_PER_MS,
        )
        if (line != null) Diag.log("silence", "${clock.sinceStartUs / MICROS_PER_MS}ms into the stream: $line")
    }

    @Suppress("TooManyFunctions")
    private class SkipsReportedWhenHeard(private val outer: AudioSink.Listener) : AudioSink.Listener {
        override fun onPositionDiscontinuity() = outer.onPositionDiscontinuity()
        override fun onPositionAdvancing(
            playoutStartSystemTimeMs: Long
        ) = outer.onPositionAdvancing(playoutStartSystemTimeMs)
        override fun onUnderrun(bufferSize: Int, bufferSizeMs: Long, elapsedSinceLastFeedMs: Long) =
            outer.onUnderrun(bufferSize, bufferSizeMs, elapsedSinceLastFeedMs)
        override fun onSkipSilenceEnabledChanged(skipSilenceEnabled: Boolean) =
            outer.onSkipSilenceEnabledChanged(skipSilenceEnabled)
        override fun onOffloadBufferEmptying() = outer.onOffloadBufferEmptying()
        override fun onOffloadBufferFull() = outer.onOffloadBufferFull()
        override fun onAudioSinkError(audioSinkError: Exception) = outer.onAudioSinkError(audioSinkError)
        override fun onAudioCapabilitiesChanged() = outer.onAudioCapabilitiesChanged()
        override fun onAudioTrackInitialized(audioTrackConfig: AudioSink.AudioTrackConfig) =
            outer.onAudioTrackInitialized(audioTrackConfig)
        override fun onAudioTrackReleased(audioTrackConfig: AudioSink.AudioTrackConfig) =
            outer.onAudioTrackReleased(audioTrackConfig)
        override fun onSilenceSkipped() = Unit
        override fun onAudioSessionIdChanged(audioSessionId: Int) = outer.onAudioSessionIdChanged(audioSessionId)
    }

    private companion object {
        const val MICROS_PER_MS = 1_000L
        const val NANOS_PER_MS = 1_000_000L
        const val VIDEO_JUMP_CAP_US = 400_000L
    }
}
