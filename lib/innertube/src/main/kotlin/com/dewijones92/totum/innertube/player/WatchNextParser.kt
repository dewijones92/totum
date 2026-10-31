package com.dewijones92.totum.innertube.player

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

public data class WatchNextDetails(
    val title: String,
    val author: String?,
    val channelId: String?,
    val description: String? = null,
    val publishedOn: LocalDate? = null,
    val publishedAgo: String? = null,
)

@Suppress("TooManyFunctions")
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
            description = root.description(),
            publishedOn = root.publishedOn(),
            publishedAgo = metadata.readable("dateText")?.takeIf { it.endsWith(" ago") },
        )
    }

    public fun description(body: String): String? =
        runCatching { json.parseToJsonElement(body) }.getOrNull()?.description()

    public fun publishedOn(body: String): LocalDate? =
        runCatching { json.parseToJsonElement(body) }.getOrNull()?.publishedOn()

    private fun JsonElement.publishedOn(): LocalDate? {
        val text = objectsNamed("videoPrimaryInfoRenderer").firstNotNullOfOrNull { it.readable("dateText") }
            ?: objectsNamed("videoDescriptionHeaderRenderer").firstNotNullOfOrNull { it.readable("publishDate") }
            ?: objectsNamed("videoMetadataRenderer").firstNotNullOfOrNull { it.readable("dateText") }
            ?: return null
        return DATE_FORMATS.firstNotNullOfOrNull { format -> dateIn(text, format) }
    }

    private fun dateIn(text: String, format: DateTimeFormatter): LocalDate? =
        text.indices.asSequence().filter { it == 0 || text[it - 1] == ' ' }.firstNotNullOfOrNull { start ->
            runCatching { LocalDate.parse(text.substring(start).trim(), format) }.getOrNull()
        }

    private val DATE_FORMATS = listOf("MMM d, yyyy", "d MMM yyyy", "MMMM d, yyyy", "d MMMM yyyy")
        .map { DateTimeFormatter.ofPattern(it, Locale.ENGLISH) }

    private fun JsonElement.description(): String? =
        objectsNamed("videoSecondaryInfoRenderer").firstNotNullOfOrNull { it.anyDescription() } ?: anyDescription()

    private fun JsonElement.anyDescription(): String? = DESCRIPTION_KEYS.firstNotNullOfOrNull { key ->
        objectsHaving(key).firstNotNullOfOrNull { it.readable(key) }
    }

    private fun JsonElement.objectsHaving(key: String): Sequence<JsonObject> = sequence {
        when (val node = this@objectsHaving) {
            is JsonObject -> {
                if (key in node) yield(node)
                node.values.forEach { yieldAll(it.objectsHaving(key)) }
            }
            is JsonArray -> node.forEach { yieldAll(it.objectsHaving(key)) }
            else -> Unit
        }
    }

    private val DESCRIPTION_KEYS = listOf(
        "descriptionBodyText",
        "attributedDescription",
        "attributedDescriptionBodyText"
    )

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
        node.text("content")?.let { return it }
        val runs = node["runs"] as? JsonArray ?: return null
        return runs.joinToString("") { (it as? JsonObject)?.text("text").orEmpty() }.ifBlank { null }
    }
}
