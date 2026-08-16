package com.dewijones92.totum.innertube.feeds

import com.dewijones92.totum.common.HttpUrl
import com.dewijones92.totum.common.Page
import com.dewijones92.totum.innertube.browse.Badges
import com.dewijones92.totum.innertube.browse.Continuations
import com.dewijones92.totum.innertube.playlists.Playlist
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

/**
 * Shared parser for YouTube's newer `lockupViewModel` tile shapes, used by both
 * the related list and channel tabs (one copy of the shape knowledge — DRY).
 * Walks the whole response tree collecting tiles, so it survives YouTube
 * reshuffling sections; dedupes by id, first-seen order preserved.
 *
 * - [videos] — `lockupViewModel` of `LOCKUP_CONTENT_TYPE_VIDEO`/`SHORTS`.
 * - [shorts] — `shortsLockupViewModel` (the Shorts-shelf tile).
 * - [playlists] — `lockupViewModel` of `LOCKUP_CONTENT_TYPE_PLAYLIST`.
 */
// A parser is naturally many small field-extractors; splitting it would scatter
// the one place that knows YouTube's lockup shape (the point of the class).
@Suppress("TooManyFunctions")
internal object LockupParser {

    private const val VIDEO_CONTENT_TYPE = "LOCKUP_CONTENT_TYPE_VIDEO"
    private const val SHORTS_CONTENT_TYPE = "LOCKUP_CONTENT_TYPE_SHORTS"
    private const val PLAYLIST_CONTENT_TYPE = "LOCKUP_CONTENT_TYPE_PLAYLIST"
    private val VIDEO_CONTENT_TYPES = setOf(VIDEO_CONTENT_TYPE, SHORTS_CONTENT_TYPE)
    private const val LIVE_BADGE_STYLE = "THUMBNAIL_OVERLAY_BADGE_STYLE_LIVE"
    private val json = Json { ignoreUnknownKeys = true }

    fun videos(body: String): Page<FeedVideo> {
        val root = parseOrNull(body) ?: return Page.empty()
        val out = LinkedHashMap<String, FeedVideo>()
        collect(root, "lockupViewModel") { lockup ->
            if (lockup.stringAt("contentType") in VIDEO_CONTENT_TYPES) {
                lockup.toFeedVideo()?.let { out.putIfAbsent(it.videoId, it) }
            }
        }
        return root.pageOf(out.values.toList())
    }

    fun shorts(body: String): Page<FeedVideo> {
        val root = parseOrNull(body) ?: return Page.empty()
        val items = shortsIn(root)
        return root.pageOf(items)
    }

    /**
     * The Shorts tiles anywhere under an ALREADY-PARSED response.
     *
     * Split out so a caller that has parsed the tree for its own reasons can look for a Shorts
     * shelf without paying to parse it again — a TV subscriptions response is 3.6 MB, and doing
     * that twice to find a shelf that is usually absent would be a real cost for nothing.
     */
    fun shortsIn(root: JsonElement): List<FeedVideo> {
        val out = LinkedHashMap<String, FeedVideo>()
        collect(root, "shortsLockupViewModel") { short ->
            short.toShort()?.let { out.putIfAbsent(it.videoId, it) }
        }
        return out.values.toList()
    }

    fun playlists(body: String): Page<Playlist> {
        val root = parseOrNull(body) ?: return Page.empty()
        val out = LinkedHashMap<String, Playlist>()
        collect(root, "lockupViewModel") { lockup ->
            if (lockup.stringAt("contentType") == PLAYLIST_CONTENT_TYPE) {
                lockup.toPlaylist()?.let { out.putIfAbsent(it.browseId, it) }
            }
        }
        return root.pageOf(out.values.toList())
    }

    /**
     * Pairs items with this response's continuation. An empty page carries no token,
     * whatever the response claims — otherwise "load more" would spin forever on a tab
     * whose shape we failed to read.
     */
    private fun <T> JsonElement.pageOf(items: List<T>): Page<T> =
        Page(items, Continuations.find(this).takeIf { items.isNotEmpty() })

    private fun parseOrNull(body: String): JsonElement? = runCatching { json.parseToJsonElement(body) }.getOrNull()

    /** Walks the tree, invoking [onNode] for every object found under [key]. */
    private fun collect(node: JsonElement, key: String, onNode: (JsonObject) -> Unit) {
        when (node) {
            is JsonObject -> {
                (node[key] as? JsonObject)?.let(onNode)
                node.values.forEach { collect(it, key, onNode) }
            }
            is JsonArray -> node.forEach { collect(it, key, onNode) }
            else -> Unit
        }
    }

    private fun JsonObject.toFeedVideo(): FeedVideo? {
        val videoId = stringAt("contentId") ?: return null
        val watchUrl = FeedVideo.watchUrlFor(videoId) ?: return null
        val metadata = lockupMetadata()
        val title = (metadata?.get("title") as? JsonObject)?.stringAt("content") ?: return null
        return FeedVideo(
            videoId = videoId,
            title = title,
            author = metadata.authorLine(),
            durationSeconds = durationSeconds(),
            thumbnailUrl = bestThumbnailUrl(),
            watchUrl = watchUrl,
            kind = when {
                isLive() -> FeedVideo.Kind.LIVE
                stringAt("contentType") == SHORTS_CONTENT_TYPE -> FeedVideo.Kind.SHORT
                else -> FeedVideo.Kind.VIDEO
            },
            publishedText = metadata.publishedText(),
            viewsText = metadata.viewsText(),
            membersOnly = Badges.membersOnly(this),
        )
    }

    private fun JsonObject.toShort(): FeedVideo? {
        val videoId = reelVideoId() ?: return null
        val watchUrl = FeedVideo.watchUrlFor(videoId) ?: return null
        val overlay = this["overlayMetadata"] as? JsonObject
        val title = (overlay?.get("primaryText") as? JsonObject)?.stringAt("content") ?: return null
        return FeedVideo(
            videoId = videoId,
            title = title,
            // The author is the CALLER's to fill in: a Shorts tile carries none, and the only
            // request that returns these is for one channel, so whoever asked already knows.
            author = null,
            durationSeconds = null,
            thumbnailUrl = (this["thumbnailViewModel"] as? JsonObject)?.bestThumbnailUrlFromImage(),
            watchUrl = watchUrl,
            kind = FeedVideo.Kind.SHORT,
            publishedText = null,
            // "3.8K views", already in the shape every other row uses. Without it a Short in a
            // feed of videos has nothing under its title at all, which reads as broken rather
            // than as brief.
            viewsText = (overlay?.get("secondaryText") as? JsonObject)?.stringAt("content"),
        )
    }

    private fun JsonObject.toPlaylist(): Playlist? {
        val playlistId = stringAt("contentId") ?: return null
        val metadata = lockupMetadata()
        val title = (metadata?.get("title") as? JsonObject)?.stringAt("content") ?: return null
        return Playlist(
            // "VL" + id is the browse id that fetches the playlist's videos.
            browseId = "VL$playlistId",
            title = title,
            videoCountText = metadata.firstMetadataText(),
            thumbnailUrl = bestThumbnailUrl(),
        )
    }

    private fun JsonObject.reelVideoId(): String? {
        var found: String? = null
        collect(this, "reelWatchEndpoint") { ep -> if (found == null) found = ep.stringAt("videoId") }
        return found
    }

    private fun JsonObject.lockupMetadata(): JsonObject? =
        (this["metadata"] as? JsonObject)?.get("lockupMetadataViewModel") as? JsonObject

    /** The metadata part holding the view count ("1.2M views", "No views"). */
    private fun JsonObject.viewsText(): String? {
        forEachMetadataPart { text -> if (text.looksLikeViews()) return text }
        return null
    }

    /** The metadata part YouTube uses for the published date (e.g. "2 days ago"). */
    private fun JsonObject.publishedText(): String? {
        forEachMetadataPart { text -> if (text.looksLikePublished()) return text }
        return null
    }

    /**
     * The channel/author line — usually the first metadata part, but NOT always present.
     *
     * On a channel's own page the tiles omit the channel name (you are already on it), so the
     * first part is the view count instead. Taking it positionally therefore set author to
     * "6.2K views" and every row read "6.2K views · 6.2K views · 10 hours ago". So: the first
     * part that is not one of the things we can recognise as something else.
     */
    private fun JsonObject.authorLine(): String? {
        forEachMetadataPart { text ->
            if (!text.looksLikeViews() && !text.looksLikePublished()) return text
        }
        return null
    }

    /** The very first metadata text (e.g. a playlist's "184 videos"). */
    private fun JsonObject.firstMetadataText(): String? {
        forEachMetadataPart { text -> return text }
        return null
    }

    private inline fun JsonObject.forEachMetadataPart(onText: (String) -> Unit) {
        val rows = metadataRows() ?: return
        for (row in rows) {
            val parts = (row as? JsonObject)?.get("metadataParts") as? JsonArray ?: continue
            for (part in parts) {
                val text = ((part as? JsonObject)?.get("text") as? JsonObject)?.stringAt("content")
                if (text != null) onText(text)
            }
        }
    }

    private fun JsonObject.metadataRows(): JsonArray? =
        ((this["metadata"] as? JsonObject)?.get("contentMetadataViewModel") as? JsonObject)
            ?.get("metadataRows") as? JsonArray

    private fun JsonObject.isLive(): Boolean {
        val overlays = thumbnailViewModel()?.get("overlays") as? JsonArray ?: return false
        return collectBadgeValues(overlays, "badgeStyle").any { it == LIVE_BADGE_STYLE }
    }

    /** Duration is the thumbnail's bottom-overlay badge text ("m:ss"/"h:mm:ss"). */
    private fun JsonObject.durationSeconds(): Long? {
        val overlays = thumbnailViewModel()?.get("overlays") as? JsonArray ?: return null
        collectBadgeValues(overlays, "text").forEach { text -> parseClockToSeconds(text)?.let { return it } }
        return null
    }

    private fun collectBadgeValues(node: JsonElement, field: String): List<String> {
        val values = mutableListOf<String>()
        collect(node, "thumbnailBadgeViewModel") { badge -> badge.stringAt(field)?.let { values.add(it) } }
        return values
    }

    private fun JsonObject.bestThumbnailUrl(): HttpUrl? = thumbnailViewModel()?.bestThumbnailUrlFromImage()

    private fun JsonObject.bestThumbnailUrlFromImage(): HttpUrl? {
        val sources = (this["image"] as? JsonObject)?.get("sources") as? JsonArray ?: return null
        return (sources.lastOrNull() as? JsonObject)?.stringAt("url")?.let(HttpUrl::parse)
    }

    private fun JsonObject.thumbnailViewModel(): JsonObject? =
        (this["contentImage"] as? JsonObject)?.get("thumbnailViewModel") as? JsonObject

    private fun JsonObject.stringAt(key: String): String? =
        this[key]?.jsonPrimitive?.contentOrNull?.ifBlank { null }
}
