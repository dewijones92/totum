package com.dewijones92.totum.sabr

/**
 * Session state the server hands out and expects back — the last echo mechanism this app ignored.
 *
 * The server sends `SABR_CONTEXT_UPDATE` parts carrying an opaque `value` under a numeric `type`, and
 * a client is meant to return the ones marked `send_by_default` in `streamer_context.sabr_contexts`.
 * We discarded every one of them, so each request arrived without state the server had already told us
 * to carry.
 *
 * Field numbers from SmartTube's `sabr_context_update.proto` (the update, 1/2/3/4/5) and
 * `streamer_context.proto` (the echo, `SabrContext { type = 1, value = 2 }`), read rather than guessed.
 */
public data class SabrContext(
    public val type: Int,
    public val value: ByteArray,
    /** Whether the server said to send this one back without being asked. */
    public val sendByDefault: Boolean = true,
) {
    internal fun encode(): ByteArray =
        Protobuf.number(FIELD_TYPE, type.toLong()) + Protobuf.bytes(FIELD_VALUE, value)

    override fun equals(other: Any?): Boolean =
        this === other || (other is SabrContext && type == other.type && value.contentEquals(other.value))

    override fun hashCode(): Int = 31 * type + value.contentHashCode()

    override fun toString(): String = "type=$type ${value.size}B${if (sendByDefault) " default" else ""}"

    public companion object {
        /** The contexts in one response, in the order they arrived. */
        public fun inResponse(response: ByteArray): List<SabrContext> =
            UmpReader.read(response).parts
                .filter { it.type == UmpPart.SABR_CONTEXT_UPDATE }
                .mapNotNull { parse(it.payload) }

        /** One `SABR_CONTEXT_UPDATE` payload, or null when it carries no type or value. */
        public fun parse(payload: ByteArray): SabrContext? {
            val fields = Protobuf.read(payload)
            val type = (fields[UPDATE_TYPE]?.firstOrNull() as? Protobuf.Value.Number)?.value ?: return null
            val value = (fields[UPDATE_VALUE]?.firstOrNull() as? Protobuf.Value.Bytes)?.value ?: return null
            val byDefault = (fields[UPDATE_SEND_BY_DEFAULT]?.firstOrNull() as? Protobuf.Value.Number)?.value
            return SabrContext(type.toInt(), value, sendByDefault = byDefault != 0L)
        }

        private const val FIELD_TYPE = 1
        private const val FIELD_VALUE = 2

        private const val UPDATE_TYPE = 1
        private const val UPDATE_VALUE = 3
        private const val UPDATE_SEND_BY_DEFAULT = 4
    }
}
