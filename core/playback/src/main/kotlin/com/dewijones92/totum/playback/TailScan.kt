package com.dewijones92.totum.playback

import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import com.dewijones92.totum.common.Diag
import java.nio.ByteOrder

internal data class TailScan(val speechEndsMs: Long?, val fromMs: Long, val heardMs: Long, val tookMs: Long)

internal fun scanTailForSpeech(path: String, durationMs: Long, weights: SpeechWeights): TailScan? {
    val fromMs = (durationMs - minOf(TAIL_SCAN_MS, durationMs * TAIL_SCAN_PERCENT / PERCENT)).coerceAtLeast(0)
    val started = System.currentTimeMillis()
    val extractor = MediaExtractor()
    try {
        extractor.setDataSource(path)
        val trackIndex = (0 until extractor.trackCount).firstOrNull {
            extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
        }
        if (trackIndex == null) {
            Diag.log(TAG, "no audio track to scan in ${path.substringAfterLast('/')}")
            return null
        }
        extractor.selectTrack(trackIndex)
        val format = extractor.getTrackFormat(trackIndex)
        extractor.seekTo(fromMs * MICROS_PER_MS, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
        val heard = TailDecoder(extractor, format, fromMs, weights).run() ?: return null
        return TailScan(heard.speechEndsMs, fromMs, heard.heardMs, System.currentTimeMillis() - started)
    } finally {
        extractor.release()
    }
}

private class TailDecoder(
    private val extractor: MediaExtractor,
    private val format: MediaFormat,
    private val fromMs: Long,
    private val weights: SpeechWeights,
) {
    data class Heard(val speechEndsMs: Long, val heardMs: Long)

    private val codec = MediaCodec.createDecoderByType(format.getString(MediaFormat.KEY_MIME) ?: "audio/mp4a-latm")
    private var rate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
    private var channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
    private var track = SpeechTrack(SpeechModel(weights), rate)
    private var firstFrameMs: Long? = null
    private var pushed = 0L
    private var inputDone = false
    private val info = MediaCodec.BufferInfo()

    fun run(): Heard? {
        codec.configure(format, null, null, 0)
        codec.start()
        try {
            do {
                if (!inputDone) feed()
            } while (!drain())
        } finally {
            codec.stop()
            codec.release()
        }
        track.finish()
        val start = firstFrameMs ?: return null
        val endsMs = track.speechEndsAtFrame?.let { start + it * MS_PER_SECOND / rate } ?: start
        return Heard(endsMs, pushed * MS_PER_SECOND / rate)
    }

    private fun feed() {
        val index = codec.dequeueInputBuffer(TIMEOUT_US)
        if (index < 0) return
        val buffer = codec.getInputBuffer(index) ?: return
        val size = extractor.readSampleData(buffer, 0)
        if (size < 0) {
            codec.queueInputBuffer(index, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
            inputDone = true
        } else {
            codec.queueInputBuffer(index, 0, size, extractor.sampleTime, 0)
            extractor.advance()
        }
    }

    private fun drain(): Boolean {
        val index = codec.dequeueOutputBuffer(info, TIMEOUT_US)
        if (index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
            rate = codec.outputFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            channels = codec.outputFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            if (pushed == 0L) track = SpeechTrack(SpeechModel(weights), rate)
        }
        if (index < 0) return false
        val ptsMs = info.presentationTimeUs / MICROS_PER_MS
        codec.getOutputBuffer(index)?.takeIf { info.size > 0 && ptsMs >= fromMs }?.let { buffer ->
            if (firstFrameMs == null) firstFrameMs = ptsMs
            val samples = buffer.order(ByteOrder.nativeOrder()).asShortBuffer()
            val frames = info.size / BYTES_PER_SAMPLE / channels
            for (frame in 0 until frames) {
                var sum = 0f
                for (c in 0 until channels) sum += samples.get(frame * channels + c)
                track.push(sum / channels / Short.MAX_VALUE)
            }
            pushed += frames
        }
        codec.releaseOutputBuffer(index, false)
        return info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
    }
}

private const val TAG = "silence"
private const val TAIL_SCAN_MS = 180_000L
private const val TAIL_SCAN_PERCENT = 30
private const val PERCENT = 100
private const val MICROS_PER_MS = 1_000L
private const val MS_PER_SECOND = 1_000L
private const val TIMEOUT_US = 10_000L
private const val BYTES_PER_SAMPLE = 2
