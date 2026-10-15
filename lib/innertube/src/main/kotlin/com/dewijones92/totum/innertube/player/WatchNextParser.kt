package com.dewijones92.totum.innertube.player

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull

/** What the TV watch page says a video is, for a signed-in TV `/player` answer that carries no title. */
public data class WatchNextDetails(val title: String, val author: String?, val channelId: String?)

public object WatchNextParser {
    private val json = Json { ignoreUnknownKeys = true }

    public fun details(body: String, videoId: String): WatchNextDetails? {
        val root = runCatching { json.parseToJsonElement(body) }.getOrNull() ?: return null
        val metadata = root.objectsNamed("videoMetadataRenderer")
            .firstOrNull { it.text("videoId") == videoId } ?: return null
        val owner = (metadata["owner"] as? JsonObject)?.get("videoOwnerRenderer") as? JsonObject
        return WatchNextDetails(
            title = metadata.readable("title") ?: return null,
            author = owner?.readable("title"),
            channelId = ((owner?.get("navigationEndpoint") as? JsonObject)?.get("browseEndpoint") as? JsonObject)
                ?.text("browseId"),
        )
    }

    private fun JsonElement.objectsNamed(name: String): Sequence<JsonObject> = sequence {
        when (val node = this@objectsNamed) {
            is JsonObject -> {
                (node[name] as? JsonObject)?.let { yield(it) }
                node.values.forEach { yieldAll(it.objectsNamed(name)) }
            }
            is JsonArray -> node.forEach { yieldAll(it.objectsNamed(name)) }
            else -> Unit
        }
    }

    private fun JsonObject.text(key: String): String? =
        (this[key] as? kotlinx.serialization.json.JsonPrimitive)?.contentOrNull?.ifBlank { null }

    private fun JsonObject.readable(key: String): String? {
        val node = this[key] as? JsonObject ?: return null
        node.text("simpleText")?.let { return it }
        val runs = node["runs"] as? JsonArray ?: return null
        return runs.joinToString("") { (it as? JsonObject)?.text("text").orEmpty() }.ifBlank { null }
    }
}
