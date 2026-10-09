package com.dewijones92.totum.ui.music

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.dewijones92.totum.R
import com.dewijones92.totum.common.HttpUrl
import com.dewijones92.totum.innertube.music.MusicAlbumRef
import com.dewijones92.totum.innertube.music.MusicReleaseKind
import com.dewijones92.totum.ui.common.FactEmoji
import com.dewijones92.totum.ui.common.MediaThumbnail

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
    )
}

@Composable
fun ArtistRow(
    name: String,
    subtitle: String?,
    artworkUrl: HttpUrl?,
    onOpen: () -> Unit,
    modifier: Modifier = Modifier
) {
    CollectionRow(
        title = name,
        lines = listOfNotNull(subtitle?.let { "${FactEmoji.ARTIST} $it" }),
        artworkUrl = artworkUrl,
        shape = CircleShape,
        onOpen = onOpen,
        modifier = modifier,
    )
}

@Composable
fun PlaylistRow(
    title: String,
    countText: String?,
    artworkUrl: HttpUrl?,
    onOpen: () -> Unit,
    modifier: Modifier = Modifier
) {
    CollectionRow(
        title = title,
        lines = listOfNotNull(countText?.let { "${FactEmoji.SONG} $it" }),
        artworkUrl = artworkUrl,
        shape = RoundedCornerShape(8.dp),
        onOpen = onOpen,
        modifier = modifier,
    )
}

@Composable
private fun CollectionRow(
    title: String,
    lines: List<String>,
    artworkUrl: HttpUrl?,
    shape: Shape,
    onOpen: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
            .fillMaxWidth()
            .clickable(onClick = onOpen)
            .padding(horizontal = 16.dp, vertical = 8.dp),
    ) {
        MediaThumbnail(url = artworkUrl, contentDescription = title, shape = shape, modifier = Modifier.size(ART_SIZE))
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
    }
}

val MusicReleaseKind.labelRes: Int
    get() = when (this) {
        MusicReleaseKind.ALBUM -> R.string.music_kind_album
        MusicReleaseKind.SINGLE -> R.string.music_kind_single
        MusicReleaseKind.EP -> R.string.music_kind_ep
    }

private val ART_SIZE = 56.dp
