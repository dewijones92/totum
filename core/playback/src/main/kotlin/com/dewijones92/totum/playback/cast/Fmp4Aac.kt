@file:Suppress("MagicNumber")

package com.dewijones92.totum.playback.cast

import java.io.ByteArrayOutputStream

internal class AacFormat(val sampleRate: Int, val channels: Int, val audioSpecificConfig: ByteArray) {
    val codecs: String get() = "mp4a.40.${((audioSpecificConfig.firstOrNull()?.toInt() ?: 0x10) and 0xFF) shr 3}"
}

internal object Fmp4Aac {

    const val SAMPLES_PER_FRAME: Int = 1024

    fun init(format: AacFormat): ByteArray = Boxes.concat(
        Boxes.box("ftyp", ascii("iso6") + u32(0) + ascii("iso6") + ascii("mp41") + ascii("dash")),
        Boxes.box("moov", moov(format)),
    )

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

    private fun moov(format: AacFormat): ByteArray {
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
            u32(0) + u32(0) + u32(TRACK_ID) + u32(0) + u32(0) + ByteArray(8) + u16(0) + u16(0) + u16(0x0100) + u16(0) +
                MATRIX + u32(0) + u32(0),
        )
        val mdhd = Boxes.full(
            "mdhd",
            0,
            0,
            u32(0) + u32(0) + u32(format.sampleRate.toLong()) + u32(0) + u16(LANGUAGE_UND) + u16(0)
        )
        val hdlr = Boxes.full("hdlr", 0, 0, u32(0) + ascii("soun") + ByteArray(12) + ascii("Totum") + byteArrayOf(0))
        val dinf = Boxes.box("dinf", Boxes.full("dref", 0, 0, u32(1) + Boxes.full("url ", 0, 1, ByteArray(0))))
        val stbl = Boxes.box(
            "stbl",
            Boxes.full("stsd", 0, 0, u32(1) + mp4a(format)) +
                Boxes.full("stts", 0, 0, u32(0)) +
                Boxes.full("stsc", 0, 0, u32(0)) +
                Boxes.full("stsz", 0, 0, u32(0) + u32(0)) +
                Boxes.full("stco", 0, 0, u32(0)),
        )
        val minf = Boxes.box("minf", Boxes.full("smhd", 0, 0, u16(0) + u16(0)) + dinf + stbl)
        val trak = Boxes.box("trak", tkhd + Boxes.box("mdia", mdhd + hdlr + minf))
        val mvex = Boxes.box(
            "mvex",
            Boxes.full("trex", 0, 0, u32(TRACK_ID) + u32(1) + u32(SAMPLES_PER_FRAME.toLong()) + u32(0) + u32(0))
        )
        return mvhd + trak + mvex
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

    private val MATRIX = listOf(0x00010000L, 0, 0, 0, 0x00010000L, 0, 0, 0, 0x40000000L)
        .map(::u32).reduce(ByteArray::plus)
    private const val TRACK_ID = 1L
    private const val MOVIE_TIMESCALE = 1000L
    private const val LANGUAGE_UND = 0x55C4
    private const val TKHD_ENABLED_IN_MOVIE = 0x3
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
