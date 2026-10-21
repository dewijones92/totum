@file:Suppress("MagicNumber")

package com.dewijones92.totum.playback.cast

internal object Ebml {
    const val EBML_HEADER = 0x1A45DFA3L
    const val SEGMENT = 0x18538067L
    const val SEEK_HEAD = 0x114D9B74L
    const val SEEK = 0x4DBBL
    const val SEEK_ID = 0x53ABL
    const val SEEK_POSITION = 0x53ACL
    const val TRACKS = 0x1654AE6BL
    const val TRACK_ENTRY = 0xAEL
    const val CODEC_ID = 0x86L
    const val CODEC_PRIVATE = 0x63A2L
    const val VIDEO = 0xE0L
    const val PIXEL_WIDTH = 0xB0L
    const val PIXEL_HEIGHT = 0xBAL
    const val AUDIO = 0xE1L
    const val SAMPLING_FREQUENCY = 0xB5L
    const val CHANNELS = 0x9FL
    const val CUES = 0x1C53BB6BL
    const val CLUSTER = 0x1F43B675L

    data class Read(val value: Long, val next: Int, val unknown: Boolean = false)

    fun id(bytes: ByteArray, at: Int): Read = vint(bytes, at, keepMarker = true)

    fun size(bytes: ByteArray, at: Int): Read = vint(bytes, at, keepMarker = false)

    @Suppress("ThrowsCount")
    private fun vint(bytes: ByteArray, at: Int, keepMarker: Boolean): Read {
        if (at >= bytes.size) throw UnreadableLayout("EBML read past the end at $at of ${bytes.size}")
        val first = bytes[at].toInt() and 0xFF
        if (first == 0) throw UnreadableLayout("EBML length marker missing at $at")
        var length = 1
        var mask = 0x80
        while (first and mask == 0) {
            mask = mask shr 1
            length++
        }
        if (at + length > bytes.size) throw UnreadableLayout("EBML value at $at runs past ${bytes.size}")
        var value = (if (keepMarker) first else first and (mask - 1)).toLong()
        var allOnes = (first and (mask - 1)) == mask - 1
        for (k in 1 until length) {
            val b = bytes[at + k].toInt() and 0xFF
            if (b != 0xFF) allOnes = false
            value = (value shl 8) or b.toLong()
        }
        return Read(value, at + length, unknown = !keepMarker && allOnes)
    }

    fun uint(bytes: ByteArray, from: Int, length: Int): Long {
        var v = 0L
        for (k in 0 until length) v = (v shl 8) or (bytes[from + k].toLong() and 0xFF)
        return v
    }

    fun float(bytes: ByteArray, from: Int, length: Int): Double = when (length) {
        4 -> java.lang.Float.intBitsToFloat(uint(bytes, from, 4).toInt()).toDouble()
        8 -> java.lang.Double.longBitsToDouble(uint(bytes, from, 8))
        else -> 0.0
    }
}

internal object WebmLayout {

    private class Element(val id: Long, val dataStart: Int, val dataEnd: Int) {
        val length: Int get() = dataEnd - dataStart
    }

    private fun elements(bytes: ByteArray, from: Int, to: Int): Sequence<Element> = sequence {
        var at = from
        while (at < to && at < bytes.size) {
            val id = Ebml.id(bytes, at)
            val size = Ebml.size(bytes, id.next)
            val end = if (size.unknown) to else (size.next + size.value).toInt().coerceAtMost(to)
            yield(Element(id.value, size.next, end))
            if (id.value == Ebml.CLUSTER) return@sequence
            at = end
        }
    }

    @Suppress("ThrowsCount")
    fun read(head: ByteArray, reader: RangeReader): StreamLayout {
        val header = elements(head, 0, head.size).first()
        val segment = elements(head, header.dataEnd, head.size).firstOrNull { it.id == Ebml.SEGMENT }
            ?: throw UnreadableLayout("WebM has no Segment in its first ${head.size} bytes")
        var cuesAt: Long? = null
        var track: TrackFacts? = null
        for (child in elements(head, segment.dataStart, head.size)) {
            when (child.id) {
                Ebml.SEEK_HEAD -> cuesAt = cuesAt ?: seekTarget(head, child, Ebml.CUES)?.let { segment.dataStart + it }
                Ebml.TRACKS -> track = firstTrack(head, child)
                Ebml.CUES -> cuesAt = child.dataStart.toLong() - headerLength(head, child)
            }
        }
        val cues = cuesAt ?: throw UnreadableLayout("WebM names no Cues, so it cannot be seeked by range")
        val facts = track ?: throw UnreadableLayout("WebM has no track entry in its first ${head.size} bytes")
        val cueHead = reader.read(ByteSpan(cues, cues + CUE_HEAD - 1))
        val cueId = Ebml.id(cueHead, 0)
        if (cueId.value != Ebml.CUES) throw UnreadableLayout("WebM seek head points at ${cueId.value} not Cues")
        val cueSize = Ebml.size(cueHead, cueId.next)
        return StreamLayout(
            container = Container.WEBM,
            codecs = facts.codecs,
            init = ByteSpan(0, cues - 1),
            index = ByteSpan(cues, cues + cueSize.next + cueSize.value - 1),
            width = facts.width,
            height = facts.height,
            sampleRate = facts.sampleRate,
            channels = facts.channels,
        )
    }

    private fun headerLength(bytes: ByteArray, element: Element): Int {
        var at = element.dataStart - 1
        while (at > 0) {
            val id = runCatching { Ebml.id(bytes, at) }.getOrNull()
            if (id?.value == element.id) {
                val size = Ebml.size(bytes, id.next)
                if (size.next == element.dataStart) return element.dataStart - at
            }
            at--
        }
        return 0
    }

    private fun seekTarget(bytes: ByteArray, seekHead: Element, wanted: Long): Long? =
        elements(
            bytes,
            seekHead.dataStart,
            seekHead.dataEnd
        ).filter { it.id == Ebml.SEEK }.firstNotNullOfOrNull { seek ->
            var id: Long? = null
            var position: Long? = null
            for (field in elements(bytes, seek.dataStart, seek.dataEnd)) {
                when (field.id) {
                    Ebml.SEEK_ID -> id = Ebml.uint(bytes, field.dataStart, field.dataEnd - field.dataStart)
                    Ebml.SEEK_POSITION -> position = Ebml.uint(bytes, field.dataStart, field.dataEnd - field.dataStart)
                }
            }
            position.takeIf { id == wanted }
        }

    private data class TrackFacts(
        val codecs: String,
        val width: Int?,
        val height: Int?,
        val sampleRate: Int?,
        val channels: Int?,
    )

    private fun firstTrack(bytes: ByteArray, tracks: Element): TrackFacts? {
        val entry = elements(bytes, tracks.dataStart, tracks.dataEnd).firstOrNull { it.id == Ebml.TRACK_ENTRY }
            ?: return null
        val fields = children(bytes, entry)
        val video = fields[Ebml.VIDEO]?.let { children(bytes, it) }.orEmpty()
        val audio = fields[Ebml.AUDIO]?.let { children(bytes, it) }.orEmpty()
        val codecId = fields[Ebml.CODEC_ID]?.let { String(bytes, it.dataStart, it.length, Charsets.US_ASCII) }.orEmpty()
        val private = fields[Ebml.CODEC_PRIVATE]?.let { bytes.copyOfRange(it.dataStart, it.dataEnd) }
        return TrackFacts(
            codecs = codecsFor(codecId, private),
            width = video[Ebml.PIXEL_WIDTH]?.let { Ebml.uint(bytes, it.dataStart, it.length).toInt() },
            height = video[Ebml.PIXEL_HEIGHT]?.let { Ebml.uint(bytes, it.dataStart, it.length).toInt() },
            sampleRate = audio[Ebml.SAMPLING_FREQUENCY]?.let { Ebml.float(bytes, it.dataStart, it.length).toInt() },
            channels = audio[Ebml.CHANNELS]?.let { Ebml.uint(bytes, it.dataStart, it.length).toInt() },
        )
    }

    private fun children(bytes: ByteArray, parent: Element): Map<Long, Element> =
        elements(bytes, parent.dataStart, parent.dataEnd).associateBy { it.id }

    private fun codecsFor(codecId: String, private: ByteArray?): String = when (codecId) {
        "V_VP9" -> "vp9"
        "V_VP8" -> "vp8"
        "V_AV1" -> private?.let(CodecStrings::av1) ?: "av01.0.08M.08"
        "A_OPUS" -> "opus"
        "A_VORBIS" -> "vorbis"
        else -> throw UnreadableLayout("WebM codec $codecId is not one a Cast receiver plays")
    }

    private const val CUE_HEAD = 16L
}

internal object CodecStrings {

    fun av1(av1c: ByteArray): String? {
        if (av1c.size < 3) return null
        val profile = (av1c[1].toInt() and 0xFF) shr 5
        val level = av1c[1].toInt() and 0x1F
        val tier = if ((av1c[2].toInt() and 0x80) != 0) "H" else "M"
        val high = (av1c[2].toInt() and 0x40) != 0
        val twelve = (av1c[2].toInt() and 0x20) != 0
        val depth = when {
            high && twelve -> 12
            high -> 10
            else -> 8
        }
        return "av01.$profile.%02d$tier.%02d".format(level, depth)
    }

    fun avc(avcc: ByteArray): String? =
        if (avcc.size < 4) null else "avc1.%02X%02X%02X".format(avcc[1], avcc[2], avcc[3])

    fun vp9(vpcc: ByteArray): String? {
        if (vpcc.size < 7) return null
        val profile = vpcc[4].toInt() and 0xFF
        val level = vpcc[5].toInt() and 0xFF
        val depth = (vpcc[6].toInt() and 0xFF) shr 4
        return "vp09.%02d.%02d.%02d".format(profile, level, depth)
    }

    fun hevc(entry: String, hvcc: ByteArray): String? {
        if (hvcc.size < 13) return null
        val first = hvcc[1].toInt() and 0xFF
        val space = listOf("", "A", "B", "C")[first shr 6]
        val tier = if ((first and 0x20) != 0) "H" else "L"
        val profile = first and 0x1F
        val compat = ((hvcc[2].toLong() and 0xFF) shl 24) or ((hvcc[3].toLong() and 0xFF) shl 16) or
            ((hvcc[4].toLong() and 0xFF) shl 8) or (hvcc[5].toLong() and 0xFF)
        val reversed = Integer.reverse(compat.toInt()).toLong() and 0xFFFFFFFFL
        val constraints = (6..11).map { hvcc[it].toInt() and 0xFF }.dropLastWhile { it == 0 }
        val level = hvcc[12].toInt() and 0xFF
        return "$entry.$space$profile.${reversed.toString(16).uppercase()}.$tier$level" +
            constraints.joinToString("") { ".%X".format(it) }
    }

    const val HEVC_FALLBACK = "hev1.1.6.L120.90"

    fun aac(esds: ByteArray): String {
        val config = descriptorBody(esds, DECODER_SPECIFIC_TAG)
        val objectType = config?.firstOrNull()?.let { (it.toInt() and 0xFF) shr 3 } ?: AAC_LC
        return "mp4a.40.$objectType"
    }

    private fun descriptorBody(bytes: ByteArray, tag: Int): ByteArray? {
        var at = 0
        while (at < bytes.size - 2) {
            if ((bytes[at].toInt() and 0xFF) == tag) {
                var length = 0
                var k = at + 1
                repeat(4) {
                    if (k >= bytes.size) return null
                    val b = bytes[k++].toInt() and 0xFF
                    length = (length shl 7) or (b and 0x7F)
                    if (b and 0x80 == 0) return bytes.copyOfRange(k, minOf(bytes.size, k + length))
                }
            }
            at++
        }
        return null
    }

    private const val DECODER_SPECIFIC_TAG = 0x05
    private const val AAC_LC = 2
}
