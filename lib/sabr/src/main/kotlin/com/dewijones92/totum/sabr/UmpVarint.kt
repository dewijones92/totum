package com.dewijones92.totum.sabr

/**
 * UMP's variable-length integer, which is NOT protobuf's varint and must not be confused with
 * it — the two appear within bytes of each other in a SABR response.
 *
 * The width is declared by how many high bits of the FIRST byte are set, and the bytes after
 * it are little-endian:
 *
 * | First byte | Width | Value |
 * |---|---|---|
 * | `0xxxxxxx` | 1 | the byte itself |
 * | `10xxxxxx` | 2 | its low 6 bits, then 1 byte shifted 6 |
 * | `110xxxxx` | 3 | its low 5 bits, then 2 bytes shifted 5, 13 |
 * | `1110xxxx` | 4 | its low 4 bits, then 3 bytes shifted 4, 12, 20 |
 * | `11110xxx` | 5 | first byte DISCARDED; the next 4 are a little-endian int32 |
 *
 * The five-byte case is the trap: its first byte contributes nothing at all, unlike every
 * other width. `11111xxx` is invalid, and treated as corrupt rather than guessed at.
 */
public object UmpVarint {

    /** Decoded value and the index just past it. */
    public data class Read(val value: Long, val next: Int)

    /**
     * How one width decodes: which bits of the first byte survive, and what each following
     * byte is shifted by. A table rather than a `when` chain, so the shifts sit beside the
     * width they belong to instead of being scattered across branches — and so the five-byte
     * exception is expressed as a mask of zero rather than as a special case in prose.
     */
    private class Layout(val firstByteMask: Int, val shifts: IntArray) {
        val width: Int get() = shifts.size + 1
    }

    private val layouts = listOf(
        Layout(firstByteMask = 0x7F, shifts = intArrayOf()),
        Layout(firstByteMask = 0x3F, shifts = intArrayOf(6)),
        Layout(firstByteMask = 0x1F, shifts = intArrayOf(5, 13)),
        Layout(firstByteMask = 0x0F, shifts = intArrayOf(4, 12, 20)),
        Layout(firstByteMask = 0x00, shifts = intArrayOf(0, 8, 16, 24)),
    )

    /** Null when [at] is out of range or the encoding is invalid — a corrupt stream, not a zero. */
    public fun read(buffer: ByteArray, at: Int): Read? {
        if (at !in buffer.indices) return null
        val first = buffer[at].toInt() and BYTE_MASK
        val layout = layouts.getOrNull(leadingSetBits(first)) ?: return null
        if (at + layout.width > buffer.size) return null
        var value = (first and layout.firstByteMask).toLong()
        layout.shifts.forEachIndexed { offset, shift ->
            value = value or ((buffer[at + 1 + offset].toInt() and BYTE_MASK).toLong() shl shift)
        }
        return Read(value, at + layout.width)
    }

    /** The count of leading set bits, which IS the width minus one; too many means invalid. */
    private fun leadingSetBits(first: Int): Int {
        var count = 0
        var probe = HIGH_BIT
        while (probe != 0 && first and probe != 0) {
            count++
            probe = probe shr 1
        }
        return count
    }

    private const val BYTE_MASK = 0xFF
    private const val HIGH_BIT = 0x80
}
