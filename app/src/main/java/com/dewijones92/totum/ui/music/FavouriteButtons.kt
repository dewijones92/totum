package com.dewijones92.totum.ui.music

import android.content.Context
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.LibraryAddCheck
import androidx.compose.material.icons.filled.PersonAddAlt1
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material.icons.outlined.LibraryAdd
import androidx.compose.material.icons.outlined.PersonAddAlt
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.dewijones92.totum.R
import com.dewijones92.totum.common.Diag
import com.dewijones92.totum.common.PoliteGate
import com.dewijones92.totum.innertube.actions.VideoRating
import com.dewijones92.totum.innertube.music.MusicAlbum
import com.dewijones92.totum.music.MusicFavourites
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

const val SAVE_ALBUM_TAG = "music-save-album"
const val FOLLOW_ARTIST_TAG = "music-follow-artist"

internal class FavouriteToggle(val on: Boolean?, val busy: Boolean, val flip: () -> Unit)

private enum class Change(val done: Int) {
    SAVE(R.string.music_favourite_done_save),
    UNSAVE(R.string.music_favourite_done_unsave),
    FOLLOW(R.string.music_favourite_done_follow),
    UNFOLLOW(R.string.music_favourite_done_unfollow),
    LIKE(R.string.music_favourite_done_like),
    UNLIKE(R.string.music_favourite_done_unlike),
}

private fun Context.say(outcome: MusicFavourites.Outcome, change: Change) {
    val text = when (outcome) {
        MusicFavourites.Outcome.DONE -> change.done
        MusicFavourites.Outcome.NOT_CONFIRMED -> R.string.music_favourite_not_confirmed
        MusicFavourites.Outcome.FAILED -> R.string.music_favourite_failed
        MusicFavourites.Outcome.SIGNED_OUT -> R.string.music_favourite_signed_out
        MusicFavourites.Outcome.NOTHING_TO_SAVE -> R.string.music_favourite_no_playlist
    }
    toast(this, getString(text))
}

@Composable
internal fun rememberAlbumSave(
    favourites: MusicFavourites,
    scope: CoroutineScope,
    album: MusicAlbum
): FavouriteToggle? {
    val shelf by favourites.shelf.collectAsStateWithLifecycle()
    LaunchedEffect(favourites) { favourites.loadOnce() }
    if (shelf.signedOut) return null
    val saved = if (shelf.loading || shelf.failed) null else shelf.albums.any { it.browseId == album.browseId }
    return rememberToggle(
        scope = scope,
        on = saved,
        confirmOff = stringResource(R.string.music_unsave_title, album.title) to R.string.music_unsave,
        what = "album ${album.browseId} \"${album.title}\"",
    ) { on ->
        val outcome = favourites.setAlbumSaved(album.browseId, album.playlistId, album.title, on)
        outcome to if (on) Change.SAVE else Change.UNSAVE
    }
}

@Composable
internal fun rememberArtistFollow(
    favourites: MusicFavourites,
    scope: CoroutineScope,
    browseId: String,
    name: String,
): FavouriteToggle? {
    val followed by remember(browseId) { favourites.followed(browseId) }.collectAsStateWithLifecycle(null)
    LaunchedEffect(browseId, followed == null) {
        if (followed == null) {
            Diag.log(
                "favourites",
                "no Follow on artist $browseId \"$name\": signed out or subscriptions not loaded yet"
            )
        }
    }
    val known = followed ?: return null
    return rememberToggle(
        scope = scope,
        on = known,
        confirmOff = stringResource(R.string.music_unfollow_title, name) to R.string.music_unfollow,
        what = "artist $browseId \"$name\"",
    ) { on ->
        val outcome = favourites.setArtistFollowed(browseId, name, on)
        outcome to if (on) Change.FOLLOW else Change.UNFOLLOW
    }
}

@Composable
internal fun rememberSongLike(
    favourites: MusicFavourites,
    scope: CoroutineScope,
    videoId: String,
    title: String,
): FavouriteToggle {
    var rating by remember(videoId) { mutableStateOf<VideoRating?>(null) }
    var known by remember(videoId) { mutableStateOf(false) }
    LaunchedEffect(videoId) {
        rating = withContext(PoliteGate.background()) { favourites.songRating(videoId) }
        known = true
    }
    return rememberToggle(
        scope = scope,
        on = if (known) rating == VideoRating.LIKE else null,
        confirmOff = null,
        what = "song $videoId \"$title\"",
        onWritten = { liked -> rating = if (liked) VideoRating.LIKE else VideoRating.NONE },
    ) { on ->
        val outcome = favourites.setSongLiked(videoId, title, on)
        outcome to if (on) Change.LIKE else Change.UNLIKE
    }
}

@Composable
private fun rememberToggle(
    scope: CoroutineScope,
    on: Boolean?,
    confirmOff: Pair<String, Int>?,
    what: String,
    onWritten: (Boolean) -> Unit = {},
    write: suspend (Boolean) -> Pair<MusicFavourites.Outcome, Change>,
): FavouriteToggle {
    val context = LocalContext.current.applicationContext
    var busy by remember { mutableStateOf(false) }
    var asking by remember { mutableStateOf(false) }
    val start: (Boolean) -> Unit = { target ->
        busy = true
        Diag.log("favourites", "tapped: ${if (target) "on" else "off"} for $what (was $on)")
        scope.launch {
            val (outcome, change) = write(target)
            if (outcome == MusicFavourites.Outcome.DONE) onWritten(target)
            busy = false
            context.say(outcome, change)
        }
    }
    if (asking && confirmOff != null) {
        AlertDialog(
            onDismissRequest = { asking = false },
            title = { Text(confirmOff.first) },
            confirmButton = {
                TextButton(onClick = {
                    asking = false
                    start(false)
                }) { Text(stringResource(confirmOff.second)) }
            },
            dismissButton = {
                TextButton(onClick = {
                    Diag.log("favourites", "kept $what at the question")
                    asking = false
                }) { Text(stringResource(R.string.music_keep)) }
            },
        )
    }
    return FavouriteToggle(on, busy) {
        when {
            busy || on == null -> Unit
            on && confirmOff != null -> asking = true
            else -> start(!on)
        }
    }
}

internal fun FavouriteToggle.saveButton(): MusicButton = MusicButton(
    labelRes = when {
        on == null || busy -> R.string.music_checking
        on -> R.string.music_saved
        else -> R.string.music_save
    },
    icon = if (on == true) Icons.Filled.LibraryAddCheck else Icons.Outlined.LibraryAdd,
    enabled = on != null && !busy,
    tag = SAVE_ALBUM_TAG,
    onClick = flip,
)

internal fun FavouriteToggle.followButton(): MusicButton = MusicButton(
    labelRes = when {
        busy -> R.string.music_checking
        on == true -> R.string.music_following
        else -> R.string.music_follow
    },
    icon = if (on == true) Icons.Filled.PersonAddAlt1 else Icons.Outlined.PersonAddAlt,
    enabled = on != null && !busy,
    tag = FOLLOW_ARTIST_TAG,
    onClick = flip,
)

internal val FavouriteToggle.likeIcon get() = if (on == true) Icons.Filled.Favorite else Icons.Outlined.FavoriteBorder

internal val FavouriteToggle.likeLabel: Int
    get() = when {
        on == null || busy -> R.string.music_checking
        on -> R.string.music_unlike
        else -> R.string.music_like
    }
