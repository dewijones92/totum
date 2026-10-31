package com.dewijones92.totum.data.backup

import com.dewijones92.totum.common.HttpUrl
import com.dewijones92.totum.domain.AlbumRef
import com.dewijones92.totum.domain.MediaContentKind
import com.dewijones92.totum.domain.MediaItem
import com.dewijones92.totum.domain.MediaItemId
import com.dewijones92.totum.domain.PlayableItem
import com.dewijones92.totum.domain.SourceId
import com.dewijones92.totum.domain.canonicalItemId
import com.dewijones92.totum.domain.persisted
import com.dewijones92.totum.domain.playHandleFrom
import kotlin.time.Duration.Companion.milliseconds

public fun PlayableItem.toBackup(): BackupItem {
    val (playbackType, handle) = this.handle.persisted()
    return BackupItem(
        itemId = item.id.value,
        sourceId = item.sourceId.value,
        title = item.title,
        author = item.author,
        publisher = item.publisher,
        thumbnailUrl = item.thumbnailUrl?.value,
        mediaUrl = item.mediaUrl?.value,
        contentKind = item.contentKind.name,
        playbackType = playbackType,
        handle = handle,
        durationMs = item.duration?.inWholeMilliseconds,
        sourceUrl = item.sourceUrl?.value,
        membersOnly = item.membersOnly,
        albumId = item.album?.id,
        albumTitle = item.album?.title,
        publishedAtMs = item.publishedAt?.toEpochMilli(),
        publishedText = item.publishedText,
        viewsText = item.viewsText,
    )
}

/** Null when the stored handle cannot be rebuilt — a restored row that plays nothing is worse than none. */
public fun BackupItem.toPlayable(): PlayableItem? {
    val playback = playHandleFrom(playbackType, handle) ?: return null
    return PlayableItem(
        MediaItem(
            id = MediaItemId(canonicalItemId(itemId)),
            sourceId = SourceId(sourceId),
            title = title,
            publishedAt = publishedAtMs?.let(java.time.Instant::ofEpochMilli),
            publishedText = publishedText,
            viewsText = viewsText,
            duration = durationMs?.milliseconds,
            author = author,
            publisher = publisher,
            thumbnailUrl = thumbnailUrl?.let(HttpUrl::parse),
            mediaUrl = mediaUrl?.let(HttpUrl::parse),
            membersOnly = membersOnly,
            sourceUrl = sourceUrl?.let(HttpUrl::parse),
            contentKind = runCatching { MediaContentKind.valueOf(contentKind) }
                .getOrDefault(MediaContentKind.STANDARD),
            album = albumTitle?.let { AlbumRef(albumId, it) },
        ),
        playback,
    )
}
