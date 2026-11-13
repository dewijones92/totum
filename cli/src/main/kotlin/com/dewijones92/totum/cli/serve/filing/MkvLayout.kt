package com.dewijones92.totum.cli.serve.filing

import com.dewijones92.totum.common.Diag
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile

enum class MkvLayout {
    TRACKS_FIRST,
    TRACKS_AFTER_CLUSTERS,
    NOT_MKV,
    UNREADABLE,
    ;

    companion object {
        private const val EBML = 0x1A45DFA3L
        private const val SEGMENT = 0x18538067L
        private const val TRACKS = 0x1654AE6BL
        private const val CLUSTER = 0x1F43B675L
        private const val MAX_ELEMENTS = 100_000
        private const val MAX_ID_BYTES = 4
        private const val MAX_SIZE_BYTES = 8
        private const val UNKNOWN_SIZE = -1L
        private const val BYTE_MASK = 0xFF

        fun of(file: File): MkvLayout = try {
            RandomAccessFile(file, "r").use(::layoutOf)
        } catch (e: IOException) {
            Diag.warn("filing", "${file.name}: the matroska layout could not be read", e)
            UNREADABLE
        }

        private fun layoutOf(input: RandomAccessFile): MkvLayout {
            val head = header(input)
            if (head == null || head.id != EBML || head.size < 0) return NOT_MKV
            skip(input, head.size)
            val segment = header(input) ?: return UNREADABLE
            return if (segment.id != SEGMENT) NOT_MKV else firstOf(input, endOf(input, segment))
        }

        private fun skip(input: RandomAccessFile, bytes: Long) = input.seek(input.filePointer + bytes)

        private fun endOf(input: RandomAccessFile, segment: Header): Long =
            if (segment.size < 0) input.length() else minOf(input.length(), input.filePointer + segment.size)

        private fun firstOf(input: RandomAccessFile, end: Long): MkvLayout {
            repeat(MAX_ELEMENTS) {
                if (input.filePointer >= end) return UNREADABLE
                val child = header(input) ?: return UNREADABLE
                when {
                    child.id == TRACKS -> return TRACKS_FIRST
                    child.id == CLUSTER -> return TRACKS_AFTER_CLUSTERS
                    child.size < 0 -> return UNREADABLE
                    else -> skip(input, child.size)
                }
            }
            return UNREADABLE
        }

        private class Header(val id: Long, val size: Long)

        private class Vint(val value: Long, val unknown: Boolean)

        private fun header(input: RandomAccessFile): Header? {
            val id = vint(input, keepMarker = true, MAX_ID_BYTES) ?: return null
            val size = vint(input, keepMarker = false, MAX_SIZE_BYTES) ?: return null
            return Header(id.value, if (size.unknown) UNKNOWN_SIZE else size.value)
        }

        private fun vint(input: RandomAccessFile, keepMarker: Boolean, maxBytes: Int): Vint? {
            val first = input.read()
            if (first < 0) return null
            val length = Integer.numberOfLeadingZeros(first) - (Int.SIZE_BITS - Byte.SIZE_BITS) + 1
            if (length !in 1..maxBytes) return null
            val payloadMask = BYTE_MASK shr length
            var value = if (keepMarker) first.toLong() else (first and payloadMask).toLong()
            var allOnes = (first and payloadMask) == payloadMask
            repeat(length - 1) {
                val next = input.read()
                if (next < 0) return null
                if (next != BYTE_MASK) allOnes = false
                value = (value shl Byte.SIZE_BITS) or next.toLong()
            }
            return Vint(value, unknown = allOnes && !keepMarker)
        }
    }
}
