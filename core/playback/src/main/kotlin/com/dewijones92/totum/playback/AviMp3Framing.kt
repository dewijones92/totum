package com.dewijones92.totum.playback

import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.DataReader
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.ExperimentalApi
import androidx.media3.common.util.ParsableByteArray
import androidx.media3.common.util.UnstableApi
import androidx.media3.extractor.DefaultExtractorsFactory
import androidx.media3.extractor.Extractor
import androidx.media3.extractor.ExtractorInput
import androidx.media3.extractor.ExtractorOutput
import androidx.media3.extractor.ExtractorsFactory
import androidx.media3.extractor.MpegAudioUtil
import androidx.media3.extractor.PositionHolder
import androidx.media3.extractor.SeekMap
import androidx.media3.extractor.SniffFailure
import androidx.media3.extractor.TrackOutput
import androidx.media3.extractor.avi.AviExtractor
import androidx.media3.extractor.text.SubtitleParser
import com.dewijones92.totum.common.Diag
import java.io.EOFException

@UnstableApi
internal fun totumExtractors(): ExtractorsFactory = AviMp3Framing(DefaultExtractorsFactory())

@OptIn(ExperimentalApi::class)
@UnstableApi
internal class AviMp3Framing(private val delegate: ExtractorsFactory) : ExtractorsFactory {
    override fun createExtractors(): Array<Extractor> = delegate.createExtractors().map(::framed).toTypedArray()

    override fun createExtractors(uri: Uri, responseHeaders: Map<String, List<String>>): Array<Extractor> =
        delegate.createExtractors(uri, responseHeaders).map(::framed).toTypedArray()

    override fun experimentalSetTextTrackTranscodingEnabled(textTrackTranscodingEnabled: Boolean): ExtractorsFactory =
        apply { delegate.experimentalSetTextTrackTranscodingEnabled(textTrackTranscodingEnabled) }

    override fun setSubtitleParserFactory(subtitleParserFactory: SubtitleParser.Factory): ExtractorsFactory =
        apply { delegate.setSubtitleParserFactory(subtitleParserFactory) }

    override fun experimentalSetCodecsToParseWithinGopSampleDependencies(
        codecsToParseWithinGop: Int,
    ): ExtractorsFactory = apply {
        delegate.experimentalSetCodecsToParseWithinGopSampleDependencies(codecsToParseWithinGop)
    }

    private fun framed(extractor: Extractor): Extractor =
        if (extractor.underlyingImplementation is AviExtractor) FramedAvi(extractor) else extractor
}

@UnstableApi
private class FramedAvi(private val inner: Extractor) : Extractor {
    private val framers = mutableListOf<Mp3Framer>()

    override fun sniff(input: ExtractorInput): Boolean = inner.sniff(input)

    override fun getSniffFailureDetails(): List<SniffFailure> = inner.sniffFailureDetails

    override fun init(output: ExtractorOutput) = inner.init(
        object : ExtractorOutput {
            override fun track(id: Int, type: Int): TrackOutput = output.track(id, type).let { track ->
                if (type == C.TRACK_TYPE_AUDIO) Mp3Framer(track).also(framers::add) else track
            }

            override fun endTracks() = output.endTracks()

            override fun seekMap(seekMap: SeekMap) = output.seekMap(seekMap)
        },
    )

    override fun read(input: ExtractorInput, seekPosition: PositionHolder): Int = inner.read(input, seekPosition)

    override fun seek(position: Long, timeUs: Long) {
        framers.forEach(Mp3Framer::reset)
        inner.seek(position, timeUs)
    }

    override fun release() = inner.release()

    override fun getUnderlyingImplementation(): Extractor = inner.underlyingImplementation
}

@Suppress("TooManyFunctions")
@UnstableApi
internal class Mp3Framer(private val out: TrackOutput) : TrackOutput {
    private var framing = false
    private var pending = ByteArray(INITIAL_BYTES)
    private var size = 0
    private var locked = false
    private var anchorUs = C.TIME_UNSET
    private var framesSinceAnchor = 0L
    private val header = MpegAudioUtil.Header()
    private val next = MpegAudioUtil.Header()
    private val scratch = ParsableByteArray()
    private var chunks = 0L
    private var frames = 0L
    private var skipped = 0L
    private val chunkOffsets = mutableListOf<Int>()
    private val chunkTimesUs = mutableListOf<Long>()

    override fun format(format: Format) {
        framing = format.sampleMimeType == MimeTypes.AUDIO_MPEG
        if (framing) Diag.log("avi", "MP3 in an AVI: cutting its chunks into whole frames before the decoder")
        out.format(format)
    }

    override fun durationUs(durationUs: Long) = out.durationUs(durationUs)

    override fun sampleData(input: DataReader, length: Int, allowEndOfInput: Boolean, sampleDataPart: Int): Int {
        if (!framing) return out.sampleData(input, length, allowEndOfInput, sampleDataPart)
        room(length)
        val read = input.read(pending, size, length)
        if (read == C.RESULT_END_OF_INPUT) {
            if (allowEndOfInput) return C.RESULT_END_OF_INPUT
            throw EOFException()
        }
        size += read
        return read
    }

    override fun sampleData(data: ParsableByteArray, length: Int, sampleDataPart: Int) {
        if (!framing) return out.sampleData(data, length, sampleDataPart)
        room(length)
        data.readBytes(pending, size, length)
        size += length
    }

    override fun sampleMetadata(
        timeUs: Long,
        flags: Int,
        size: Int,
        offset: Int,
        cryptoData: TrackOutput.CryptoData?,
    ) {
        if (!framing) return out.sampleMetadata(timeUs, flags, size, offset, cryptoData)
        chunks++
        chunkOffsets += this.size - size - offset
        chunkTimesUs += timeUs
        val emitted = drain()
        this.size -= emitted
        System.arraycopy(pending, emitted, pending, 0, this.size)
        for (i in chunkOffsets.indices) chunkOffsets[i] -= emitted
        while (chunkOffsets.size > 1 && chunkOffsets[1] <= 0) {
            chunkOffsets.removeAt(0)
            chunkTimesUs.removeAt(0)
        }
    }

    fun reset() {
        if (framing && chunks > 0) {
            Diag.log("avi", "MP3 in an AVI: seek; $chunks chunk(s) became $frames frame(s), $skipped byte(s) skipped")
        }
        size = 0
        chunkOffsets.clear()
        chunkTimesUs.clear()
        locked = false
        anchorUs = C.TIME_UNSET
        framesSinceAnchor = 0
    }

    private fun drain(): Int {
        var at = 0
        while (true) {
            val frameSize = frameAt(at) ?: break
            if (frameSize == NOT_A_FRAME) {
                locked = false
                skipped++
                at++
            } else {
                emit(at, frameSize)
                at += frameSize
            }
        }
        return at
    }

    private fun frameAt(at: Int): Int? {
        if (size - at < HEADER_BYTES) return null
        if (!header.setForHeaderData(word(at))) return NOT_A_FRAME
        val frameSize = header.frameSize
        if (!locked) {
            when (confirmed(at, frameSize)) {
                null -> return null
                false -> return NOT_A_FRAME
                true -> Unit
            }
        }
        return frameSize.takeIf { size - at >= it }
    }

    private fun confirmed(at: Int, frameSize: Int): Boolean? {
        if (size - at < frameSize + HEADER_BYTES) return null
        if (!next.setForHeaderData(word(at + frameSize))) return false
        locked = true
        if (anchorUs == C.TIME_UNSET) {
            anchorUs = timeOfByte(at)
            framesSinceAnchor = 0
            if (frames == 0L) Diag.log("avi", "MP3 in an AVI: first whole frame after $skipped byte(s)")
        }
        return true
    }

    private fun emit(at: Int, frameSize: Int) {
        scratch.reset(pending, at + frameSize)
        scratch.position = at
        out.sampleData(scratch, frameSize, TrackOutput.SAMPLE_DATA_PART_MAIN)
        val timeUs = anchorUs + framesSinceAnchor * header.samplesPerFrame * MICROS / header.sampleRate
        out.sampleMetadata(timeUs, C.BUFFER_FLAG_KEY_FRAME, frameSize, 0, null)
        framesSinceAnchor++
        frames++
    }

    private fun timeOfByte(at: Int): Long {
        val chunk = chunkOffsets.indexOfLast { it <= at }.coerceAtLeast(0)
        return chunkTimesUs[chunk] + (at - chunkOffsets[chunk]) * BITS_PER_BYTE * MICROS / header.bitrate
    }

    private fun word(at: Int): Int =
        (0 until HEADER_BYTES).fold(0) { word, i -> (word shl Byte.SIZE_BITS) or (pending[at + i].toInt() and BYTE) }

    private fun room(more: Int) {
        if (size + more > pending.size) pending = pending.copyOf(maxOf(pending.size * 2, size + more))
    }

    private companion object {
        const val INITIAL_BYTES = 16 * 1024
        const val HEADER_BYTES = 4
        const val BITS_PER_BYTE = 8L
        const val MICROS = 1_000_000L
        const val BYTE = 0xFF
        const val NOT_A_FRAME = 0
    }
}
