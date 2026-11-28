package com.dewijones92.totum.playback.cast

import java.io.ByteArrayOutputStream

internal object MkvFixtures {

    enum class Order(val seekHead: Boolean) {
        TRACKS_BEHIND_A_BIG_VOID(seekHead = true),
        TRACKS_AFTER_THE_CLUSTERS(seekHead = true),
        NO_SEEK_HEAD_TRACKS_BEHIND_A_BIG_VOID(seekHead = false),
    }

    private const val SEGMENT = 0x18538067L
    private const val SEEK_HEAD = 0x114D9B74L
    private const val SEEK = 0x4DBBL
    private const val SEEK_ID = 0x53ABL
    private const val SEEK_POSITION = 0x53ACL
    private const val INFO = 0x1549A966L
    private const val TRACKS = 0x1654AE6BL
    private const val CLUSTER = 0x1F43B675L
    private const val CUES = 0x1C53BB6BL
    private const val VOID = 0xECL
    private const val CUE_POINT = 0xBBL
    private const val CUE_TIME = 0xB3L
    private const val CUE_TRACK_POSITIONS = 0xB7L
    private const val CUE_TRACK = 0xF7L
    private const val CUE_CLUSTER_POSITION = 0xF1L
    private const val BIG_VOID = 70_000
    private const val SMALL_VOID = 20
    private const val EBML_HEADER_ID = 0x1A45DFA3L

    private class Raw(val id: Long, val bytes: ByteArray, val dataStart: Int)

    private fun idBytes(id: Long): ByteArray {
        var length = 1
        while (id ushr (8 * length) != 0L) length++
        return ByteArray(length) { k -> (id ushr (8 * (length - 1 - k))).toByte() }
    }

    private fun size8(length: Long): ByteArray =
        byteArrayOf(0x01) + ByteArray(7) { k -> (length ushr (8 * (6 - k))).toByte() }

    private fun element(id: Long, payload: ByteArray): ByteArray = idBytes(id) + size8(payload.size.toLong()) + payload

    private fun uint8(value: Long): ByteArray = ByteArray(8) { k -> (value ushr (8 * (7 - k))).toByte() }

    private fun children(bytes: ByteArray, from: Int, to: Int): List<Raw> {
        val out = mutableListOf<Raw>()
        var at = from
        while (at < to) {
            val id = Ebml.id(bytes, at)
            val size = Ebml.size(bytes, id.next)
            val end = size.next + size.value.toInt()
            out += Raw(id.value, bytes.copyOfRange(at, end), size.next - at)
            at = end
        }
        return out
    }

    private class Source(val ebmlHeaderLength: Int, val segmentStart: Int, val parts: List<Raw>)

    private fun segmentOf(file: ByteArray): Source {
        val header = Ebml.children(file, 0, file.size).first()
        require(Ebml.id(file, 0).value == EBML_HEADER_ID)
        val segment = Ebml.children(file, header.dataEnd, file.size).first { it.id == SEGMENT }
        return Source(header.dataEnd, segment.dataStart, children(file, segment.dataStart, file.size))
    }

    fun relayout(file: ByteArray, order: Order): ByteArray {
        val source = segmentOf(file)
        val parts = source.parts
        val clusters = parts.filter { it.id == CLUSTER }
        val info = parts.single { it.id == INFO }
        val tracks = parts.single { it.id == TRACKS }
        val firstCluster = parts.takeWhile { it.id != CLUSTER }.sumOf { it.bytes.size }
        val oldClusterAt = clusters.runningFold(firstCluster) { at, c -> at + c.bytes.size }.dropLast(1)
        val cuesOld = parts.single { it.id == CUES }

        val bodies = when (order) {
            Order.TRACKS_BEHIND_A_BIG_VOID, Order.NO_SEEK_HEAD_TRACKS_BEHIND_A_BIG_VOID ->
                listOf("void" to element(VOID, ByteArray(BIG_VOID)), "info" to info.bytes, "tracks" to tracks.bytes) +
                    clusters.mapIndexed { i, c -> "cluster$i" to c.bytes }
            Order.TRACKS_AFTER_THE_CLUSTERS ->
                listOf("void" to element(VOID, ByteArray(SMALL_VOID))) +
                    clusters.mapIndexed { i, c -> "cluster$i" to c.bytes } +
                    listOf("info" to info.bytes, "tracks" to tracks.bytes)
        }
        val seekHeadLength = if (order.seekHead) seekHead(0, 0, 0).size else 0

        var at = seekHeadLength.toLong()
        val place = mutableMapOf<String, Long>()
        bodies.forEach { (name, bytes) ->
            place[name] = at
            at += bytes.size
        }
        place["cues"] = at
        val newClusterAt = clusters.indices.associate { i -> oldClusterAt[i].toLong() to place.getValue("cluster$i") }
        val cues = element(CUES, rebuiltCuePoints(cuesOld, newClusterAt))

        val out = ByteArrayOutputStream()
        if (order.seekHead) {
            out.write(seekHead(place.getValue("info"), place.getValue("tracks"), place.getValue("cues")))
        }
        bodies.forEach { out.write(it.second) }
        out.write(cues)
        return file.copyOfRange(0, source.ebmlHeaderLength) + element(SEGMENT, out.toByteArray())
    }

    private fun seekHead(info: Long, tracks: Long, cues: Long): ByteArray = element(
        SEEK_HEAD,
        listOf(INFO to info, TRACKS to tracks, CUES to cues).map { (id, position) ->
            element(SEEK, element(SEEK_ID, idBytes(id)) + element(SEEK_POSITION, uint8(position)))
        }.reduce(ByteArray::plus),
    )

    private fun rebuiltCuePoints(cues: Raw, newPositions: Map<Long, Long>): ByteArray =
        children(cues.bytes, cues.dataStart, cues.bytes.size).filter { it.id == CUE_POINT }.map { point ->
            val fields = children(point.bytes, point.dataStart, point.bytes.size)
            val time = fields.single { it.id == CUE_TIME }
            val positions = fields.filter { it.id == CUE_TRACK_POSITIONS }.map { p ->
                val inner = children(p.bytes, p.dataStart, p.bytes.size)
                val track = inner.single { it.id == CUE_TRACK }
                val old = inner.single { it.id == CUE_CLUSTER_POSITION }
                val oldValue = Ebml.uint(old.bytes, old.dataStart, old.bytes.size - old.dataStart)
                element(
                    CUE_TRACK_POSITIONS,
                    track.bytes + element(CUE_CLUSTER_POSITION, uint8(newPositions.getValue(oldValue))),
                )
            }
            element(CUE_POINT, time.bytes + positions.reduce(ByteArray::plus))
        }.reduce(ByteArray::plus)

    fun positions(file: ByteArray): Map<String, LongRange> {
        val source = segmentOf(file)
        var at = source.segmentStart.toLong()
        val found = mutableMapOf<String, LongRange>()
        source.parts.forEach { part ->
            val name = when (part.id) {
                INFO -> "info"
                TRACKS -> "tracks"
                CUES -> "cues"
                else -> null
            }
            if (name != null) found[name] = at until at + part.bytes.size
            at += part.bytes.size
        }
        return found
    }
}
