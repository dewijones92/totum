package com.dewijones92.totum.ui.library

import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.dewijones92.totum.R
import com.dewijones92.totum.domain.AlbumRef
import com.dewijones92.totum.domain.DownloadedMedia
import com.dewijones92.totum.domain.MediaContentKind
import com.dewijones92.totum.ui.common.FactEmoji
import com.dewijones92.totum.ui.music.CollectionRow
import com.dewijones92.totum.ui.music.MusicSectionTitle

data class AlbumOnPhone(val album: AlbumRef, val songs: List<DownloadedMedia>) {
    val key: String get() = album.id ?: album.title
}

/** Music downloads by album, each album's songs in the order they were downloaded, albums by title. */
fun albumsOnThisPhone(downloaded: List<DownloadedMedia>): List<AlbumOnPhone> =
    downloaded
        .filter { it.item.contentKind == MediaContentKind.MUSIC && it.item.album != null }
        .groupBy { it.item.album!!.id ?: it.item.album!!.title }
        .map { (_, songs) -> AlbumOnPhone(songs.first().item.album!!, songs) }
        .sortedBy { it.album.title.lowercase() }

/** The albums on the phone and what can be done to one, handed to the Library as one value. */
data class PhoneAlbums(
    val albums: List<AlbumOnPhone> = emptyList(),
    val onPlay: (AlbumOnPhone) -> Unit = {},
    val onDelete: (AlbumOnPhone) -> Unit = {},
)

internal fun LazyListScope.phoneAlbumsSection(phone: PhoneAlbums) {
    if (phone.albums.isEmpty()) return
    item(key = "phone-albums") {
        MusicSectionTitle("${FactEmoji.ALBUM} " + stringResource(R.string.library_albums_on_phone))
    }
    items(phone.albums, key = { "phone-album:" + it.key }) { album ->
        var confirming by remember { mutableStateOf(false) }
        CollectionRow(
            title = album.album.title,
            lines = listOfNotNull(
                "${FactEmoji.SONG} " +
                    pluralStringResource(R.plurals.library_album_songs, album.songs.size, album.songs.size),
                album.songs.first().item.author?.let { "${FactEmoji.ARTIST} $it" },
            ),
            artworkUrl = album.songs.first().item.thumbnailUrl,
            shape = RoundedCornerShape(8.dp),
            onOpen = { phone.onPlay(album) },
            trailing = {
                IconButton(onClick = { confirming = true }) {
                    Icon(Icons.Outlined.Delete, contentDescription = stringResource(R.string.library_album_remove))
                }
            },
        )
        if (confirming) {
            AlertDialog(
                onDismissRequest = { confirming = false },
                text = {
                    Text(
                        pluralStringResource(
                            R.plurals.library_album_remove_confirm,
                            album.songs.size,
                            album.songs.size,
                            album.album.title,
                        ),
                    )
                },
                confirmButton = {
                    TextButton(onClick = {
                        confirming = false
                        phone.onDelete(album)
                    }) { Text(stringResource(R.string.library_album_remove)) }
                },
                dismissButton = {
                    TextButton(
                        onClick = { confirming = false }
                    ) { Text(stringResource(android.R.string.cancel)) }
                },
            )
        }
    }
}
