package com.dewijones92.totum.ui.music

import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.dewijones92.totum.R
import com.dewijones92.totum.common.Diag
import com.dewijones92.totum.common.HttpUrl
import com.dewijones92.totum.innertube.music.MusicAlbumRef
import com.dewijones92.totum.innertube.music.MusicReleaseKind
import com.dewijones92.totum.pins.Pin
import com.dewijones92.totum.ui.common.FactEmoji
import com.dewijones92.totum.ui.common.LocalPins
import com.dewijones92.totum.ui.common.MediaThumbnail
import com.dewijones92.totum.ui.common.PinMenu

@Composable
fun AlbumRow(album: MusicAlbumRef, onOpen: () -> Unit, modifier: Modifier = Modifier) {
    CollectionRow(
        title = album.title,
        lines = listOfNotNull(
            "${FactEmoji.ALBUM} " + listOfNotNull(stringResource(album.kind.labelRes), album.year)
                .joinToString(" • "),
            album.artist?.let { "${FactEmoji.ARTIST} $it" },
        ),
        artworkUrl = album.thumbnailUrl,
        shape = RoundedCornerShape(8.dp),
        onOpen = onOpen,
        modifier = modifier,
        pin = Pin.Album(album.browseId, album.title, album.thumbnailUrl?.value),
    )
}

@Composable
fun ArtistRow(
    name: String,
    subtitle: String?,
    artworkUrl: HttpUrl?,
    onOpen: () -> Unit,
    modifier: Modifier = Modifier,
    browseId: String? = null,
) {
    CollectionRow(
        title = name,
        lines = listOfNotNull(subtitle?.let { "${FactEmoji.ARTIST} $it" }),
        artworkUrl = artworkUrl,
        shape = CircleShape,
        onOpen = onOpen,
        modifier = modifier,
        pin = Pin.Artist(browseId, name, artworkUrl?.value, radioPlaylistId = null),
    )
}

@Composable
fun PlaylistRow(
    title: String,
    countText: String?,
    artworkUrl: HttpUrl?,
    onOpen: () -> Unit,
    modifier: Modifier = Modifier,
    cover: (@Composable () -> Unit)? = null,
    browseId: String? = null,
) {
    CollectionRow(
        title = title,
        lines = listOfNotNull(countText?.let { "${FactEmoji.SONG} $it" }),
        artworkUrl = artworkUrl,
        shape = RoundedCornerShape(8.dp),
        onOpen = onOpen,
        modifier = modifier,
        cover = cover,
        pin = browseId?.let { Pin.Playlist(it, title, artworkUrl?.value, music = true) },
    )
}

@Composable
fun HeartCover() {
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .size(ART_SIZE)
            .background(MaterialTheme.colorScheme.primary, RoundedCornerShape(8.dp)),
    ) {
        Icon(
            Icons.Filled.Favorite,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onPrimary,
            modifier = Modifier.size(HEART_SIZE),
        )
    }
}

private val HEART_SIZE = 36.dp

@Composable
internal fun CollectionRow(
    title: String,
    lines: List<String>,
    artworkUrl: HttpUrl?,
    shape: Shape,
    onOpen: () -> Unit,
    modifier: Modifier = Modifier,
    trailing: (@Composable () -> Unit)? = null,
    cover: (@Composable () -> Unit)? = null,
    pin: Pin? = null,
) {
    val pins = LocalPins.current
    var menu by remember { mutableStateOf(false) }
    if (menu && pin != null && pins != null) PinMenu(pin, pins, inRecent = false) { menu = false }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
            .fillMaxWidth()
            .combinedClickable(
                onClick = onOpen,
                onLongClick = if (pin != null && pins != null) {
                    {
                        Diag.log("music", "long-pressed ${pin.key} \"$title\"")
                        menu = true
                    }
                } else {
                    null
                },
            )
            .padding(horizontal = 16.dp, vertical = 8.dp),
    ) {
        if (cover != null) {
            cover()
        } else {
            MediaThumbnail(
                url = artworkUrl,
                contentDescription = title,
                shape = shape,
                modifier = Modifier.size(ART_SIZE)
            )
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(text = title, style = MaterialTheme.typography.bodyLarge)
            lines.forEach { line ->
                Text(
                    text = line,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        trailing?.invoke()
    }
}

val MusicReleaseKind.labelRes: Int
    get() = when (this) {
        MusicReleaseKind.ALBUM -> R.string.music_kind_album
        MusicReleaseKind.SINGLE -> R.string.music_kind_single
        MusicReleaseKind.EP -> R.string.music_kind_ep
    }

private val ART_SIZE = 56.dp
