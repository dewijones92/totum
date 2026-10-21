@file:Suppress("MagicNumber")

package com.dewijones92.totum.playback.cast

internal object Mp4Layout {

    private class Box(val type: String, val start: Int, val dataStart: Int, val end: Int)

    fun isMp4(head: ByteArray): Boolean = type(head, 4) in setOf("ftyp", "styp", "moov")

    private fun type(bytes: ByteArray, at: Int): String =
        if (at + 4 > bytes.size) "" else String(bytes, at, 4, Charsets.ISO_8859_1)

    private fun u32(bytes: ByteArray, at: Int): Long = Ebml.uint(bytes, at, 4)

    private fun boxes(bytes: ByteArray, from: Int, to: Int): Sequence<Box> = sequence {
        var at = from
        while (at + 8 <= to) {
            var size = u32(bytes, at)
            var header = 8
            if (size == 1L) {
                size = Ebml.uint(bytes, at + 8, 8)
                header = 16
            } else if (size == 0L) {
                size = (to - at).toLong()
            }
            if (size < header) return@sequence
            val type = type(bytes, at + 4)
            yield(Box(type, at, at + header, (at + size).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()))
            at = (at + size).toInt()
        }
    }

    private fun child(bytes: ByteArray, parent: Box, vararg path: String): Box? {
        var current: Box? = parent
        for (name in path) {
            val p = current ?: return null
            current = boxes(bytes, p.dataStart, minOf(p.end, bytes.size)).firstOrNull { it.type == name }
        }
        return current
    }

    @Suppress("ThrowsCount")
    fun read(head: ByteArray): StreamLayout {
        val top = boxes(head, 0, head.size).toList()
        val moov = top.firstOrNull {
            it.type == "moov"
        } ?: throw UnreadableLayout("MP4 has no moov in its first ${head.size} bytes")
        if (moov.end > head.size) throw UnreadableLayout("MP4 moov runs past the first ${head.size} bytes")
        val sidx = top.firstOrNull { it.type == "sidx" }
            ?: throw UnreadableLayout("MP4 has no sidx after its moov, so it cannot be seeked by range")
        val trak = boxes(head, moov.dataStart, moov.end).firstOrNull { it.type == "trak" }
            ?: throw UnreadableLayout("MP4 moov has no track")
        val stsd = child(head, trak, "mdia", "minf", "stbl", "stsd") ?: throw UnreadableLayout("MP4 track has no stsd")
        val entry = boxes(head, stsd.dataStart + STSD_HEADER, stsd.end).firstOrNull()
            ?: throw UnreadableLayout("MP4 stsd has no sample entry")
        val facts = sampleEntry(head, entry)
        return StreamLayout(
            container = Container.MP4,
            codecs = facts.codecs,
            init = ByteSpan(0, moov.end - 1L),
            index = ByteSpan(sidx.start.toLong(), sidx.end - 1L),
            width = facts.width,
            height = facts.height,
            sampleRate = facts.sampleRate,
            channels = facts.channels,
        )
    }

    private data class EntryFacts(
        val codecs: String,
        val width: Int?,
        val height: Int?,
        val sampleRate: Int?,
        val channels: Int?,
    )

    private class Visual(val box: String, val codecs: (ByteArray) -> String?, val fallback: String)

    private val visuals = mapOf(
        "avc1" to Visual("avcC", CodecStrings::avc, "avc1.640028"),
        "avc3" to Visual("avcC", CodecStrings::avc, "avc1.640028"),
        "av01" to Visual("av1C", CodecStrings::av1, "av01.0.08M.08"),
        "vp09" to Visual("vpcC", CodecStrings::vp9, "vp09.00.40.08"),
    )

    private fun inner(bytes: ByteArray, entry: Box, from: Int, name: String): ByteArray? =
        boxes(bytes, from, entry.end).firstOrNull { it.type == name }?.let { bytes.copyOfRange(it.dataStart, it.end) }

    private fun sampleEntry(bytes: ByteArray, entry: Box): EntryFacts {
        val visual = visuals[entry.type] ?: return audioEntry(bytes, entry)
        val at = entry.dataStart + VISUAL_WIDTH
        return EntryFacts(
            codecs = inner(bytes, entry, entry.dataStart + VISUAL_ENTRY, visual.box)?.let(visual.codecs)
                ?: visual.fallback,
            width = Ebml.uint(bytes, at, 2).toInt(),
            height = Ebml.uint(bytes, at + 2, 2).toInt(),
            sampleRate = null,
            channels = null,
        )
    }

    private fun audioEntry(bytes: ByteArray, entry: Box): EntryFacts {
        val codecs = when (entry.type) {
            "mp4a" -> inner(bytes, entry, entry.dataStart + AUDIO_ENTRY, "esds")?.let(CodecStrings::aac) ?: "mp4a.40.2"
            "Opus" -> "opus"
            else -> throw UnreadableLayout("MP4 sample entry ${entry.type} is not one a Cast receiver plays")
        }
        return EntryFacts(
            codecs = codecs,
            width = null,
            height = null,
            sampleRate = (u32(bytes, entry.dataStart + AUDIO_RATE) shr FIXED_POINT).toInt(),
            channels = Ebml.uint(bytes, entry.dataStart + AUDIO_CHANNELS, 2).toInt(),
        )
    }

    private const val STSD_HEADER = 8
    private const val VISUAL_ENTRY = 78
    private const val VISUAL_WIDTH = 24
    private const val AUDIO_ENTRY = 28
    private const val AUDIO_CHANNELS = 16
    private const val AUDIO_RATE = 24
    private const val FIXED_POINT = 16
}
