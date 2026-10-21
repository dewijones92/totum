package com.dewijones92.totum.playback.cast

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.MediaExtractorCompat
import com.dewijones92.totum.common.Diag
import com.dewijones92.totum.playback.BoostingAudioProcessor
import com.dewijones92.totum.playback.SilenceCuttingAudioProcessor
import com.dewijones92.totum.playback.VolumeBoost
import java.nio.ByteBuffer
import java.nio.ByteOrder

internal class TranscodePlan(
    val format: AacFormat,
    val framesPerSegment: Int,
    val first: Int,
    val label: String,
    val startUs: Long,
) {
    val segmentSamples: Long get() = framesPerSegment.toLong() * Fmp4Aac.SAMPLES_PER_FRAME

    fun samplesToUs(samples: Long): Long = samples * MICROS / format.sampleRate

    companion object {
        const val MICROS: Long = 1_000_000L
    }
}

internal interface TranscodeSink {
    fun waitWhileAhead(segment: Int)
    fun stale(): Boolean
    fun publish(segment: Int, bytes: ByteArray, tvEndUs: Long, sourceEndUs: Long)
    fun ended(tvEndUs: Long)
}

@OptIn(markerClass = [UnstableApi::class])
@Suppress("TooManyFunctions")
internal class AudioTranscode(
    private val plan: TranscodePlan,
    private val extractor: MediaExtractorCompat,
    private val track: Int,
    private val boost: () -> VolumeBoost,
    private val sink: TranscodeSink,
    private val cutter: SilenceCuttingAudioProcessor?,
) {
    private val info = MediaCodec.BufferInfo()
    private val pending = ArrayDeque<ByteBuffer>()
    private val frames = ArrayList<ByteArray>()
    private val booster = BoostingAudioProcessor()
    private val chain: List<AudioProcessor> = listOfNotNull(cutter, booster)
    private var tvSamples = 0L
    private var decoder: MediaCodec? = null
    private var encoder: MediaCodec? = null
    private var segment = plan.first
    private var inputSamples = 0L
    private var sourceDone = false
    private var decodedDone = false
    private var endQueued = false
    private var encodeDone = false

    val lastSegment: Int get() = segment - 1

    fun run() {
        try {
            open()
            while (!encodeDone && !sink.stale()) {
                sink.waitWhileAhead(segment)
                feedDecoder()
                drainDecoder()
                feedEncoder()
                drainEncoder()
            }
        } finally {
            close()
        }
    }

    private fun open() {
        extractor.selectTrack(track)
        val inFormat = extractor.getTrackFormat(track)
        extractor.seekTo(plan.startUs, MediaExtractorCompat.SEEK_TO_PREVIOUS_SYNC)
        decoder = MediaCodec.createDecoderByType(inFormat.getString(MediaFormat.KEY_MIME)!!).apply {
            configure(inFormat, null, null, 0)
            start()
        }
        val out = MediaFormat.createAudioFormat(
            MediaFormat.MIMETYPE_AUDIO_AAC,
            plan.format.sampleRate,
            plan.format.channels
        )
        out.setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
        out.setInteger(MediaFormat.KEY_BIT_RATE, BITRATE)
        out.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, MAX_INPUT)
        encoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC).apply {
            configure(out, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            start()
        }
        booster.level = boost()
        cutter?.enabled = true
        chain.forEach {
            it.configure(AudioProcessor.AudioFormat(plan.format.sampleRate, plan.format.channels, C.ENCODING_PCM_16BIT))
            it.flush(AudioProcessor.StreamMetadata.DEFAULT)
        }
    }

    private fun feedDecoder() {
        if (sourceDone) return
        val codec = decoder ?: return
        val i = codec.dequeueInputBuffer(TIMEOUT_US)
        if (i < 0) return
        val n = extractor.readSampleData(codec.getInputBuffer(i)!!, 0)
        if (n < 0) {
            codec.queueInputBuffer(i, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
            sourceDone = true
        } else {
            codec.queueInputBuffer(i, 0, n, extractor.sampleTime, 0)
            extractor.advance()
        }
    }

    private fun drainDecoder() {
        if (decodedDone) return
        val codec = decoder ?: return
        val o = codec.dequeueOutputBuffer(info, TIMEOUT_US)
        if (o < 0) return
        trimmed(codec.getOutputBuffer(o)!!, codec.outputFormat)?.let(::processed)
        codec.releaseOutputBuffer(o, false)
        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
            var tail: ByteBuffer? = null
            chain.forEach { processor ->
                tail?.let(processor::queueInput)
                processor.queueEndOfStream()
                tail = copy(processor.output)
            }
            tail?.let(pending::add)
            decodedDone = true
        }
    }

    private fun processed(pcm: ByteBuffer) {
        val wanted = boost()
        if (booster.level != wanted) {
            Diag.log("cast", "audio ${plan.label} boost $wanted from segment $segment")
            booster.level = wanted
        }
        var buffer = pcm
        chain.forEach { processor ->
            processor.queueInput(buffer)
            buffer = copy(processor.output)
        }
        if (buffer.hasRemaining()) pending.add(buffer)
    }

    private fun feedEncoder() {
        val codec = encoder ?: return
        while (pending.isNotEmpty()) {
            val i = codec.dequeueInputBuffer(TIMEOUT_US)
            if (i < 0) return
            val buf = codec.getInputBuffer(i)!!
            val chunk = pending.first()
            val take = minOf(buf.remaining(), chunk.remaining())
            buf.put(chunk.duplicate().apply { limit(position() + take) })
            chunk.position(chunk.position() + take)
            if (!chunk.hasRemaining()) pending.removeFirst()
            codec.queueInputBuffer(i, 0, take, ptsUs(), 0)
            inputSamples += take / (BYTES_PER_SAMPLE * plan.format.channels)
        }
        if (decodedDone && !endQueued) {
            val i = codec.dequeueInputBuffer(TIMEOUT_US)
            if (i >= 0) {
                codec.queueInputBuffer(i, 0, 0, ptsUs(), MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                endQueued = true
            }
        }
    }

    private fun drainEncoder() {
        val codec = encoder ?: return
        val e = codec.dequeueOutputBuffer(info, TIMEOUT_US)
        if (e < 0) return
        if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0 && info.size > 0) {
            val out = codec.getOutputBuffer(e)!!
            out.position(info.offset)
            frames.add(ByteArray(info.size).also { out.get(it) })
        }
        codec.releaseOutputBuffer(e, false)
        val last = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
        while (frames.size >= plan.framesPerSegment || (last && frames.isNotEmpty())) {
            val take = minOf(plan.framesPerSegment, frames.size)
            val bytes = Fmp4Aac.segment(segment + 1, segment * plan.segmentSamples, frames.subList(0, take).toList())
            frames.subList(0, take).clear()
            tvSamples += take.toLong() * Fmp4Aac.SAMPLES_PER_FRAME
            val skipped = cutter?.skippedFrames ?: 0L
            sink.publish(segment, bytes, tvEndUs(), plan.startUs + plan.samplesToUs(tvSamples + skipped))
            segment++
        }
        if (last) {
            encodeDone = true
            sink.ended(tvEndUs())
            Diag.log(
                "cast",
                "audio ${plan.label} ended at ${tvEndUs() / MILLI}ms on the TV's clock; " +
                    "silences cut ${plan.samplesToUs(cutter?.skippedFrames ?: 0L) / MILLI}ms in this run",
            )
        }
    }

    private fun tvEndUs() = plan.samplesToUs(plan.first * plan.segmentSamples + tvSamples)

    private fun ptsUs() = inputSamples * TranscodePlan.MICROS / plan.format.sampleRate

    private fun trimmed(out: ByteBuffer, decoded: MediaFormat): ByteBuffer? {
        if (info.size <= 0) return null
        val channels = decoded.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
        val rate = decoded.getInteger(MediaFormat.KEY_SAMPLE_RATE)
        check(channels == plan.format.channels && rate == plan.format.sampleRate) {
            "decoder gives ${rate}Hz ${channels}ch, stream promised " +
                "${plan.format.sampleRate}Hz ${plan.format.channels}ch"
        }
        out.position(info.offset).limit(info.offset + info.size)
        if (info.presentationTimeUs < plan.startUs) {
            val skipSamples = ((plan.startUs - info.presentationTimeUs) * rate / TranscodePlan.MICROS).toInt()
            val skip = skipSamples * BYTES_PER_SAMPLE * channels
            if (skip >= info.size) return null
            out.position(info.offset + skip)
        }
        return out.slice().order(ByteOrder.nativeOrder()).takeIf { it.hasRemaining() }
    }

    private fun copy(b: ByteBuffer): ByteBuffer =
        ByteBuffer.allocateDirect(b.remaining()).order(ByteOrder.nativeOrder()).put(b).apply { flip() }

    private fun close() {
        listOfNotNull(decoder, encoder).forEach { codec ->
            runCatching { codec.stop() }
            runCatching { codec.release() }
        }
        extractor.release()
    }

    private companion object {
        const val TIMEOUT_US = 10_000L
        const val BITRATE = 160_000
        const val MAX_INPUT = 32 * 1024
        const val BYTES_PER_SAMPLE = 2
        const val MILLI = 1000L
    }
}
