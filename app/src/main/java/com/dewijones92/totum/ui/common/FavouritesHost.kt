package com.dewijones92.totum.ui.common

import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import com.dewijones92.totum.music.MusicFavourites
import com.dewijones92.totum.ui.music.likeIcon
import com.dewijones92.totum.ui.music.likeLabel
import com.dewijones92.totum.ui.music.rememberSongLike
import kotlinx.coroutines.CoroutineScope

class FavouritesHost(val favourites: MusicFavourites, val scope: CoroutineScope)

internal val LocalFavourites = staticCompositionLocalOf<FavouritesHost?> { null }

const val SONG_LIKE_TAG = "sheet-song-like"

@Composable
internal fun SongLikeAction(songId: String?, title: String, onDismiss: () -> Unit) {
    val host = LocalFavourites.current ?: return
    if (songId == null) return
    val like = rememberSongLike(host.favourites, host.scope, songId, title)
    SheetAction(like.flip, like.likeIcon, like.likeLabel, onDismiss, tag = SONG_LIKE_TAG)
}
