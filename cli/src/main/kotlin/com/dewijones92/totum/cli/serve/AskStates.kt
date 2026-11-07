package com.dewijones92.totum.cli.serve

import com.dewijones92.totum.common.Diag
import com.dewijones92.totum.data.ask.state.AskStateEnvelope
import com.dewijones92.totum.data.ask.state.StateJson
import com.dewijones92.totum.data.ask.state.StateScrub
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.util.concurrent.ConcurrentHashMap

internal class StoredState(
    val id: String,
    val schema: String,
    val state: String,
    val itemIds: Set<String>,
    val storedAt: Long,
    val counts: String,
) {
    val bytes: Long get() = state.length.toLong() + schema.length
}

internal class AskStates(
    private val ttlMs: Long = TTL_MS,
    private val max: Int = MAX_KEPT,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val kept = ConcurrentHashMap<String, StoredState>()

    val size: Int get() = kept.size

    fun put(id: String, envelope: AskStateEnvelope): StoredState {
        prune()
        val scrubbed = StateScrub.clean(envelope.state)
        val stored = StoredState(
            id = id,
            schema = StateJson.encodeToString(JsonObject.serializer(), envelope.schema),
            state = StateJson.encodeToString(JsonElement.serializer(), scrubbed.element),
            itemIds = itemIdsIn(scrubbed.element),
            storedAt = clock(),
            counts = countsOf(scrubbed.element),
        )
        while (kept.size >= max) {
            val oldest = kept.values.minByOrNull { it.storedAt } ?: break
            kept.remove(oldest.id)
            Diag.log("ask", "snapshot ${oldest.id} deleted early: more than $max kept at once")
        }
        kept[id] = stored
        Diag.log(
            "ask",
            "snapshot $id kept for ${ttlMs / MS_PER_MINUTE} min: ${stored.bytes} bytes, " +
                "${stored.itemIds.size} item ids, rescrubbed ${scrubbed.droppedKeys} key(s) and " +
                "${scrubbed.redactedValues} value(s); counts ${stored.counts}",
        )
        return stored
    }

    fun get(id: String): StoredState? {
        prune()
        return kept[id]
    }

    fun forget(id: String) {
        if (kept.remove(id) != null) Diag.log("ask", "snapshot $id deleted")
    }

    fun prune(): Int {
        val cutoff = clock() - ttlMs
        var gone = 0
        kept.values.filter { it.storedAt <= cutoff }.forEach { old ->
            if (kept.remove(old.id) != null) {
                gone++
                Diag.log("ask", "snapshot ${old.id} deleted after ${ttlMs / MS_PER_MINUTE} minutes")
            }
        }
        return gone
    }

    val keepForMs: Long get() = ttlMs

    private fun itemIdsIn(element: JsonElement): Set<String> = buildSet {
        fun walk(node: JsonElement) {
            when (node) {
                is JsonObject -> node.forEach { (key, value) ->
                    if (key == "itemId" && value is JsonPrimitive && value.isString) add(value.content) else walk(value)
                }
                is JsonArray -> node.forEach(::walk)
                is JsonPrimitive -> Unit
            }
        }
        walk(element)
    }

    private fun countsOf(element: JsonElement): String =
        ((element as? JsonObject)?.get("snapshot") as? JsonObject)?.get("counts")?.toString().orEmpty()

    companion object {
        const val TTL_MS = 10 * 60_000L
        const val MAX_KEPT = 6
        private const val MS_PER_MINUTE = 60_000L
    }
}
