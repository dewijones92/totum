package com.dewijones92.totum.playback

import androidx.annotation.OptIn
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.AudioProcessorChain
import androidx.media3.common.audio.SonicAudioProcessor
import androidx.media3.common.util.UnstableApi
import com.dewijones92.totum.common.Diag

@OptIn(markerClass = [UnstableApi::class])
@UnstableApi
internal class SilenceCuttingAudioProcessorChain(
    before: Array<AudioProcessor>,
    private val cutter: SilenceCuttingAudioProcessor,
    private val sonic: SonicAudioProcessor = SonicAudioProcessor(),
) : AudioProcessorChain {

    private val processors: Array<AudioProcessor> = before + arrayOf(cutter, sonic)

    override fun getAudioProcessors(): Array<AudioProcessor> = processors

    override fun applyPlaybackParameters(playbackParameters: PlaybackParameters): PlaybackParameters {
        sonic.setSpeed(playbackParameters.speed)
        sonic.setPitch(playbackParameters.pitch)
        return playbackParameters
    }

    override fun applySkipSilenceEnabled(skipSilenceEnabled: Boolean): Boolean {
        if (cutter.enabled != skipSilenceEnabled) Diag.log("silence", "sink applied skip-silence=$skipSilenceEnabled")
        cutter.enabled = skipSilenceEnabled
        return skipSilenceEnabled
    }

    override fun getMediaDuration(playoutDuration: Long): Long =
        if (sonic.isActive) sonic.getMediaDuration(playoutDuration) else playoutDuration

    override fun getSkippedOutputFrameCount(): Long = cutter.skippedFrames
}
