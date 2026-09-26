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

    private val clock = HeardClock()
    private var seenFlushes = -1L
    private var released = 0L
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
        val position = clock.position(counted, cutter.framesToUs(cutter.skippedFrames)) { heardUs ->
            cutter.framesToUs(cutter.heard?.skippedBy(cutter.usToFrames(heardUs)) ?: 0L)
        }
        if (clock.releasedUs > 0) cutHeard()
        return position
    }

    override fun playToEndOfStream() {
        super.playToEndOfStream()
        clock.inputEnded()
    }

    override fun flush() {
        super.flush()
        seenFlushes = cutter.flushes
        cutter.takePreviousSkippedBy()
        clock.seeked()
    }

    private fun noticeFlush() {
        if (cutter.flushes == seenFlushes) return
        seenFlushes = cutter.flushes
        val midStream = clock.started
        clock.processorsFlushed(cutter.takePreviousSkippedBy())
        if (midStream) {
            Diag.log(
                "silence",
                "processors flushed mid-stream: the previous stretch's cuts count as they are heard " +
                    "(${clock.waitingOnFlushes} flush(es) not yet heard)",
            )
        }
    }

    private fun cutHeard() {
        released++
        listener?.onSilenceSkipped()
        if (released == 1L || released % RELEASE_LOG_EVERY == 0L) {
            Diag.log(
                "silence",
                "cut #$released heard ${clock.sinceStartUs / MICROS_PER_MS}ms into the stream: clock moved on " +
                    "${clock.releasedUs / MICROS_PER_MS}ms when it was heard, not when it was cut",
            )
        }
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
        const val RELEASE_LOG_EVERY = 100L
        const val MICROS_PER_MS = 1_000L
    }
}
