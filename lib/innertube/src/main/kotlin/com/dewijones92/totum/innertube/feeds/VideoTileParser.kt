package com.dewijones92.totum.innertube.feeds

import com.dewijones92.totum.common.HttpUrl
import com.dewijones92.totum.common.Page
import com.dewijones92.totum.common.isYouTubeChannelId
import com.dewijones92.totum.innertube.browse.Badges
import com.dewijones92.totum.innertube.browse.Continuations
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

/**
 * Extracts videos from any InnerTube TV feed response (home, subscriptions,
 * watch later, history — they share the `tileRenderer` shape). Walks the tree
 * and collects every video tile, so it survives YouTube reshuffling shelves;
 * dedupes by video id, first-seen order preserved. Shape verified against
 * real feeds (2026-07-13).
 */
// One function per field a tile can yield, each a few lines of null-safe descent into
// YouTube's JSON. Merging them to satisfy the counter would produce one long unreadable
// walk and lose the per-field kdoc explaining why each is matched by shape, which is the
// part that keeps this working when YouTube reshuffles a response.
@Suppress("TooManyFunctions")
internal object VideoTileParser {

    private const val VIDEO_CONTENT_TYPE = "TILE_CONTENT_TYPE_VIDEO"
    private val json = Json { ignoreUnknownKeys = true }

    fun parse(body: String): FeedResult {
        val root = runCatching { json.parseToJsonElement(body) }.getOrNull()
            ?: return FeedResult.Failure("Unparseable feed response")
        val videos = LinkedHashMap<String, FeedVideo>()
        collectVideoTiles(root) { tile ->
            tile.toFeedVideo()?.let { videos.putIfAbsent(it.videoId, it) }
        }
        // A Shorts SHELF, when YouTube sends one. This is how SmartTube gets Shorts into a TV
        // feed (`BrowseService2.getShortsTV` reads the same subscriptions response and pulls the
        // shelf out of it) — the tiles above and the shelf are different shapes in the same body.
        //
        // Measured against Dewi's own account on 2026-08-16: a 3.6 MB subscriptions response with
        // 45 tiles and NO shelf, which is SmartTube's open bug #4278 rather than anything of ours.
        // Kept anyway because it costs one walk of a tree already parsed, and it is the difference
        // between Shorts appearing the day YouTube starts sending the shelf and nobody noticing.
        LockupParser.shortsIn(root).forEach { videos.putIfAbsent(it.videoId, it) }
        // No token means the last page. A response that parsed but yielded no tiles is
        // also the end, whatever it claims, or "load more" would spin forever.
        val items = videos.values.toList()
        return FeedResult.Success(Page(items, Continuations.find(root).takeIf { items.isNotEmpty() }))
    }

    private fun collectVideoTiles(node: JsonElement, onTile: (JsonObject) -> Unit) {
        when (node) {
            is JsonObject -> {
                val tile = node["tileRenderer"] as? JsonObject
                if (tile != null && tile.stringAt("contentType") == VIDEO_CONTENT_TYPE) onTile(tile)
                node.values.forEach { collectVideoTiles(it, onTile) }
            }
            is JsonArray -> node.forEach { collectVideoTiles(it, onTile) }
            else -> Unit
        }
    }

    private fun JsonObject.toFeedVideo(): FeedVideo? {
        val videoId = watchVideoId() ?: stringAt("contentId") ?: return null
        val watchUrl = FeedVideo.watchUrlFor(videoId) ?: return null
        val metadata = (this["metadata"] as? JsonObject)?.get("tileMetadataRenderer") as? JsonObject
        val title = (metadata?.get("title") as? JsonObject)?.stringAt("simpleText") ?: return null
        return FeedVideo(
            videoId = videoId,
            title = title,
            author = metadata.authorLine(),
            durationSeconds = durationSeconds(),
            thumbnailUrl = bestThumbnailUrl(),
            watchUrl = watchUrl,
            kind = when {
                "LIVE" in timeStatusStyles() -> FeedVideo.Kind.LIVE
                isShort() -> FeedVideo.Kind.SHORT
                else -> FeedVideo.Kind.VIDEO
            },
            publishedText = metadata.metadataLine { it.looksLikePublished() },
            viewsText = metadata.metadataLine { it.looksLikeViews() },
            membersOnly = Badges.membersOnly(this),
            channelId = channelId(),
        )
    }

    /**
     * The uploader's `UC…` id, taken from the tile's own long-press menu — the "go to
     * channel" entry YouTube itself renders.
     *
     * Found by **matching the shape, not the position**: the menu item's index varies by
     * tile (it was [3] in the first one), and a channel browse is the only entry whose
     * `browseId` is a `UC…`, so that is what identifies it. Reading a fixed index would work
     * on the feed it was written against and silently pick up a playlist or a report action
     * on another — the same trap that made the metadata lines shape-matched above.
     */
    private fun JsonObject.channelId(): String? {
        val items = ((this["onLongPressCommand"] as? JsonObject)?.get("showMenuCommand") as? JsonObject)
            ?.let { it["menu"] as? JsonObject }
            ?.let { it["menuRenderer"] as? JsonObject }
            ?.let { it["items"] as? JsonArray }
            ?: return null
        return items.asSequence()
            .mapNotNull { item ->
                ((item as? JsonObject)?.get("menuNavigationItemRenderer") as? JsonObject)
                    ?.let { it["navigationEndpoint"] as? JsonObject }
                    ?.let { it["browseEndpoint"] as? JsonObject }
                    ?.stringAt("browseId")
            }
            .firstOrNull { it.isYouTubeChannelId() }
    }

    /**
     * The first metadata line matching [matches], or null. Matched by shape rather than by
     * position: YouTube reorders these lines between tile types, so reading "line 2" for the
     * date silently picks up the view count on some feeds.
     */
    private inline fun JsonObject.metadataLine(matches: (String) -> Boolean): String? {
        val lines = this["lines"] as? JsonArray ?: return null
        for (line in lines) {
            val items = ((line as? JsonObject)?.get("lineRenderer") as? JsonObject)?.get("items") as? JsonArray
                ?: continue
            for (item in items) {
                val text = ((item as? JsonObject)?.get("lineItemRenderer") as? JsonObject)?.get("text")
                    ?.let { it as? JsonObject }?.readText()
                if (text != null && matches(text)) return text
            }
        }
        return null
    }

    /** Styles of the tile's thumbnail time-status overlays (e.g. "LIVE", "SHORTS"). */
    private fun JsonObject.timeStatusStyles(): List<String> {
        val overlays = ((this["header"] as? JsonObject)?.get("tileHeaderRenderer") as? JsonObject)
            ?.get("thumbnailOverlays") as? JsonArray ?: return emptyList()
        return overlays.mapNotNull { overlay ->
            (overlay as? JsonObject)?.get("thumbnailOverlayTimeStatusRenderer")
                ?.let { it as? JsonObject }?.stringAt("style")
        }
    }

    /** A Short opens a reel player (a `reelWatchEndpoint`) or carries a "SHORTS" overlay. */
    private fun JsonObject.isShort(): Boolean =
        (this["onSelectCommand"] as? JsonObject)?.get("reelWatchEndpoint") != null ||
            "SHORTS" in timeStatusStyles()

    /** A video (or Short) tile's id lives on its watch or reel endpoint. */
    private fun JsonObject.watchVideoId(): String? {
        val command = this["onSelectCommand"] as? JsonObject ?: return null
        return (command["watchEndpoint"] as? JsonObject)?.stringAt("videoId")
            ?: (command["reelWatchEndpoint"] as? JsonObject)?.stringAt("videoId")
    }

    /**
     * The channel: the first metadata line that is neither a view count nor a date.
     *
     * By SHAPE, like every other field here — it used to take line ZERO by position, and was the
     * one field in this parser that still did. On a third of the rows in Dewi's own subscriptions
     * feed (23 of 55, measured 2026-08-16) that first line is the view count, so the channel name
     * came out as "760K views" and the row then read `📺 760K views / 👁️ 760K views`.
     *
     * It had been that way for a long time and was invisible while the three facts shared one
     * truncating line; giving each its own line put it on screen. `LockupParser` already excluded
     * the two by shape, so this is the same rule, not a new one.
     */
    private fun JsonObject.authorLine(): String? =
        metadataLine { it.couldBeAChannelName() }

    /**
     * Whether a metadata line could be a channel at all: not a view count, not a date, and not
     * punctuation.
     *
     * The last clause is not hypothetical. The first version of this excluded only views and
     * dates, and the very next run on Dewi's feed showed `📺 ·` — YouTube renders the separator
     * between metadata lines as its own line item, and a bare "·" is neither a view count nor a
     * date. Requiring one letter or digit is what makes "is this a name" a question about the
     * text rather than about what it is not.
     */
    private fun String.couldBeAChannelName(): Boolean =
        !looksLikeViews() && !looksLikePublished() && any { it.isLetterOrDigit() }

    /** Duration lives in the thumbnail's time-status overlay, as "m:ss"/"h:mm:ss". */
    private fun JsonObject.durationSeconds(): Long? {
        val overlays = ((this["header"] as? JsonObject)?.get("tileHeaderRenderer") as? JsonObject)
            ?.get("thumbnailOverlays") as? JsonArray ?: return null
        for (overlay in overlays) {
            val status = (overlay as? JsonObject)?.get("thumbnailOverlayTimeStatusRenderer") as? JsonObject
            val text = (status?.get("text") as? JsonObject)?.readText()
            if (text != null) return parseClockToSeconds(text)
        }
        return null
    }

    private fun JsonObject.bestThumbnailUrl(): HttpUrl? {
        val thumbnails = ((this["header"] as? JsonObject)?.get("tileHeaderRenderer") as? JsonObject)
            ?.let { it["thumbnail"] as? JsonObject }
            ?.let { it["thumbnails"] as? JsonArray } ?: return null
        return (thumbnails.lastOrNull() as? JsonObject)?.stringAt("url")?.let(HttpUrl::parse)
    }
}

private fun JsonObject.readText(): String? {
    stringAt("simpleText")?.let { return it }
    val runs = this["runs"] as? JsonArray ?: return null
    return runs.joinToString("") { (it as? JsonObject)?.stringAt("text").orEmpty() }
}

private fun JsonObject.stringAt(key: String): String? =
    this[key]?.jsonPrimitive?.contentOrNull?.ifBlank { null }
