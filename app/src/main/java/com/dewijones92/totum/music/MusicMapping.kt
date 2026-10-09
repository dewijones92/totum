package com.dewijones92.totum.music

import com.dewijones92.totum.domain.MediaContentKind
import com.dewijones92.totum.domain.MediaItem
import com.dewijones92.totum.domain.MediaItemId
import com.dewijones92.totum.domain.PlayHandle
import com.dewijones92.totum.domain.PlayableItem
import com.dewijones92.totum.domain.SourceId
import com.dewijones92.totum.innertube.music.MusicSong
import kotlin.time.Duration.Companion.seconds

fun MusicSong.toMediaItem(sourceId: SourceId): MediaItem = MediaItem(
    id = MediaItemId(videoId),
    sourceId = sourceId,
    title = title,
    publishedAt = null,
    duration = durationSeconds?.takeIf { it > 0 }?.seconds,
    author = artist,
    thumbnailUrl = thumbnailUrl,
    mediaUrl = watchUrl,
    viewsText = playsText,
    contentKind = MediaContentKind.MUSIC,
)

fun MusicSong.toPlayable(sourceId: SourceId): PlayableItem =
    PlayableItem(toMediaItem(sourceId), PlayHandle.Video(watchUrl))

object MusicSources {
    fun album(browseId: String): SourceId = SourceId("music:album:$browseId")

    fun artist(browseId: String): SourceId = SourceId("music:artist:$browseId")

    fun radio(playlistId: String): SourceId = SourceId("music:radio:$playlistId")
}
