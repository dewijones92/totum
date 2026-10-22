@file:Suppress("MagicNumber")

package com.dewijones92.totum.playback.cast

import java.io.ByteArrayOutputStream

internal class AacFormat(val sampleRate: Int, val channels: Int, val audioSpecificConfig: ByteArray) {
    val codecs: String get() = "mp4a.40.${((audioSpecificConfig.firstOrNull()?.toInt() ?: 0x10) and 0xFF) shr 3}"
}

internal object Fmp4Aac {

    const val SAMPLES_PER_FRAME: Int = 1024

    fun init(format: AacFormat): ByteArray = Fmp4.init("soun", format.sampleRate.toLong(), mp4a(format), 0, 0)

    fun segment(sequence: Int, baseDecodeTime: Long, frames: List<ByteArray>): ByteArray {
        val trunEntries = frames.size
        val trunBodyLength = 4 + 4 + 4 + trunEntries * 8
        val tfhd = Boxes.full("tfhd", 0, TFHD_DEFAULT_BASE_IS_MOOF, u32(TRACK_ID))
        val tfdt = Boxes.full("tfdt", 1, 0, u64(baseDecodeTime))
        val mfhd = Boxes.full("mfhd", 0, 0, u32(sequence.toLong()))
        val trunHeaderLength = 8 + trunBodyLength
        val trafLength = 8 + tfhd.size + tfdt.size + trunHeaderLength
        val moofLength = 8 + mfhd.size + trafLength
        val dataOffset = moofLength + 8
        val trun = ByteArrayOutputStream().apply {
            write(u32(trunEntries.toLong()))
            write(u32(dataOffset.toLong()))
            frames.forEach {
                write(u32(SAMPLES_PER_FRAME.toLong()))
                write(u32(it.size.toLong()))
            }
        }.toByteArray()
        val moof = Boxes.box("moof", mfhd + Boxes.box("traf", tfhd + tfdt + Boxes.full("trun", 0, TRUN_FLAGS, trun)))
        check(moof.size == moofLength) { "moof is ${moof.size} bytes, expected $moofLength" }
        val mdat = Boxes.box("mdat", frames.reduce(ByteArray::plus))
        return moof + mdat
    }

    private fun mp4a(format: AacFormat): ByteArray = Boxes.box(
        "mp4a",
        ByteArray(6) + u16(1) + ByteArray(8) + u16(format.channels) + u16(16) + u16(0) + u16(0) +
            u32(format.sampleRate.toLong() shl 16) + esds(format),
    )

    private fun esds(format: AacFormat): ByteArray {
        val specific = descriptor(0x05, format.audioSpecificConfig)
        val config = descriptor(0x04, byteArrayOf(0x40, 0x15) + ByteArray(3) + u32(0) + u32(0) + specific)
        val sl = descriptor(0x06, byteArrayOf(0x02))
        return Boxes.full("esds", 0, 0, descriptor(0x03, u16(1) + byteArrayOf(0) + config + sl))
    }

    private fun descriptor(tag: Int, body: ByteArray): ByteArray =
        byteArrayOf(tag.toByte(), 0x80.toByte(), 0x80.toByte(), 0x80.toByte(), body.size.toByte()) + body

    private const val TRACK_ID = 1L
    private const val TFHD_DEFAULT_BASE_IS_MOOF = 0x020000
    private const val TRUN_FLAGS = 0x000001 or 0x000100 or 0x000200
}

internal object Boxes {
    fun box(type: String, body: ByteArray): ByteArray = u32(8L + body.size) + ascii(type) + body

    fun full(type: String, version: Int, flags: Int, body: ByteArray): ByteArray =
        box(type, byteArrayOf(version.toByte(), (flags shr 16).toByte(), (flags shr 8).toByte(), flags.toByte()) + body)

    fun concat(vararg parts: ByteArray): ByteArray = ByteArrayOutputStream().apply {
        parts.forEach(
            ::write
        )
    }.toByteArray()
}

internal fun u16(v: Int): ByteArray = byteArrayOf((v shr 8).toByte(), v.toByte())

internal fun u32(v: Long): ByteArray = byteArrayOf(
    (v shr 24).toByte(),
    (v shr 16).toByte(),
    (v shr 8).toByte(),
    v.toByte()
)

internal fun u64(v: Long): ByteArray = u32(v ushr 32) + u32(v and 0xFFFFFFFFL)

internal fun ascii(s: String): ByteArray = s.toByteArray(Charsets.US_ASCII)
