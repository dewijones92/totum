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
    val durationUs: Long,
    val trustedEndUs: Long? = null,
) {
    val segmentSamples: Long get() = framesPerSegment.toLong() * Fmp4Aac.SAMPLES_PER_FRAME

    fun samplesToUs(samples: Long): Long = samples * MICROS / format.sampleRate

    companion object {
        const val MICROS: Long = 1_000_000L
    }
}

internal class EarlyEnd(val atUs: Long, message: String) : java.io.IOException(message)

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
    private var lastFedUs = plan.startUs
    private var decodedDone = false
    private var endQueued = false
    private var encodeDone = false

    val lastSegment: Int get() = segment - 1

    private val spentNs = LongArray(STAGES)

    private inline fun <T> timed(stage: Int, block: () -> T): T {
        val began = System.nanoTime()
        return try {
            block()
        } finally {
            spentNs[stage] += System.nanoTime() - began
        }
    }

    fun run() {
        try {
            open()
            while (!encodeDone && !sink.stale()) {
                timed(WAIT) { sink.waitWhileAhead(segment) }
                val work = timed(FEED) { feedDecoder() } + timed(DECODE) { drainDecoder() } +
                    timed(ENCODE_IN) { feedEncoder() } + timed(ENCODE_OUT) { drainEncoder() }
                if (work == 0) timed(IDLE) { Thread.sleep(IDLE_MS) }
            }
        } finally {
            close()
        }
    }

    private fun open() {
        extractor.selectTrack(track)
        val inFormat = extractor.getTrackFormat(track)
        val seekStarted = System.currentTimeMillis()
        extractor.seekTo(plan.startUs, MediaExtractorCompat.SEEK_TO_PREVIOUS_SYNC)
        val landedUs = extractor.sampleTime
        var skipped = 0
        while (extractor.sampleTime in 0 until plan.startUs - PREROLL_US && extractor.advance()) skipped++
        Diag.log(
            "cast",
            "audio ${plan.label} seeked to ${plan.startUs / MILLI}ms " +
                "in ${System.currentTimeMillis() - seekStarted}ms (landed at ${landedUs / MILLI}ms, " +
                "skipped $skipped packet(s) undecoded to ${extractor.sampleTime / MILLI}ms)",
        )
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

    private fun feedDecoder(): Int {
        val codec = decoder ?: return 0
        var fed = 0
        while (!sourceDone && pending.size < MAX_PENDING) {
            val i = codec.dequeueInputBuffer(0)
            if (i < 0) break
            val n = timed(READ) { extractor.readSampleData(codec.getInputBuffer(i)!!, 0) }
            if (n < 0) {
                endedEarly()?.let { throw EarlyEnd(lastFedUs, it) }
                codec.queueInputBuffer(i, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                sourceDone = true
            } else {
                lastFedUs = extractor.sampleTime
                codec.queueInputBuffer(i, 0, n, lastFedUs, 0)
                extractor.advance()
            }
            fed++
        }
        return fed
    }

    private fun endedEarly(): String? {
        val slack = maxOf(EARLY_END_US, plan.durationUs / EARLY_END_FRACTION)
        if (plan.durationUs <= 0 || lastFedUs >= plan.durationUs - slack) return null
        plan.trustedEndUs?.let { if (kotlin.math.abs(it - lastFedUs) < SAME_END_US) return null }
        return "source ended at ${lastFedUs / MILLI}ms of ${plan.durationUs / MILLI}ms " +
            "(the extractor turns a failed read into an end)"
    }

    private fun drainDecoder(): Int {
        val codec = decoder ?: return 0
        var drained = 0
        while (!decodedDone) {
            val o = codec.dequeueOutputBuffer(info, 0)
            if (o >= 0) {
                drained++
                decodedOne(codec, o)
            } else if (o != MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                break
            }
        }
        return drained
    }

    private fun decodedOne(codec: MediaCodec, o: Int) {
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
        timed(PROCESS) {
            chain.forEach { processor ->
                processor.queueInput(buffer)
                buffer = copy(processor.output)
            }
        }
        if (buffer.hasRemaining()) pending.add(buffer)
    }

    private fun feedEncoder(): Int {
        val codec = encoder ?: return 0
        var fed = 0
        val frameBytes = BYTES_PER_SAMPLE * plan.format.channels
        while (pending.isNotEmpty()) {
            val i = codec.dequeueInputBuffer(0)
            if (i < 0) return fed
            fed++
            val buf = codec.getInputBuffer(i)!!
            val pts = ptsUs()
            var filled = 0
            while (pending.isNotEmpty() && buf.remaining() >= frameBytes) {
                val chunk = pending.first()
                val take = minOf(buf.remaining() / frameBytes * frameBytes, chunk.remaining())
                buf.put(chunk.duplicate().apply { limit(position() + take) })
                chunk.position(chunk.position() + take)
                if (!chunk.hasRemaining()) pending.removeFirst()
                filled += take
            }
            codec.queueInputBuffer(i, 0, filled, pts, 0)
            inputSamples += filled / frameBytes
        }
        if (decodedDone && !endQueued) {
            val i = codec.dequeueInputBuffer(0)
            if (i >= 0) {
                codec.queueInputBuffer(i, 0, 0, ptsUs(), MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                endQueued = true
                fed++
            }
        }
        return fed
    }

    private fun drainEncoder(): Int {
        val codec = encoder ?: return 0
        var drained = 0
        while (!encodeDone) {
            val e = codec.dequeueOutputBuffer(info, 0)
            if (e >= 0) {
                drained++
                encodedOne(codec, e)
            } else if (e != MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                break
            }
        }
        return drained
    }

    private fun encodedOne(codec: MediaCodec, e: Int) {
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
            pace()
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

    private val began = System.currentTimeMillis()

    private fun pace() {
        val made = segment - plan.first
        if (made != 1 && made % PACE_EVERY != 0) return
        val tookMs = System.currentTimeMillis() - began
        val audioMs = made * plan.samplesToUs(plan.segmentSamples) / MILLI
        Diag.log(
            "cast",
            "audio ${plan.label}: $made segment(s) from ${plan.first} in ${tookMs}ms " +
                "(%.1fx real time, waiting for the TV included); ms spent "
                    .format(audioMs.toDouble() / tookMs.coerceAtLeast(1)) +
                STAGE_NAMES.indices.joinToString(" ") { "${STAGE_NAMES[it]}=${spentNs[it] / NANOS_PER_MS}" },
        )
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
        const val IDLE_MS = 2L
        const val PREROLL_US = 200_000L
        const val EARLY_END_US = 3_000_000L
        const val EARLY_END_FRACTION = 50
        const val SAME_END_US = 2_000_000L
        const val FEED = 0
        const val READ = 1
        const val DECODE = 2
        const val PROCESS = 3
        const val ENCODE_IN = 4
        const val ENCODE_OUT = 5
        const val IDLE = 6
        const val WAIT = 7
        const val STAGES = 8
        const val NANOS_PER_MS = 1_000_000L
        val STAGE_NAMES = listOf("feed", "read", "decode", "process", "encodeIn", "encodeOut", "idle", "waitForTv")
        const val MAX_PENDING = 64
        const val PACE_EVERY = 10
        const val BITRATE = 160_000
        const val MAX_INPUT = 32 * 1024
        const val BYTES_PER_SAMPLE = 2
        const val MILLI = 1000L
    }
}
