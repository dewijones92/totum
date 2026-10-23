package com.dewijones92.totum.stars

import com.dewijones92.totum.common.Diag
import com.dewijones92.totum.common.HttpUrl
import com.dewijones92.totum.data.playlist.LocalPlaylistStore
import com.dewijones92.totum.domain.LocalPlaylist
import com.dewijones92.totum.domain.MediaItem
import com.dewijones92.totum.domain.MediaItemId
import com.dewijones92.totum.domain.PlayHandle
import com.dewijones92.totum.domain.PlayableItem
import com.dewijones92.totum.domain.PlaylistId
import com.dewijones92.totum.domain.SourceId
import com.dewijones92.totum.domain.WATCH_PREFIX
import com.dewijones92.totum.domain.isYouTubeVideoId
import com.dewijones92.totum.domain.toPlayableOrNull
import com.dewijones92.totum.music.MusicFavourites.Outcome
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

val STARRED_PLAYLIST: PlaylistId = PlaylistId("starred")

const val STARRED_PLAYLIST_NAME: String = "⭐ Starred"

data class StarOutcome(val starredHere: Boolean, val account: Outcome?)

class Stars(
    private val playlists: LocalPlaylistStore,
    private val accountLike: suspend (videoId: String, title: String, liked: Boolean) -> Outcome,
) {
    val starredIds: Flow<Set<MediaItemId>> =
        playlists.observeItems(STARRED_PLAYLIST).map { items -> items.mapTo(mutableSetOf()) { it.item.id } }

    val starred: Flow<List<PlayableItem>> = playlists.observeItems(STARRED_PLAYLIST)

    suspend fun markHere(item: PlayableItem, on: Boolean) {
        playlists.ensure(STARRED_PLAYLIST, STARRED_PLAYLIST_NAME)
        if (on) playlists.addItem(STARRED_PLAYLIST, item) else playlists.removeItem(STARRED_PLAYLIST, item.item.id)
    }

    suspend fun setStarred(item: PlayableItem, on: Boolean): StarOutcome {
        markHere(item, on)
        val videoId = item.item.id.value.takeIf(::isYouTubeVideoId)
        val account = videoId?.let { accountLike(it, item.item.title, on) }
        Diag.log(
            TAG,
            "${if (on) "starred" else "unstarred"} ${item.item.id.value} \"${item.item.title}\" here; " +
                (
                    account?.let { "YouTube ${if (on) "like" else "unlike"}: ${it.phrase}" }
                        ?: "not a YouTube item, so local only"
                    ),
        )
        return StarOutcome(starredHere = on, account = account)
    }

    private companion object {
        const val TAG = "stars"
    }
}

const val LIKED_VIDEOS_BROWSE_ID: String = "VLLL"

val LIKED_SOURCE: SourceId = SourceId("ytplaylist:$LIKED_VIDEOS_BROWSE_ID")

fun starredWithLikes(local: List<PlayableItem>, liked: List<MediaItem>): List<PlayableItem> {
    val here = local.mapTo(mutableSetOf()) { it.item.id }
    return local + liked.filter { it.id !in here }.mapNotNull { video ->
        video.mediaUrl?.let { PlayableItem(video, PlayHandle.Video(it)) }
    }
}

fun LocalPlaylistStore.observeYourPlaylists(): Flow<List<LocalPlaylist>> =
    observePlaylists().map { all -> all.filter { it.id != STARRED_PLAYLIST } }

fun MediaItem.starrable(): PlayableItem? = toPlayableOrNull()
    ?: id.value.takeIf(::isYouTubeVideoId)?.let { PlayableItem(this, PlayHandle.Video(HttpUrl.of(WATCH_PREFIX + it))) }
