package com.dewijones92.totum.sabr

/**
 * Frames UMP parts the way YouTube does, for tests that need realistic responses.
 *
 * Shared because hand-rolling it per test file goes wrong quietly. The first version in
 * `ClaimedTimeFollowsTheBytesTest` encoded only the two-byte width — fine for the three-byte
 * payloads the older tests use, and silently corrupt for the 64KB runs a realistic stream carries.
 * The symptom was not a framing error but `100% wasted`: every byte parsed, attributed to nothing,
 * discarded. A test fixture that produces garbage the code correctly rejects looks exactly like the
 * code being broken.
 *
 * So this always uses the FIVE-byte form: first byte `0xF0`, then a little-endian int32. It is the
 * one width that fits anything, and [UmpVarint] documents it as the case whose first byte carries no
 * value at all — which is why it is safe to use uniformly.
 */
internal object UmpFraming {

    /** One UMP part: its type, its length, its payload — each length in the 5-byte width. */
    fun part(type: Int, payload: ByteArray): ByteArray =
        varint(type.toLong()) + varint(payload.size.toLong()) + payload

    /** A `MEDIA_HEADER` declaring [format]'s run [id], starting at [offset] and [length] long. */
    fun mediaHeader(id: Int, format: SabrFormat, offset: Long, length: Int): ByteArray = part(
        UmpPart.MEDIA_HEADER,
        Protobuf.number(HEADER_ID, id.toLong()) +
            Protobuf.number(HEADER_ITAG, format.itag.toLong()) +
            Protobuf.number(HEADER_OFFSET, offset) +
            Protobuf.number(HEADER_LENGTH, length.toLong()),
    )

    /** A `MEDIA` part. Its payload begins with the run id, which is how runs are attributed. */
    fun media(id: Int, payload: ByteArray): ByteArray =
        part(UmpPart.MEDIA, byteArrayOf(id.toByte()) + payload)

    /** One run of [size] bytes at [offset]: the header that declares it, then the bytes. */
    fun run(format: SabrFormat, offset: Long, size: Int, id: Int = 0, fill: Byte = 7): ByteArray =
        mediaHeader(id, format, offset, size) + media(id, ByteArray(size) { fill })

    /** The five-byte UMP width: `0xF0`, then a little-endian int32. */
    private fun varint(value: Long): ByteArray = byteArrayOf(
        FIVE_BYTE_MARKER.toByte(),
        (value and BYTE_MASK).toByte(),
        ((value shr 8) and BYTE_MASK).toByte(),
        ((value shr 16) and BYTE_MASK).toByte(),
        ((value shr 24) and BYTE_MASK).toByte(),
    )

    private const val FIVE_BYTE_MARKER = 0xF0
    private const val BYTE_MASK = 0xFFL

    private const val HEADER_ID = 1
    private const val HEADER_ITAG = 3
    private const val HEADER_OFFSET = 6
    private const val HEADER_LENGTH = 14
}
