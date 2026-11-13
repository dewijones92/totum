package com.dewijones92.totum.playback

import androidx.annotation.OptIn
import androidx.media3.common.Format
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.mediacodec.MediaCodecAdapter
import androidx.media3.exoplayer.video.MediaCodecVideoRenderer
import com.dewijones92.totum.common.Diag
import java.nio.ByteBuffer

@OptIn(markerClass = [UnstableApi::class])
@UnstableApi
internal class SilenceCutPauseVideoRenderer(
    builder: Builder,
    private val cutOwedUs: () -> Long,
) : MediaCodecVideoRenderer(builder) {

    private var droppedForCuts = 0L

    @Suppress("LongParameterList")
    override fun processOutputBuffer(
        positionUs: Long,
        elapsedRealtimeUs: Long,
        codec: MediaCodecAdapter?,
        buffer: ByteBuffer?,
        bufferIndex: Int,
        bufferFlags: Int,
        sampleCount: Int,
        bufferPresentationTimeUs: Long,
        isDecodeOnly: Boolean,
        isLastBuffer: Boolean,
        format: Format,
    ): Boolean {
        val owedUs = cutOwedUs()
        val inCutPause = SilenceCutPauseFrames.dropped(bufferPresentationTimeUs, positionUs, owedUs, isLastBuffer)
        if (codec != null && inCutPause) {
            dropOutputBuffer(codec, bufferIndex, bufferPresentationTimeUs - outputStreamOffsetUs)
            droppedForCuts++
            if (droppedForCuts == 1L || droppedForCuts % LOG_EVERY_DROPPED == 0L) {
                val frameMs = (bufferPresentationTimeUs - outputStreamOffsetUs) / MICROS_PER_MS
                Diag.log(
                    "silence",
                    "video frame at ${frameMs}ms dropped, not drawn: it is in a pause already cut " +
                        "(position=${(positionUs - outputStreamOffsetUs) / MICROS_PER_MS}ms, " +
                        "cut still to move=${owedUs / MICROS_PER_MS}ms); $droppedForCuts such frame(s) since this " +
                        "renderer started",
                )
            }
            return true
        }
        return super.processOutputBuffer(
            positionUs,
            elapsedRealtimeUs,
            codec,
            buffer,
            bufferIndex,
            bufferFlags,
            sampleCount,
            bufferPresentationTimeUs,
            isDecodeOnly,
            isLastBuffer,
            format,
        )
    }

    private companion object {
        const val LOG_EVERY_DROPPED = 50L
        const val MICROS_PER_MS = 1_000L
    }
}
