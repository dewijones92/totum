package com.dewijones92.totum.innertube.related

import com.dewijones92.totum.innertube.feeds.LockupParser
import com.dewijones92.totum.innertube.player.WatchNextParser
import kotlinx.serialization.json.Json

/**
 * Extracts the related / "up next" videos from a WEB `next` (watch-page)
 * response. YouTube serves these as `lockupViewModel` tiles under the secondary
 * results; the shared [LockupParser] does the walking/mapping (the same shape
 * powers channel tabs), so this only wraps it in a [RelatedResult].
 * Shape verified against a real watch-page response (2026-07-14).
 */
internal object RelatedVideosParser {

    private val json = Json { ignoreUnknownKeys = true }

    fun parse(body: String): RelatedResult {
        runCatching { json.parseToJsonElement(body) }.getOrNull()
            ?: return RelatedResult.Failure("Unparseable watch-page response")
        // Related videos are shown as a short "up next" list, not an infinite one, so
        // the page's continuation is deliberately dropped here.
        return RelatedResult.Success(
            LockupParser.videos(body).items,
            WatchNextParser.description(body),
            WatchNextParser.publishedOn(body),
        )
    }
}
