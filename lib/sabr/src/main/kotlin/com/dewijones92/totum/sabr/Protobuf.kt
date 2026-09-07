package com.dewijones92.totum.sabr

/**
 * Just enough protobuf to write a `VideoPlaybackAbrRequest` by hand.
 *
 * Hand-rolled rather than generated, deliberately. The schema is Google's private one: it has
 * no public `.proto` we can depend on, its field names are largely unknown (`field6`,
 * `field21`, `field1000` in the reverse-engineered version), and a code generator plus its
 * runtime would be a build-time dependency and an APK cost for what turns out to be a handful
 * of length-delimited fields.
 *
 * NOT to be confused with [UmpVarint], which sits inches away in the same response and encodes
 * differently — protobuf varints are 7 bits per byte with a continuation flag, UMP's are
 * width-prefixed and little-endian. Mixing them up produces plausible garbage rather than an
 * error, which is exactly the kind of bug that costs a day.
 */
internal object Protobuf {

    private const val WIRE_VARINT = 0
    private const val WIRE_LENGTH_DELIMITED = 2
    private const val WIRE_FIXED64 = 1
    internal const val WIRE_FIXED32 = 5
    private const val WIRE_TYPE_MASK = 0x07L
    private const val BYTE_MASK = 0xFF
    private const val MAX_SHIFT = 63
    private const val CONTINUATION = 0x80
    private const val SEVEN_BITS = 0x7F
    private const val SHIFT = 7

    /** Protobuf packs the field number above the 3-bit wire type. */
    private const val WIRE_TYPE_BITS = 3

    /** Protobuf's own varint: 7 bits per byte, low first, high bit set while more follow. */
    fun varint(value: Long): ByteArray {
        var remaining = value
        val out = ArrayList<Byte>()
        do {
            val chunk = (remaining and SEVEN_BITS.toLong()).toInt()
            remaining = remaining ushr SHIFT
            out += (if (remaining != 0L) chunk or CONTINUATION else chunk).toByte()
        } while (remaining != 0L)
        return out.toByteArray()
    }

    internal fun tag(field: Int, wireType: Int) = varint(((field shl WIRE_TYPE_BITS) or wireType).toLong())

    /** A length-delimited field: bytes, a string, or a nested message. */
    fun bytes(field: Int, value: ByteArray): ByteArray =
        tag(field, WIRE_LENGTH_DELIMITED) + varint(value.size.toLong()) + value

    fun number(field: Int, value: Long): ByteArray = tag(field, WIRE_VARINT) + varint(value)

    /**
     * A shallow read of [buffer] into field number → values.
     *
     * Shallow on purpose: nested messages come back as raw bytes for the caller to read again
     * if it cares. Going deeper automatically would mean guessing which fields are messages,
     * and in a schema this is largely unknown, a wrong guess produces plausible nonsense.
     *
     * A field that cannot be read stops the scan and keeps what came before, because a
     * response is more useful partially understood than discarded — YouTube adds fields we
     * have never seen, and one of them must not cost us the whole header.
     */
    fun read(buffer: ByteArray): Map<Int, List<Value>> {
        val fields = mutableMapOf<Int, MutableList<Value>>()
        var at = 0
        while (at < buffer.size) {
            at = readOneField(buffer, at, fields) ?: break
        }
        return fields
    }

    /** The offset past one field, having recorded it, or null when it cannot be read. */
    private fun readOneField(
        buffer: ByteArray,
        at: Int,
        into: MutableMap<Int, MutableList<Value>>,
    ): Int? {
        val key = readVarint(buffer, at) ?: return null
        val field = (key.value shr WIRE_TYPE_BITS).toInt()
        fun record(value: Value) = into.getOrPut(field) { mutableListOf() }.add(value)
        return when ((key.value and WIRE_TYPE_MASK).toInt()) {
            WIRE_VARINT -> readVarint(buffer, key.next)?.let {
                record(Value.Number(it.value))
                it.next
            }
            WIRE_LENGTH_DELIMITED -> readLengthDelimited(buffer, key.next)?.let { (value, after) ->
                record(Value.Bytes(value))
                after
            }
            // Skipped rather than recorded: nothing we read uses fixed-width fields, and
            // stepping over one is what keeps an unknown field from ending the scan.
            WIRE_FIXED64 -> (key.next + Long.SIZE_BYTES).takeIf { it <= buffer.size }
            WIRE_FIXED32 -> (key.next + Int.SIZE_BYTES).takeIf { it <= buffer.size }
            else -> null
        }
    }

    /** A read field: a number, or length-delimited bytes (which may be a nested message). */
    sealed interface Value {
        data class Number(val value: Long) : Value
        data class Bytes(val value: ByteArray) : Value {
            override fun equals(other: Any?): Boolean =
                this === other || (other is Bytes && value.contentEquals(other.value))

            override fun hashCode(): Int = value.contentHashCode()
        }
    }

    private data class Read(val value: Long, val next: Int)

    private fun readVarint(buffer: ByteArray, at: Int): Read? {
        var value = 0L
        var shift = 0
        var index = at
        while (index < buffer.size) {
            val byte = buffer[index].toInt() and BYTE_MASK
            value = value or ((byte and SEVEN_BITS).toLong() shl shift)
            index++
            if (byte and CONTINUATION == 0) return Read(value, index)
            shift += SHIFT
            if (shift > MAX_SHIFT) return null
        }
        return null
    }

    /** Bounded as a LONG before it is narrowed, for the reason [UmpReader] records. */
    private fun readLengthDelimited(buffer: ByteArray, at: Int): Pair<ByteArray, Int>? {
        val length = readVarint(buffer, at) ?: return null
        if (length.value < 0 || length.value > (buffer.size - length.next).toLong()) return null
        val end = length.next + length.value.toInt()
        return buffer.copyOfRange(length.next, end) to end
    }

    /** Convenience: the first number at [field], or null. */
    fun Map<Int, List<Value>>.numberAt(field: Int): Long? =
        (this[field]?.firstOrNull() as? Value.Number)?.value

    /** Convenience: the first bytes at [field], or null. */
    fun Map<Int, List<Value>>.bytesAt(field: Int): ByteArray? =
        (this[field]?.firstOrNull() as? Value.Bytes)?.value
}

/** A 32-bit little-endian field — how protobuf carries a `float`, e.g. the playback rate. */
internal fun Protobuf.float(field: Int, value: Float): ByteArray =
    tag(field, Protobuf.WIRE_FIXED32) +
        java.nio.ByteBuffer.allocate(Float.SIZE_BYTES).order(java.nio.ByteOrder.LITTLE_ENDIAN).putFloat(value).array()
