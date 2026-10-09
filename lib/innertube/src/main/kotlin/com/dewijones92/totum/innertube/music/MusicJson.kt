package com.dewijones92.totum.innertube.music

import com.dewijones92.totum.common.HttpUrl
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

internal val musicJson: Json = Json { ignoreUnknownKeys = true }

internal fun parseMusicRoot(body: String): JsonElement? = runCatching { musicJson.parseToJsonElement(body) }.getOrNull()

internal fun JsonElement.collectEach(key: String, onNode: (JsonObject) -> Unit) {
    when (this) {
        is JsonObject -> {
            (this[key] as? JsonObject)?.let(onNode)
            values.forEach { it.collectEach(key, onNode) }
        }
        is JsonArray -> forEach { it.collectEach(key, onNode) }
        else -> Unit
    }
}

internal fun JsonElement.firstObject(key: String): JsonObject? = when (this) {
    is JsonObject -> (this[key] as? JsonObject) ?: values.firstNotNullOfOrNull { it.firstObject(key) }
    is JsonArray -> firstNotNullOfOrNull { it.firstObject(key) }
    else -> null
}

internal fun JsonObject.obj(key: String): JsonObject? = this[key] as? JsonObject

internal fun JsonObject.str(key: String): String? = this[key]?.let {
    runCatching { it.jsonPrimitive.contentOrNull }.getOrNull()
}
    ?.ifBlank { null }

internal fun JsonObject?.text(): String? {
    if (this == null) return null
    str("simpleText")?.let { return it }
    val runs = this["runs"] as? JsonArray ?: return null
    return runs.joinToString("") { (it as? JsonObject)?.str("text").orEmpty() }.trim().ifBlank { null }
}

/**
 * The id from the first `watchEndpoint` anywhere in the row.
 *
 * Anywhere on purpose: it appears on the overlay's play button AND on the title run, and which
 * of them is present varies by row type. Searching for the field rather than a path means a
 * layout change cannot quietly turn every song into an unplayable one.
 */
internal fun JsonElement.firstVideoId(): String? = when (this) {
    is JsonObject -> obj("watchEndpoint")?.str("videoId") ?: values.firstNotNullOfOrNull { it.firstVideoId() }
    is JsonArray -> firstNotNullOfOrNull { it.firstVideoId() }
    else -> null
}

internal data class BrowseLink(val browseId: String, val pageType: String?)

internal fun JsonElement.firstBrowseLink(): BrowseLink? = when (this) {
    is JsonObject -> obj("browseEndpoint")?.let { endpoint ->
        endpoint.str("browseId")?.let { id ->
            val pageType = endpoint.obj("browseEndpointContextSupportedConfigs")
                ?.obj("browseEndpointContextMusicConfig")?.str("pageType")
            BrowseLink(id, pageType)
        }
    } ?: values.firstNotNullOfOrNull { it.firstBrowseLink() }
    is JsonArray -> firstNotNullOfOrNull { it.firstBrowseLink() }
    else -> null
}

/**
 * The largest thumbnail a row offers; music rows list them smallest-first.
 *
 * A file-level function rather than a member: it is about pictures, not about rows, and every
 * candidate is `{url, width, height}` wherever it appears in the tree.
 */
internal fun JsonElement.bestThumbnailUrl(): HttpUrl? {
    val candidates = mutableListOf<Pair<Int, String>>()
    fun walk(node: JsonElement) {
        when (node) {
            is JsonObject -> {
                val url = node.str("url")
                val width = node.str("width")?.toIntOrNull()
                if (url != null && width != null) candidates += width to url
                node.values.forEach(::walk)
            }
            is JsonArray -> node.forEach(::walk)
            else -> Unit
        }
    }
    walk(this)
    return candidates.maxByOrNull { it.first }?.second?.let { HttpUrl.parse(it) ?: HttpUrl.parse("https:$it") }
}
