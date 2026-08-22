package com.dewijones92.totum.database

import com.dewijones92.totum.common.HttpUrl
import com.dewijones92.totum.domain.MediaContentKind
import com.dewijones92.totum.domain.MediaItem
import com.dewijones92.totum.domain.MediaItemId
import com.dewijones92.totum.domain.PlayHandle
import com.dewijones92.totum.domain.PlayableItem
import com.dewijones92.totum.domain.SourceId
import com.dewijones92.totum.domain.persisted
import com.dewijones92.totum.domain.playHandleFrom
import java.time.Instant
import kotlin.time.Duration.Companion.milliseconds

/**
 * The denormalized columns a [PlayableItem] persists as — shared by the local-playlist
 * and play-history entities so [playlistItemFrom] maps them one way for both (DRY).
 * A video keeps its watch URL as the handle; a podcast its enclosure in mediaUrl.
 */
internal interface PlaylistItemColumns {
    val itemId: String
    val sourceId: String
    val title: String
    val author: String?
    val thumbnailUrl: String?
    val contentKind: String
    val playbackType: String
    val handle: String?
    val mediaUrl: String?

    /**
     * What the listing said about the item, which nothing else can reconstruct.
     *
     * A resolution knows the stream and nothing else — no view count, no publication date — so once
     * a row loses these they are gone for good. They were being dropped here, silently, by the
     * rebuild below setting `publishedAt = null` and there being no column for the other two: the
     * facts survived the media session (`PlayerMetadataTest`) and then died in the database, so the
     * video page showed them for an item tapped from a feed and showed nothing for the same item
     * replayed from the queue. Caught by CI on 2026-08-07, having passed locally on timing.
     *
     * Epoch millis for the instant, because SQLite has no date type and a Long sorts correctly.
     */
    val viewsText: String?
    val publishedText: String?
    val publishedAtEpochMs: Long?

    /**
     * The rest of what the listing said, dropped for the same reason and found the same way.
     *
     * [durationMs] is not decoration: the Library's "Longest first" sorts on it, so losing it made
     * that menu entry a silent no-op and took the length chip off every persisted row.
     *
     * [sourceUrl] is what makes "Go to channel" instant. Without it `DefaultSourceLocator` falls back
     * to a full yt-dlp extraction to read one string -- 12.5s on a real phone -- so the same video was
     * instant from a feed row and a twelve-second wait from a Library row.
     *
     * [membersOnly] costs nothing to keep and is worth showing rather than discovering at play time;
     * three of them once sat unexplained in a real download queue.
     */
    val durationMs: Long?
    val sourceUrl: String?
    val membersOnly: Boolean
}

/** The one place the denormalized columns rebuild a [PlayableItem]; null if the handle is unusable. */
internal fun playlistItemFrom(columns: PlaylistItemColumns): PlayableItem? {
    val playback = playHandleFrom(columns.playbackType, columns.handle) ?: return null
    val item = MediaItem(
        id = MediaItemId(columns.itemId),
        sourceId = SourceId(columns.sourceId),
        title = columns.title,
        publishedAt = columns.publishedAtEpochMs?.let(Instant::ofEpochMilli),
        publishedText = columns.publishedText,
        viewsText = columns.viewsText,
        duration = columns.durationMs?.milliseconds,
        author = columns.author,
        thumbnailUrl = columns.thumbnailUrl?.let(HttpUrl::parse),
        mediaUrl = columns.mediaUrl?.let(HttpUrl::parse),
        contentKind = runCatching { MediaContentKind.valueOf(columns.contentKind) }
            .getOrDefault(MediaContentKind.STANDARD),
        membersOnly = columns.membersOnly,
        sourceUrl = columns.sourceUrl?.let(HttpUrl::parse),
    )
    return PlayableItem(item, playback)
}

/** The persisted `playbackType` + `handle`; the vocabulary itself lives in the domain. */
internal fun PlayHandle.typeAndHandle(): Pair<String, String?> = persisted()
