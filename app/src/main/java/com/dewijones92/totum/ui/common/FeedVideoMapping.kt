package com.dewijones92.totum.ui.common

import com.dewijones92.totum.common.youTubeChannelUrl
import com.dewijones92.totum.common.youTubeVideoId
import com.dewijones92.totum.data.search.SearchHit
import com.dewijones92.totum.domain.MediaContentKind
import com.dewijones92.totum.domain.MediaItem
import com.dewijones92.totum.domain.MediaItemId
import com.dewijones92.totum.domain.PublishedAge
import com.dewijones92.totum.domain.SourceId
import com.dewijones92.totum.innertube.feeds.FeedVideo
import com.dewijones92.totum.music.toMediaItem
import java.time.Instant
import kotlin.time.Duration.Companion.seconds

/**
 * Maps an InnerTube [FeedVideo] to the domain [MediaItem] every list and the
 * player use. One mapper for every YouTube list (feeds, playlists), so tags,
 * dates and the watch handle carry through identically. [watchUrl] is the stable
 * handle resolved to a stream on play.
 */
/**
 * [observedAt] anchors YouTube's relative wording ("2 hours ago") to an instant, so a row can keep
 * ageing after it is listed or persisted instead of wearing that text for ever.
 */
fun FeedVideo.toMediaItem(sourceId: SourceId, observedAt: Instant = Instant.now()): MediaItem = MediaItem(
    id = MediaItemId(videoId),
    sourceId = sourceId,
    title = title,
    publishedAt = publishedText?.let { PublishedAge.parse(it, observedAt) },
    publishedText = publishedText,
    // Zero means "not stated", which channel search often omits — rendering it as a
    // real duration put "0 min" under results that are minutes long.
    duration = durationSeconds?.takeIf { it > 0 }?.seconds,
    author = author,
    thumbnailUrl = thumbnailUrl,
    mediaUrl = watchUrl,
    viewsText = viewsText,
    membersOnly = membersOnly,
    contentKind = when (kind) {
        FeedVideo.Kind.VIDEO -> MediaContentKind.STANDARD
        FeedVideo.Kind.LIVE -> MediaContentKind.LIVE
        FeedVideo.Kind.SHORT -> MediaContentKind.SHORT
    },
    sourceUrl = channelId?.let { youTubeChannelUrl(it) },
)

/**
 * Maps a video search hit to a [MediaItem].
 *
 * Search results used to play through the launcher directly, which quietly bypassed the
 * queue — so a tapped search result never joined the spine. Giving a hit the same domain
 * shape as everything else is what lets it go through `PlaybackQueue` like the rest.
 *
 * The id is the video id read from the watch URL, the same id a feed, a shared link or a song
 * gives the same video; the whole URL was the id until v26, which made one video two items.
 */
fun SearchHit.Video.toMediaItem(sourceId: SourceId, observedAt: Instant = Instant.now()): MediaItem = MediaItem(
    id = MediaItemId(watchUrl.youTubeVideoId() ?: watchUrl.value),
    sourceId = sourceId,
    title = title,
    publishedAt = publishedText?.let { PublishedAge.parse(it, observedAt) },
    publishedText = publishedText,
    // Zero means "not stated", which channel search often omits — rendering it as a
    // real duration put "0 min" under results that are minutes long.
    duration = durationSeconds?.takeIf { it > 0 }?.seconds,
    author = subtitle,
    thumbnailUrl = artworkUrl,
    mediaUrl = watchUrl,
    viewsText = viewsText,
    membersOnly = membersOnly,
    sourceUrl = channelUrl,
)

fun SearchHit.Song.toMediaItem(sourceId: SourceId): MediaItem = song.toMediaItem(sourceId)
