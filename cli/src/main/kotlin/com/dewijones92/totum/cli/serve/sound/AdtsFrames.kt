package com.dewijones92.totum.cli.serve.sound

import java.io.InputStream

internal object AdtsFrames {
    private const val HEADER = 7
    private const val LENGTH_HIGH_AT = 3
    private const val LENGTH_MID_AT = 4
    private const val LENGTH_LOW_AT = 5
    private const val BYTE = 0xFF
    private const val SYNC_LOW_MASK = 0xF0
    private const val LENGTH_HIGH_MASK = 0x03
    private const val HIGH_SHIFT = 11
    private const val MID_SHIFT = 3
    private const val LOW_SHIFT = 5
    private const val NO_CRC_BIT = 0x01
    private const val CRC_BYTES = 2

    fun of(bytes: ByteArray): List<ByteArray> = read(bytes.inputStream()).toList()

    fun read(input: InputStream): Sequence<ByteArray> = sequence {
        while (true) {
            val header = input.readNBytes(HEADER)
            if (header.size < HEADER) return@sequence
            val sync = header[0].toInt() and BYTE == BYTE && header[1].toInt() and SYNC_LOW_MASK == SYNC_LOW_MASK
            check(sync) { "not an ADTS frame" }
            val length = ((header[LENGTH_HIGH_AT].toInt() and LENGTH_HIGH_MASK) shl HIGH_SHIFT) or
                ((header[LENGTH_MID_AT].toInt() and BYTE) shl MID_SHIFT) or
                ((header[LENGTH_LOW_AT].toInt() and BYTE) ushr LOW_SHIFT)
            val crc = if (header[1].toInt() and NO_CRC_BIT == 0) CRC_BYTES else 0
            val body = input.readNBytes(length - HEADER)
            if (body.size < length - HEADER) return@sequence
            yield(body.copyOfRange(crc, body.size))
        }
    }
}
