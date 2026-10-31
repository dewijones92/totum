package com.dewijones92.totum.ui.search

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.dewijones92.totum.R
import com.dewijones92.totum.data.search.SearchHit
import com.dewijones92.totum.data.search.toSource
import com.dewijones92.totum.ui.common.LocalOpenSource
import com.dewijones92.totum.ui.common.MediaThumbnail
import com.dewijones92.totum.ui.search.SearchViewModel.Results

internal fun LazyListScope.bookSections(
    results: Results.Loaded,
    onPlayBook: (SearchHit.Book) -> Unit,
    onPlayTorrent: (SearchHit.Torrent) -> Unit,
    onDownloadTorrent: (SearchHit.Torrent) -> Unit,
) {
    hitSection(
        title = { stringResource(R.string.search_section_audiobooks) },
        section = results.books,
    ) { hit -> BookHitRow(hit, onPlay = { onPlayBook(hit) }) }
    hitSection(
        title = { stringResource(R.string.search_section_audiobook_torrents) },
        section = results.bookTorrents,
        failure = { failed ->
            SectionMessage(
                stringResource(
                    if (failed.slow) R.string.search_torrents_slow else R.string.search_torrents_unreachable
                ),
            )
        },
    ) { hit ->
        TorrentHitRow(hit = hit, onPlay = { onPlayTorrent(hit) }, onDownload = { onDownloadTorrent(hit) })
    }
}

@Composable
private fun BookHitRow(
    hit: SearchHit.Book,
    onPlay: () -> Unit,
    modifier: Modifier = Modifier,
    onOpen: (() -> Unit)? = LocalOpenSource.current?.let { open -> { open(hit.toSource()) } },
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
            .fillMaxWidth()
            .clickable(enabled = onOpen != null) { onOpen?.invoke() }
            .padding(horizontal = 16.dp, vertical = 8.dp)
            .testTag("$BOOK_ROW_TAG-${hit.identifier}"),
    ) {
        MediaThumbnail(url = hit.artworkUrl, contentDescription = hit.title, modifier = Modifier.size(BOOK_COVER))
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(hit.title, style = MaterialTheme.typography.bodyLarge)
            hit.subtitle?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        TextButton(onClick = onPlay, modifier = Modifier.testTag("$BOOK_PLAY_TAG-${hit.identifier}")) {
            Text(stringResource(R.string.book_play))
        }
    }
}

internal const val BOOK_ROW_TAG = "book-row"
internal const val BOOK_PLAY_TAG = "book-play"
private val BOOK_COVER = 56.dp
