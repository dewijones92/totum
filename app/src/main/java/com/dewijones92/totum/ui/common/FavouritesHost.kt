package com.dewijones92.totum.ui.common

import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import com.dewijones92.totum.music.MusicFavourites
import com.dewijones92.totum.ui.music.likeIcon
import com.dewijones92.totum.ui.music.likeLabel
import com.dewijones92.totum.ui.music.rememberSongLike
import kotlinx.coroutines.CoroutineScope

class FavouritesHost(val favourites: MusicFavourites, val scope: CoroutineScope)

internal val LocalFavourites = staticCompositionLocalOf<FavouritesHost?> { null }

const val TRACK_LIKE_TAG = "track-like"

@Composable
internal fun TrackLikeHeart(songId: String, title: String) {
    val host = LocalFavourites.current ?: return
    val like = rememberSongLike(host.favourites, host.scope, songId, title)
    IconButton(
        onClick = like.flip,
        enabled = like.on != null && !like.busy,
        modifier = Modifier.testTag("$TRACK_LIKE_TAG-$songId"),
    ) {
        Icon(like.likeIcon, contentDescription = stringResource(like.likeLabel))
    }
}
