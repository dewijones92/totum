@file:Suppress("MagicNumber", "LongParameterList", "ThrowsCount")

package com.dewijones92.totum.playback.cast

import com.dewijones92.totum.common.Diag
import java.io.IOException

internal class Mp4Video private constructor(
    override val codecs: String,
    override val width: Int,
    override val height: Int,
    private val sampleEntry: ByteArray,
    private val samples: Samples,
    private val segments: List<IntRange>,
    private val frameMs: Long,
    override val durationMs: Long,
) : RemuxedVideo {

    internal class Samples(
        val offset: LongArray,
        val size: IntArray,
        val ptsMs: LongArray,
        val key: BooleanArray,
    )

    override val timeline: List<VideoSegment> = segments.mapIndexed { i, range ->
        val start = startMs(range)
        val end = segments.getOrNull(i + 1)?.let(::startMs) ?: durationMs
        VideoSegment(start, (end - start).coerceAtLeast(frameMs))
    }

    override val describe: String
        get() = "$codecs ${width}x$height MP4, ${samples.size.size} frame(s) in ${segments.size} segment(s), " +
            "remuxed on demand"

    override fun init(): ByteArray = Fmp4.init("vide", MILLIS, sampleEntry, width, height)

    override fun segment(n: Int, read: RangeReader): ByteArray {
        val range = segments[n]
        val first = range.minOf { samples.offset[it] }
        val last = range.maxOf { samples.offset[it] + samples.size[it] } - 1
        val bytes = read.read(ByteSpan(first, last))
        if (bytes.size.toLong() != last - first + 1) {
            throw UnreadableLayout("MP4 segment $n: asked for ${last - first + 1} bytes at $first, got ${bytes.size}")
        }
        val frames = range.map { i ->
            val at = (samples.offset[i] - first).toInt()
            VideoFrame(samples.ptsMs[i], samples.key[i], bytes.copyOfRange(at, at + samples.size[i]))
        }
        return Fmp4.videoSegment(n + 1, frames, frameMs)
    }

    private fun startMs(range: IntRange): Long = range.minOf { samples.ptsMs[it] }

    companion object {
        private const val MILLIS = 1000L
        private const val MIN_SEGMENT_MS = 2_000L
        private const val MAX_TOP_BOXES = 64
        private const val MAX_MOOV_BYTES = 64L * 1024 * 1024
        private const val BOX_HEADER = 16

        sealed interface Choice {
            data class Fragmented(val layout: StreamLayout) : Choice

            data class Remuxed(val video: RemuxedVideo) : Choice

            data object NoVideo : Choice
        }

        fun choose(head: ByteArray, read: RangeReader, label: String): Choice {
            val fragmented = try {
                StreamLayouts.probe(read)
            } catch (e: UnreadableLayout) {
                Diag.log("cast", "$label: an MP4 that is not fragmented (${e.message}); remuxing it on demand")
                null
            }
            if (fragmented != null) return Choice.Fragmented(fragmented)
            return probe(head, read)?.let(Choice::Remuxed) ?: Choice.NoVideo
        }

        fun probe(head: ByteArray, read: RangeReader): RemuxedVideo? {
            val moov = moov(head, read)
            val root = Mp4Layout.Box("moov", 0, 8, moov.size)
            val trak = Mp4Layout.boxes(moov, root.dataStart, root.end).filter { it.type == "trak" }
                .firstOrNull { handler(moov, it) == "vide" } ?: return null
            val mdhd = Mp4Layout.child(moov, trak, "mdia", "mdhd") ?: throw UnreadableLayout("MP4 video has no mdhd")
            val timescale = timescaleOf(moov, mdhd)
            val stbl = Mp4Layout.child(moov, trak, "mdia", "minf", "stbl")
                ?: throw UnreadableLayout("MP4 video has no stbl")
            val stsd = Mp4Layout.child(moov, stbl, "stsd") ?: throw UnreadableLayout("MP4 video has no stsd")
            val entry = Mp4Layout.boxes(moov, stsd.dataStart + Mp4Layout.STSD_HEADER, stsd.end).firstOrNull()
                ?: throw UnreadableLayout("MP4 stsd has no sample entry")
            if (entry.type !in VIDEO_ENTRIES) throw UnreadableLayout("MP4 video is ${entry.type}, not H.264 or HEVC")
            val facts = Mp4Layout.sampleEntry(moov, entry)
            val shift = editShift(moov, trak)
            val samples = SampleTables.read(moov, stbl, timescale, shift)
            if (samples.size.isEmpty()) throw UnreadableLayout("MP4 video track has no samples")
            val frameMs = SampleTables.typicalFrameMs(moov, stbl, timescale)
            val durationMs = samples.ptsMs.max() + frameMs
            val segments = segmentsAt(samples)
            Diag.log(
                "cast",
                "MP4 video ${entry.type} ${facts.width}x${facts.height}: ${samples.size.size} frames, " +
                    "${samples.key.count { it }} keyframes, ${segments.size} segment(s), index ${moov.size / 1024}KB",
            )
            return Mp4Video(
                codecs = facts.codecs,
                width = facts.width ?: 0,
                height = facts.height ?: 0,
                sampleEntry = moov.copyOfRange(entry.start, entry.end),
                samples = samples,
                segments = segments,
                frameMs = frameMs,
                durationMs = durationMs,
            )
        }

        private fun moov(head: ByteArray, read: RangeReader): ByteArray {
            var at = 0L
            repeat(MAX_TOP_BOXES) {
                val header = if (at + BOX_HEADER <= head.size) {
                    head.copyOfRange(at.toInt(), (at + BOX_HEADER).toInt())
                } else {
                    try {
                        read.read(ByteSpan(at, at + BOX_HEADER - 1))
                    } catch (e: IOException) {
                        throw UnreadableLayout("MP4 has no moov: nothing readable at $at (${e.message})", e)
                    }
                }
                if (header.size < 8) throw UnreadableLayout("MP4 has no moov before its end at $at")
                var size = Mp4Layout.u32(header, 0)
                if (size == 1L) size = Ebml.uint(header, 8, 8)
                val type = Mp4Layout.type(header, 4)
                if (size < 8) throw UnreadableLayout("MP4 box $type at $at has size $size")
                if (type == "moov") {
                    if (size > MAX_MOOV_BYTES) throw UnreadableLayout("MP4 moov is $size bytes")
                    return if (at + size <= head.size) {
                        head.copyOfRange(at.toInt(), (at + size).toInt())
                    } else {
                        read.read(ByteSpan(at, at + size - 1)).also {
                            if (it.size.toLong() != size) {
                                throw UnreadableLayout(
                                    "MP4 moov: read ${it.size} of $size bytes"
                                )
                            }
                        }
                    }
                }
                at += size
            }
            throw UnreadableLayout("MP4 has no moov in its first $MAX_TOP_BOXES boxes")
        }

        private fun handler(moov: ByteArray, trak: Mp4Layout.Box): String? =
            Mp4Layout.child(moov, trak, "mdia", "hdlr")?.let { Mp4Layout.type(moov, it.dataStart + 8) }

        private fun timescaleOf(moov: ByteArray, mdhd: Mp4Layout.Box): Long =
            Mp4Layout.u32(moov, mdhd.dataStart + if (moov[mdhd.dataStart].toInt() == 1) 20 else 12)

        private fun editShift(moov: ByteArray, trak: Mp4Layout.Box): Long {
            val elst = Mp4Layout.child(moov, trak, "edts", "elst") ?: return 0
            val version = moov[elst.dataStart].toInt()
            val count = Mp4Layout.u32(moov, elst.dataStart + 4).toInt()
            var at = elst.dataStart + 8
            repeat(count) {
                val mediaTime = if (version == 1) {
                    Ebml.uint(
                        moov,
                        at + 8,
                        8
                    )
                } else {
                    Mp4Layout.u32(moov, at + 4).toInt().toLong()
                }
                if (mediaTime >= 0 && mediaTime != 0xFFFFFFFFL) return mediaTime
                at += if (version == 1) 20 else 12
            }
            return 0
        }

        private fun segmentsAt(samples: Samples): List<IntRange> {
            val keys = samples.key.indices.filter { samples.key[it] }.ifEmpty { listOf(0) }
            val starts = mutableListOf(0)
            keys.drop(1).forEach { key ->
                val since = samples.ptsMs[key] - samples.ptsMs[starts.last()]
                if (since >= MIN_SEGMENT_MS) starts += key
            }
            return starts.mapIndexed { i, start -> start until (starts.getOrNull(i + 1) ?: samples.size.size) }
        }

        private val VIDEO_ENTRIES = setOf("avc1", "avc3", "hvc1", "hev1")
    }
}

internal object SampleTables {

    fun read(moov: ByteArray, stbl: Mp4Layout.Box, timescale: Long, shift: Long): Mp4Video.Samples {
        val sizes = sizes(moov, stbl)
        val count = sizes.size
        val offsets = offsets(moov, stbl, count)
        val dts = decodeTimes(moov, stbl, count)
        val composition = compositionOffsets(moov, stbl, count)
        val sync = Mp4Layout.child(moov, stbl, "stss")
        val key = BooleanArray(count) { sync == null }
        if (sync != null) {
            val entries = Mp4Layout.u32(moov, sync.dataStart + 4).toInt()
            repeat(entries) { k ->
                val number = Mp4Layout.u32(moov, sync.dataStart + 8 + k * 4).toInt()
                if (number in 1..count) key[number - 1] = true
            }
        }
        val pts = LongArray(count) { i -> (dts[i] + composition[i] - shift) * 1000L / timescale }
        return Mp4Video.Samples(offsets, sizes, pts, key)
    }

    fun typicalFrameMs(moov: ByteArray, stbl: Mp4Layout.Box, timescale: Long): Long {
        val stts = Mp4Layout.child(moov, stbl, "stts") ?: return FALLBACK_FRAME_MS
        val entries = Mp4Layout.u32(moov, stts.dataStart + 4).toInt()
        val longest = (0 until entries).maxByOrNull {
            Mp4Layout.u32(moov, stts.dataStart + 8 + it * 8)
        } ?: return FALLBACK_FRAME_MS
        val delta = Mp4Layout.u32(moov, stts.dataStart + 12 + longest * 8)
        return (delta * 1000L / timescale).coerceAtLeast(1)
    }

    private fun sizes(moov: ByteArray, stbl: Mp4Layout.Box): IntArray {
        Mp4Layout.child(moov, stbl, "stsz")?.let { stsz ->
            val uniform = Mp4Layout.u32(moov, stsz.dataStart + 4).toInt()
            val count = Mp4Layout.u32(moov, stsz.dataStart + 8).toInt()
            return IntArray(
                count
            ) { if (uniform != 0) uniform else Mp4Layout.u32(moov, stsz.dataStart + 12 + it * 4).toInt() }
        }
        throw UnreadableLayout("MP4 video has no stsz (compact sample sizes are not read)")
    }

    private fun offsets(moov: ByteArray, stbl: Mp4Layout.Box, count: Int): LongArray {
        val stco = Mp4Layout.child(moov, stbl, "stco")
        val co64 = Mp4Layout.child(moov, stbl, "co64")
        val chunks = when {
            stco != null -> LongArray(Mp4Layout.u32(moov, stco.dataStart + 4).toInt()) {
                Mp4Layout.u32(moov, stco.dataStart + 8 + it * 4)
            }
            co64 != null -> LongArray(Mp4Layout.u32(moov, co64.dataStart + 4).toInt()) {
                Ebml.uint(moov, co64.dataStart + 8 + it * 8, 8)
            }
            else -> throw UnreadableLayout("MP4 video has no chunk offsets")
        }
        val stsc = Mp4Layout.child(moov, stbl, "stsc") ?: throw UnreadableLayout("MP4 video has no stsc")
        val runs = Mp4Layout.u32(moov, stsc.dataStart + 4).toInt()
        val firstChunk = IntArray(runs) { Mp4Layout.u32(moov, stsc.dataStart + 8 + it * 12).toInt() }
        val perChunk = IntArray(runs) { Mp4Layout.u32(moov, stsc.dataStart + 12 + it * 12).toInt() }
        val sizes = sizes(moov, stbl)
        val out = LongArray(count)
        var sample = 0
        for (chunk in chunks.indices) {
            val run = (runs - 1 downTo 0).firstOrNull { firstChunk[it] <= chunk + 1 } ?: 0
            var at = chunks[chunk]
            repeat(perChunk[run]) {
                if (sample < count) {
                    out[sample] = at
                    at += sizes[sample]
                    sample++
                }
            }
        }
        if (sample < count) throw UnreadableLayout("MP4 chunks place $sample of $count samples")
        return out
    }

    private fun decodeTimes(moov: ByteArray, stbl: Mp4Layout.Box, count: Int): LongArray {
        val stts = Mp4Layout.child(moov, stbl, "stts") ?: throw UnreadableLayout("MP4 video has no stts")
        val entries = Mp4Layout.u32(moov, stts.dataStart + 4).toInt()
        val out = LongArray(count)
        var sample = 0
        var time = 0L
        repeat(entries) { k ->
            val run = Mp4Layout.u32(moov, stts.dataStart + 8 + k * 8)
            val delta = Mp4Layout.u32(moov, stts.dataStart + 12 + k * 8)
            repeat(run.toInt()) {
                if (sample < count) out[sample++] = time
                time += delta
            }
        }
        return out
    }

    private fun compositionOffsets(moov: ByteArray, stbl: Mp4Layout.Box, count: Int): LongArray {
        val ctts = Mp4Layout.child(moov, stbl, "ctts") ?: return LongArray(count)
        val version = moov[ctts.dataStart].toInt()
        val entries = Mp4Layout.u32(moov, ctts.dataStart + 4).toInt()
        val out = LongArray(count)
        var sample = 0
        repeat(entries) { k ->
            val run = Mp4Layout.u32(moov, ctts.dataStart + 8 + k * 8).toInt()
            val raw = Mp4Layout.u32(moov, ctts.dataStart + 12 + k * 8)
            val offset = if (version == 1) raw.toInt().toLong() else raw
            repeat(run) { if (sample < count) out[sample++] = offset }
        }
        return out
    }

    private const val FALLBACK_FRAME_MS = 40L
}
