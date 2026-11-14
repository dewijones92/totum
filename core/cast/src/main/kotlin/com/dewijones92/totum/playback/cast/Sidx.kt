@file:Suppress("MagicNumber")

package com.dewijones92.totum.playback.cast

internal class SidxSegment(val bytes: ByteSpan, val durationUs: Long)

@Suppress("ThrowsCount")
internal object Sidx {

    fun read(box: ByteArray, boxStart: Long): List<SidxSegment> {
        if (box.size < MIN_BOX || Mp4Layout.type(box, 4) != "sidx") {
            throw UnreadableLayout("MP4 index is not a sidx box (found '${Mp4Layout.type(box, 4)}')")
        }
        val version = box[8].toInt()
        var at = 12 + 4
        val timescale = Mp4Layout.u32(box, at).takeIf { it > 0 } ?: throw UnreadableLayout("MP4 sidx has no timescale")
        at += 4
        val widthOfTimes = if (version == 0) 4 else 8
        at += widthOfTimes
        val firstOffset = Ebml.uint(box, at, widthOfTimes)
        at += widthOfTimes + 2
        val count = Ebml.uint(box, at, 2).toInt()
        at += 2
        if (box.size < at + count * ENTRY) throw UnreadableLayout("MP4 sidx lists $count references but is cut short")
        var offset = boxStart + Mp4Layout.u32(box, 0) + firstOffset
        return List(count) { i ->
            val entry = at + i * ENTRY
            val word = Mp4Layout.u32(box, entry)
            if (word and HIERARCHICAL != 0L) {
                throw UnreadableLayout("MP4 sidx points at another sidx; HLS cannot be cut from it")
            }
            val size = word and SIZE_MASK
            val duration = Mp4Layout.u32(box, entry + 4) * MICROS / timescale
            SidxSegment(ByteSpan(offset, offset + size - 1), duration).also { offset += size }
        }
    }

    private const val MIN_BOX = 32
    private const val ENTRY = 12
    private const val HIERARCHICAL = 0x80000000L
    private const val SIZE_MASK = 0x7FFFFFFFL
    private const val MICROS = 1_000_000L
}
