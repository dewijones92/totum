@file:Suppress(
    "MagicNumber",
    "LongParameterList",
    "CyclomaticComplexMethod",
    "ThrowsCount",
    "LoopWithTooManyJumpStatements",
)

package com.dewijones92.totum.playback.cast

import java.io.ByteArrayOutputStream

internal class MkvVideo private constructor(
    val codecs: String,
    val width: Int,
    val height: Int,
    private val sampleEntry: ByteArray,
    private val track: Long,
    private val cues: List<Cue>,
    private val end: Long,
    private val scaleNs: Long,
    private val frameMs: Long,
    val durationMs: Long,
) {
    data class Cue(val timeMs: Long, val offset: Long)

    data class Segment(val startMs: Long, val durationMs: Long)

    val timeline: List<Segment> = cues.mapIndexed { i, cue ->
        Segment(cue.timeMs, ((cues.getOrNull(i + 1)?.timeMs ?: durationMs) - cue.timeMs).coerceAtLeast(frameMs))
    }

    val describe: String get() = "$codecs ${width}x$height MKV, ${cues.size} cue(s), remuxed on demand"

    fun init(): ByteArray = Fmp4.init("vide", MILLIS, sampleEntry, width, height)

    fun segment(n: Int, read: RangeReader): ByteArray {
        val from = cues[n].offset
        val to = cues.getOrNull(n + 1)?.offset ?: end
        val frames = Clusters.frames(read.read(ByteSpan(from, to - 1)), track, scaleNs)
        if (frames.isEmpty()) throw UnreadableLayout("MKV segment $n ($from..$to) holds no frames of track $track")
        if (!frames.first().key) {
            com.dewijones92.totum.common.Diag.warn(
                "cast",
                "MKV segment $n does not start on a keyframe"
            )
        }
        return Fmp4.videoSegment(n + 1, frames, frameMs)
    }

    companion object {
        private const val MILLIS = 1000L
        private const val NANOS_PER_MS = 1_000_000L
        private const val DEFAULT_SCALE_NS = 1_000_000L
        private const val FALLBACK_FRAME_MS = 40L
        private const val CUE_HEAD = 16L

        fun probe(head: ByteArray, read: RangeReader): MkvVideo? {
            val segment = Ebml.children(head, 0, head.size).drop(1).firstOrNull { it.id == Ebml.SEGMENT }
                ?: throw UnreadableLayout("MKV has no Segment in its first ${head.size} bytes")
            val parts = Ebml.children(head, segment.dataStart, head.size).takeWhile { it.id != Ebml.CLUSTER }.toList()
            val seeks = parts.firstOrNull { it.id == Ebml.SEEK_HEAD }?.let { Ebml.seekTargets(head, it) }.orEmpty()
            val info = parts.firstOrNull { it.id == INFO }?.let { Ebml.fields(head, it) }.orEmpty()
            val scale = info[TIMECODE_SCALE]?.let { Ebml.uint(head, it.dataStart, it.length) } ?: DEFAULT_SCALE_NS
            val units = info[DURATION]?.let { Ebml.float(head, it.dataStart, it.length) } ?: 0.0
            val tracks = parts.firstOrNull { it.id == Ebml.TRACKS }
                ?: throw UnreadableLayout("MKV Tracks are not in its first ${head.size} bytes")
            val video = videoTrack(head, tracks) ?: return null
            val cuesAt = seeks[Ebml.CUES]?.let { segment.dataStart + it }
                ?: throw UnreadableLayout("MKV names no Cues, so it cannot be cut into segments")
            val segmentEnd = if (segment.unknownSize) {
                throw UnreadableLayout("MKV Segment has an unknown size")
            } else {
                segment.declaredEnd
            }
            val cues = readCues(read, cuesAt, segment.dataStart.toLong(), video.number, scale)
            if (cues.isEmpty()) throw UnreadableLayout("MKV Cues name no point on video track ${video.number}")
            return MkvVideo(
                codecs = video.codecs,
                width = video.width,
                height = video.height,
                sampleEntry = video.sampleEntry,
                track = video.number,
                cues = cues,
                end = minOf(segmentEnd, cuesAt.takeIf { it > cues.last().offset } ?: segmentEnd),
                scaleNs = scale,
                frameMs = video.defaultDurationNs?.let { (it + NANOS_PER_MS / 2) / NANOS_PER_MS } ?: FALLBACK_FRAME_MS,
                durationMs = (units * scale / NANOS_PER_MS).toLong(),
            )
        }

        private class VideoTrack(
            val number: Long,
            val codecs: String,
            val width: Int,
            val height: Int,
            val sampleEntry: ByteArray,
            val defaultDurationNs: Long?,
        )

        private fun videoTrack(head: ByteArray, tracks: Ebml.Element): VideoTrack? {
            val entry = Ebml.children(head, tracks.dataStart, tracks.dataEnd)
                .filter { it.id == Ebml.TRACK_ENTRY }
                .map { Ebml.fields(head, it) }
                .firstOrNull { f -> f[TRACK_TYPE]?.let { Ebml.uint(head, it.dataStart, it.length) } == TYPE_VIDEO }
                ?: return null
            if (entry.containsKey(CONTENT_ENCODINGS)) {
                throw UnreadableLayout("MKV video track is compressed or encrypted (ContentEncodings)")
            }
            val number = entry[TRACK_NUMBER]?.let { Ebml.uint(head, it.dataStart, it.length) }
                ?: throw UnreadableLayout("MKV video track has no number")
            val codecId = entry[Ebml.CODEC_ID]?.let {
                String(
                    head,
                    it.dataStart,
                    it.length,
                    Charsets.US_ASCII
                )
            }.orEmpty()
            val private = entry[Ebml.CODEC_PRIVATE]?.let { head.copyOfRange(it.dataStart, it.dataEnd) }
                ?: throw UnreadableLayout("MKV video track $codecId has no CodecPrivate")
            val picture = entry[Ebml.VIDEO]?.let { Ebml.fields(head, it) }.orEmpty()
            val width = picture[Ebml.PIXEL_WIDTH]?.let { Ebml.uint(head, it.dataStart, it.length).toInt() } ?: 0
            val height = picture[Ebml.PIXEL_HEIGHT]?.let { Ebml.uint(head, it.dataStart, it.length).toInt() } ?: 0
            val (entryType, configType, codecs) = when (codecId) {
                "V_MPEGH/ISO/HEVC" -> Triple("hev1", "hvcC", CodecStrings.hevc("hev1", private))
                "V_MPEG4/ISO/AVC" -> Triple("avc1", "avcC", CodecStrings.avc(private))
                else -> throw UnreadableLayout("MKV video codec $codecId is not one Totum remuxes for a TV")
            }
            return VideoTrack(
                number = number,
                codecs = codecs ?: throw UnreadableLayout("MKV $codecId CodecPrivate is too short"),
                width = width,
                height = height,
                sampleEntry = Fmp4.visualEntry(entryType, width, height, Boxes.box(configType, private)),
                defaultDurationNs = entry[DEFAULT_DURATION]?.let { Ebml.uint(head, it.dataStart, it.length) },
            )
        }

        private fun readCues(read: RangeReader, at: Long, segmentStart: Long, track: Long, scale: Long): List<Cue> {
            val top = read.read(ByteSpan(at, at + CUE_HEAD - 1))
            val id = Ebml.id(top, 0)
            if (id.value != Ebml.CUES) throw UnreadableLayout("MKV seek head points at ${id.value}, not Cues")
            val size = Ebml.size(top, id.next)
            val body = read.read(ByteSpan(at + size.next, at + size.next + size.value - 1))
            return Ebml.children(body, 0, body.size).filter { it.id == CUE_POINT }.mapNotNull { point ->
                val fields = Ebml.fields(body, point)
                val time = fields[CUE_TIME]?.let { Ebml.uint(body, it.dataStart, it.length) } ?: return@mapNotNull null
                val position = Ebml.children(body, point.dataStart, point.dataEnd)
                    .filter { it.id == CUE_TRACK_POSITIONS }
                    .map { Ebml.fields(body, it) }
                    .firstOrNull { f -> f[CUE_TRACK]?.let { Ebml.uint(body, it.dataStart, it.length) } == track }
                    ?.get(CUE_CLUSTER_POSITION)
                    ?.let { Ebml.uint(body, it.dataStart, it.length) }
                    ?: return@mapNotNull null
                Cue(time * scale / NANOS_PER_MS, segmentStart + position)
            }.distinctBy { it.offset }.sortedBy { it.offset }.toList()
        }

        private const val INFO = 0x1549A966L
        private const val TIMECODE_SCALE = 0x2AD7B1L
        private const val DURATION = 0x4489L
        private const val TRACK_NUMBER = 0xD7L
        private const val TRACK_TYPE = 0x83L
        private const val TYPE_VIDEO = 1L
        private const val DEFAULT_DURATION = 0x23E383L
        private const val CONTENT_ENCODINGS = 0x6D80L
        private const val CUE_POINT = 0xBBL
        private const val CUE_TIME = 0xB3L
        private const val CUE_TRACK_POSITIONS = 0xB7L
        private const val CUE_TRACK = 0xF7L
        private const val CUE_CLUSTER_POSITION = 0xF1L
    }
}

internal class VideoFrame(val ptsMs: Long, val key: Boolean, val bytes: ByteArray)

internal object Clusters {

    fun frames(bytes: ByteArray, track: Long, scaleNs: Long): List<VideoFrame> {
        val out = ArrayList<VideoFrame>()
        var clusterTime = 0L
        var at = 0
        while (at < bytes.size) {
            val id = runCatching { Ebml.id(bytes, at) }.getOrNull() ?: break
            val size = runCatching { Ebml.size(bytes, id.next) }.getOrNull() ?: break
            val end = if (size.unknown) bytes.size else (size.next + size.value).toInt().coerceAtMost(bytes.size)
            when (id.value) {
                Ebml.CLUSTER -> {
                    at = size.next
                    continue
                }
                CLUSTER_TIMECODE -> clusterTime = Ebml.uint(bytes, size.next, end - size.next)
                SIMPLE_BLOCK -> block(bytes, size.next, end, track, clusterTime, scaleNs, null)?.let(out::add)
                BLOCK_GROUP -> group(bytes, size.next, end, track, clusterTime, scaleNs)?.let(out::add)
            }
            at = end
        }
        return out
    }

    private fun group(
        bytes: ByteArray,
        from: Int,
        to: Int,
        track: Long,
        clusterTime: Long,
        scaleNs: Long,
    ): VideoFrame? {
        val fields = Ebml.children(bytes, from, to).toList()
        val block = fields.firstOrNull { it.id == BLOCK } ?: return null
        val referenced = fields.any { it.id == REFERENCE_BLOCK }
        return block(bytes, block.dataStart, block.dataEnd, track, clusterTime, scaleNs, !referenced)
    }

    private fun block(
        bytes: ByteArray,
        from: Int,
        to: Int,
        track: Long,
        clusterTime: Long,
        scaleNs: Long,
        keyFromGroup: Boolean?,
    ): VideoFrame? {
        val number = Ebml.size(bytes, from)
        if (number.value != track) return null
        val relative = ((bytes[number.next].toInt() shl 8) or (bytes[number.next + 1].toInt() and 0xFF)).toShort()
        val flags = bytes[number.next + 2].toInt() and 0xFF
        if ((flags shr 1) and 0x3 != 0) throw UnreadableLayout("MKV video blocks are laced")
        val pts = (clusterTime + relative) * scaleNs / 1_000_000L
        val key = keyFromGroup ?: (flags and 0x80 != 0)
        return VideoFrame(pts, key, bytes.copyOfRange(number.next + 3, to))
    }

    private const val CLUSTER_TIMECODE = 0xE7L
    private const val SIMPLE_BLOCK = 0xA3L
    private const val BLOCK_GROUP = 0xA0L
    private const val BLOCK = 0xA1L
    private const val REFERENCE_BLOCK = 0xFBL
}

internal object Fmp4 {

    fun init(handler: String, timescale: Long, sampleEntry: ByteArray, width: Int, height: Int): ByteArray =
        Boxes.concat(
            Boxes.box("ftyp", ascii("iso6") + u32(0) + ascii("iso6") + ascii("mp41") + ascii("dash")),
            Boxes.box("moov", moov(handler, timescale, sampleEntry, width, height)),
        )

    fun visualEntry(type: String, width: Int, height: Int, config: ByteArray): ByteArray = Boxes.box(
        type,
        ByteArray(6) + u16(1) + ByteArray(16) + u16(width) + u16(height) + u32(RESOLUTION_72_DPI) +
            u32(RESOLUTION_72_DPI) + u32(0) + u16(1) + ByteArray(32) + u16(DEPTH_COLOUR) + u16(0xFFFF) + config,
    )

    fun videoSegment(sequence: Int, frames: List<VideoFrame>, frameMs: Long): ByteArray {
        val dts = frames.map { it.ptsMs }.sorted()
        val entries = ByteArrayOutputStream()
        frames.forEachIndexed { i, frame ->
            val duration = if (i + 1 < dts.size) dts[i + 1] - dts[i] else frameMs
            entries.write(u32(duration.coerceAtLeast(1)))
            entries.write(u32(frame.bytes.size.toLong()))
            entries.write(u32(if (frame.key) KEY_SAMPLE else DEPENDENT_SAMPLE))
            entries.write(u32((frame.ptsMs - dts[i]) and 0xFFFFFFFFL))
        }
        val tfhd = Boxes.full("tfhd", 0, TFHD_DEFAULT_BASE_IS_MOOF, u32(TRACK_ID))
        val tfdt = Boxes.full("tfdt", 1, 0, u64(dts.first()))
        val mfhd = Boxes.full("mfhd", 0, 0, u32(sequence.toLong()))
        val trunLength = 8 + 4 + 4 + 4 + entries.size()
        val moofLength = 8 + mfhd.size + 8 + tfhd.size + tfdt.size + trunLength
        val trun = Boxes.full(
            "trun",
            1,
            VIDEO_TRUN_FLAGS,
            u32(frames.size.toLong()) + u32(moofLength + 8L) + entries.toByteArray(),
        )
        val moof = Boxes.box("moof", mfhd + Boxes.box("traf", tfhd + tfdt + trun))
        check(moof.size == moofLength) { "moof is ${moof.size} bytes, expected $moofLength" }
        val payload = frames.sumOf { it.bytes.size }
        val out = ByteArray(moof.size + 8 + payload)
        moof.copyInto(out)
        (u32(8L + payload) + ascii("mdat")).copyInto(out, moof.size)
        var at = moof.size + 8
        frames.forEach { frame ->
            frame.bytes.copyInto(out, at)
            at += frame.bytes.size
        }
        return out
    }

    private fun moov(handler: String, timescale: Long, sampleEntry: ByteArray, width: Int, height: Int): ByteArray {
        val mvhd = Boxes.full(
            "mvhd",
            0,
            0,
            u32(0) + u32(0) + u32(MOVIE_TIMESCALE) + u32(0) + u32(0x00010000) + u16(0x0100) + ByteArray(10) +
                MATRIX + ByteArray(24) + u32(TRACK_ID + 1),
        )
        val tkhd = Boxes.full(
            "tkhd",
            0,
            TKHD_ENABLED_IN_MOVIE,
            u32(0) + u32(0) + u32(TRACK_ID) + u32(0) + u32(0) + ByteArray(8) + u16(0) + u16(0) +
                u16(if (handler == "soun") 0x0100 else 0) + u16(0) + MATRIX + u32(width.toLong() shl 16) +
                u32(height.toLong() shl 16),
        )
        val mdhd = Boxes.full("mdhd", 0, 0, u32(0) + u32(0) + u32(timescale) + u32(0) + u16(LANGUAGE_UND) + u16(0))
        val hdlr = Boxes.full("hdlr", 0, 0, u32(0) + ascii(handler) + ByteArray(12) + ascii("Totum") + byteArrayOf(0))
        val dinf = Boxes.box("dinf", Boxes.full("dref", 0, 0, u32(1) + Boxes.full("url ", 0, 1, ByteArray(0))))
        val stbl = Boxes.box(
            "stbl",
            Boxes.full("stsd", 0, 0, u32(1) + sampleEntry) +
                Boxes.full("stts", 0, 0, u32(0)) +
                Boxes.full("stsc", 0, 0, u32(0)) +
                Boxes.full("stsz", 0, 0, u32(0) + u32(0)) +
                Boxes.full("stco", 0, 0, u32(0)),
        )
        val header = if (handler == "soun") {
            Boxes.full("smhd", 0, 0, u16(0) + u16(0))
        } else {
            Boxes.full("vmhd", 0, 1, ByteArray(8))
        }
        val minf = Boxes.box("minf", header + dinf + stbl)
        val trak = Boxes.box("trak", tkhd + Boxes.box("mdia", mdhd + hdlr + minf))
        val mvex = Boxes.box("mvex", Boxes.full("trex", 0, 0, u32(TRACK_ID) + u32(1) + u32(0) + u32(0) + u32(0)))
        return mvhd + trak + mvex
    }

    private val MATRIX = listOf(0x00010000L, 0, 0, 0, 0x00010000L, 0, 0, 0, 0x40000000L)
        .map(::u32).reduce(ByteArray::plus)
    const val TRACK_ID = 1L
    private const val MOVIE_TIMESCALE = 1000L
    private const val LANGUAGE_UND = 0x55C4
    private const val TKHD_ENABLED_IN_MOVIE = 0x3
    const val TFHD_DEFAULT_BASE_IS_MOOF = 0x020000
    private const val VIDEO_TRUN_FLAGS = 0x000001 or 0x000100 or 0x000200 or 0x000400 or 0x000800
    private const val KEY_SAMPLE = 0x02000000L
    private const val DEPENDENT_SAMPLE = 0x01010000L
    private const val RESOLUTION_72_DPI = 0x00480000L
    private const val DEPTH_COLOUR = 0x0018
}
