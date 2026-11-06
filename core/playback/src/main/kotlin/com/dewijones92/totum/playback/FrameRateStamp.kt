package com.dewijones92.totum.playback

import androidx.media3.common.C
import androidx.media3.common.DataReader
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.ParsableByteArray
import androidx.media3.common.util.UnstableApi
import androidx.media3.extractor.Extractor
import androidx.media3.extractor.ExtractorInput
import androidx.media3.extractor.ExtractorOutput
import androidx.media3.extractor.PositionHolder
import androidx.media3.extractor.SeekMap
import androidx.media3.extractor.SniffFailure
import androidx.media3.extractor.TrackOutput
import com.dewijones92.totum.common.Diag
import java.io.EOFException
import kotlin.math.abs

internal fun frameRateFrom(sampleTimesUs: List<Long>): Float? {
    if (sampleTimesUs.size < FRAMES_TO_JUDGE) return null
    val sorted = sampleTimesUs.sorted()
    val gaps = sorted.zipWithNext { a, b -> b - a }.filter { it > 0 }.sorted()
    if (gaps.isEmpty()) return null
    val medianUs = gaps[gaps.size / 2]
    val raw = MICROS_PER_SECOND / medianUs.toFloat()
    if (raw < SLOWEST_FPS || raw > FASTEST_FPS) return null
    val nearest = COMMON_RATES.minBy { abs(it - raw) }
    return if (abs(nearest - raw) / nearest < SNAP_TOLERANCE) nearest else raw
}

@UnstableApi
internal class StampsFrameRate(private val inner: Extractor) : Extractor {
    private val stamping = mutableListOf<FrameRateStampingTrack>()

    override fun sniff(input: ExtractorInput): Boolean = inner.sniff(input)

    override fun getSniffFailureDetails(): List<SniffFailure> = inner.sniffFailureDetails

    override fun init(output: ExtractorOutput) = inner.init(
        object : ExtractorOutput {
            override fun track(id: Int, type: Int): TrackOutput = output.track(id, type).let { track ->
                if (type == C.TRACK_TYPE_VIDEO) FrameRateStampingTrack(track).also(stamping::add) else track
            }

            override fun endTracks() = output.endTracks()

            override fun seekMap(seekMap: SeekMap) = output.seekMap(seekMap)
        },
    )

    override fun read(input: ExtractorInput, seekPosition: PositionHolder): Int =
        inner.read(input, seekPosition).also { result ->
            if (result == Extractor.RESULT_END_OF_INPUT) stamping.forEach { it.flush("the stream ended first") }
        }

    override fun seek(position: Long, timeUs: Long) {
        stamping.forEach { it.forget("a seek came first") }
        inner.seek(position, timeUs)
    }

    override fun release() = inner.release()

    override fun getUnderlyingImplementation(): Extractor = inner.underlyingImplementation
}

@UnstableApi
internal class FrameRateStampingTrack(private val out: TrackOutput) : TrackOutput {
    private class Held(
        val timeUs: Long,
        val flags: Int,
        val start: Int,
        val size: Int,
        val crypto: TrackOutput.CryptoData?
    )

    private var held: Format? = null
    private var judged: Float? = null
    private var bytes = ByteArray(INITIAL_BYTES)
    private var length = 0
    private val samples = ArrayList<Held>(FRAMES_TO_JUDGE)

    override fun format(format: Format) {
        val known = judged
        when {
            !MimeTypes.isVideo(format.sampleMimeType) || format.frameRate > 0f -> {
                if (held != null) release(null, "the format changed to one that states its rate")
                out.format(format)
            }
            known != null -> out.format(format.buildUpon().setFrameRate(known).build())
            else -> held = format
        }
    }

    override fun durationUs(durationUs: Long) = out.durationUs(durationUs)

    override fun sampleData(input: DataReader, length: Int, allowEndOfInput: Boolean, sampleDataPart: Int): Int {
        if (held == null) return out.sampleData(input, length, allowEndOfInput, sampleDataPart)
        room(length)
        val read = input.read(bytes, this.length, length)
        if (read == C.RESULT_END_OF_INPUT) {
            if (allowEndOfInput) return C.RESULT_END_OF_INPUT
            throw EOFException()
        }
        this.length += read
        return read
    }

    override fun sampleData(data: ParsableByteArray, length: Int, sampleDataPart: Int) {
        if (held == null) return out.sampleData(data, length, sampleDataPart)
        room(length)
        data.readBytes(bytes, this.length, length)
        this.length += length
    }

    override fun sampleMetadata(
        timeUs: Long,
        flags: Int,
        size: Int,
        offset: Int,
        cryptoData: TrackOutput.CryptoData?,
    ) {
        if (held == null) return out.sampleMetadata(timeUs, flags, size, offset, cryptoData)
        samples += Held(timeUs, flags, length - offset - size, size, cryptoData)
        if (samples.size < FRAMES_TO_JUDGE) return
        val fps = frameRateFrom(samples.map { it.timeUs })
        release(fps, if (fps == null) "sample times too uneven" else null)
    }

    fun flush(why: String) {
        if (held != null) release(frameRateFrom(samples.map { it.timeUs }), why)
    }

    fun forget(why: String) {
        val format = held ?: return
        Diag.log(
            "video",
            "frame rate not worked out for ${format.describeVideo()}: $why; ${samples.size} held frame(s) dropped"
        )
        held = null
        samples.clear()
        length = 0
        out.format(format)
    }

    private fun release(fps: Float?, why: String?) {
        val format = held ?: return
        held = null
        judged = fps
        if (fps == null) {
            Diag.log("video", "frame rate not worked out for ${format.describeVideo()}: $why; configured without one")
        } else {
            Diag.log(
                "video",
                "frame rate ${"%.2f".format(fps)}fps worked out from ${samples.size} sample times for " +
                    "${format.describeVideo()} before the decoder was configured (the container gave none)",
            )
        }
        out.format(if (fps == null) format else format.buildUpon().setFrameRate(fps).build())
        var end = 0
        samples.forEach { sample ->
            out.sampleData(ParsableByteArray(bytes.copyOfRange(sample.start, sample.start + sample.size)), sample.size)
            out.sampleMetadata(sample.timeUs, sample.flags, sample.size, 0, sample.crypto)
            end = sample.start + sample.size
        }
        if (length > end) out.sampleData(ParsableByteArray(bytes.copyOfRange(end, length)), length - end)
        samples.clear()
        length = 0
        bytes = ByteArray(INITIAL_BYTES)
    }

    private fun room(more: Int) {
        if (length + more > bytes.size) bytes = bytes.copyOf(maxOf(bytes.size * 2, length + more))
    }

    private fun Format.describeVideo(): String = "${codecs ?: sampleMimeType} ${width}x$height"
}

private const val FRAMES_TO_JUDGE = 12
private const val INITIAL_BYTES = 256 * 1024
private const val MICROS_PER_SECOND = 1_000_000f
private const val SLOWEST_FPS = 5f
private const val FASTEST_FPS = 240f
private const val SNAP_TOLERANCE = 0.01f
private val COMMON_RATES = listOf(
    24_000f / 1001,
    24f,
    25f,
    30_000f / 1001,
    30f,
    48f,
    50f,
    60_000f / 1001,
    60f,
    90f,
    100f,
    120_000f / 1001,
    120f,
)
