package com.dewijones92.totum.data.ask.state

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream

@Serializable
public data class AskStateEnvelope(val schema: JsonObject, val state: JsonObject)

@Serializable
public data class StateReceipt(val ok: Boolean, val bytes: Long, val keptForMs: Long)

public object AskStateWire {

    public const val MAX_COMPRESSED_BYTES: Long = 48L * 1024 * 1024
    public const val MAX_BYTES: Long = 128L * 1024 * 1024

    public fun pack(built: BuiltState): ByteArray {
        val envelope = AskStateEnvelope(StateSchema.of(AppState.serializer().descriptor, built.json), built.json)
        val raw = StateJson.encodeToString(AskStateEnvelope.serializer(), envelope).toByteArray(Charsets.UTF_8)
        return ByteArrayOutputStream().also { out -> GZIPOutputStream(out).use { it.write(raw) } }.toByteArray()
    }

    public fun unpack(compressed: InputStream): AskStateEnvelope {
        val raw = GZIPInputStream(compressed).use { it.readCapped(MAX_BYTES) }
        return StateJson.decodeFromString(AskStateEnvelope.serializer(), raw.toString(Charsets.UTF_8))
    }

    public fun unpack(compressed: ByteArray): AskStateEnvelope = unpack(ByteArrayInputStream(compressed))

    private fun InputStream.readCapped(limit: Long): ByteArray {
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(BUFFER)
        var total = 0L
        while (true) {
            val read = read(buffer)
            if (read < 0) break
            total += read
            require(total <= limit) { "the snapshot is larger than $limit bytes once unpacked" }
            out.write(buffer, 0, read)
        }
        return out.toByteArray()
    }

    private const val BUFFER = 64 * 1024
}
