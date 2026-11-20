package com.dewijones92.totum.data.bbc

import com.dewijones92.totum.data.rss.ParsedEpisode
import com.dewijones92.totum.data.rss.ParsedFeed
import com.dewijones92.totum.data.rss.RssParseResult
import com.dewijones92.totum.domain.IPlayer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import java.time.Instant
import kotlin.time.Duration

public object IblEpisodesParser {

    private val json = Json { ignoreUnknownKeys = true }

    @Suppress("ReturnCount")
    public fun parse(body: String): RssParseResult {
        val root = runCatching { json.parseToJsonElement(body) as JsonObject }
            .getOrElse { return RssParseResult.Failure("Not a BBC iPlayer answer: ${it.message}") }
        val page = root["programme_episodes"] as? JsonObject
            ?: return RssParseResult.Failure("BBC iPlayer answer has no programme_episodes")
        val programme = page["programme"] as? JsonObject
            ?: return RssParseResult.Failure("BBC iPlayer has no episodes for this programme right now")
        val title = programme.text("title")
            ?: return RssParseResult.Failure("BBC iPlayer programme has no title")
        val episodes = (page["elements"] as? JsonArray).orEmpty()
            .mapNotNull { (it as? JsonObject)?.toEpisode() }
            .sortedByDescending { it.publishedAt ?: Instant.EPOCH }
        return RssParseResult.Success(
            ParsedFeed(
                title = title,
                author = programme.channelName(),
                description = programme.synopsis(),
                websiteUrl = programme.text("id")?.let { BbcProgrammes.programmePageUrl(it).value },
                imageUrl = BbcProgrammes.imageUrl(programme.images()?.text("standard"))?.value,
                episodes = episodes,
            ),
        )
    }

    private fun JsonObject.toEpisode(): ParsedEpisode? {
        val pid = text("id") ?: return null
        val version = versions().let { all -> all.firstOrNull { it.text("kind") == "original" } ?: all.firstOrNull() }
        val availability = version?.get("availability") as? JsonObject
        return ParsedEpisode(
            guid = IPlayer.ID_PREFIX + pid,
            title = text("subtitle") ?: text("title") ?: pid,
            author = null,
            enclosureUrl = IPlayer.episodeUrl(pid).value,
            publishedAt = instant(version?.text("first_broadcast_date_time"))
                ?: instant(text("release_date_time"))
                ?: instant(availability?.text("start")),
            duration = ((version?.get("duration") as? JsonObject)?.text("value"))
                ?.let { Duration.parseIsoStringOrNull(it) }?.takeIf { it.isPositive() },
            description = synopsis(),
            imageUrl = BbcProgrammes.imageUrl(images()?.text("standard"))?.value,
            availableUntil = instant(availability?.text("end")),
        )
    }

    private fun JsonObject.versions(): List<JsonObject> =
        (this["versions"] as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }

    private fun JsonObject.images(): JsonObject? = this["images"] as? JsonObject

    private fun JsonObject.channelName(): String? =
        ((this["master_brand"] as? JsonObject)?.get("titles") as? JsonObject)?.text("small")

    private fun JsonObject.synopsis(): String? =
        (this["synopses"] as? JsonObject)?.let { it.text("large") ?: it.text("medium") ?: it.text("small") }

    private fun instant(text: String?): Instant? = text?.let { runCatching { Instant.parse(it) }.getOrNull() }
}

internal fun JsonObject.text(key: String): String? =
    (this[key] as? JsonPrimitive)?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() }
