package com.dewijones92.totum.ui.search

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.dewijones92.totum.R
import com.dewijones92.totum.data.audiobook.asItem
import com.dewijones92.totum.data.search.AudiobookPicks
import com.dewijones92.totum.data.search.SearchHit
import com.dewijones92.totum.data.search.SearchSection
import com.dewijones92.totum.data.search.toSource
import com.dewijones92.totum.domain.MediaKind
import com.dewijones92.totum.ui.common.ActionSheet
import com.dewijones92.totum.ui.common.LocalOpenSource
import com.dewijones92.totum.ui.common.MediaThumbnail
import com.dewijones92.totum.ui.common.SheetExtras
import com.dewijones92.totum.ui.search.SearchViewModel.Results

internal class BookSections(
    val onPlayBook: (SearchHit.Book) -> Unit,
    val onPlayTorrent: (SearchHit.Torrent) -> Unit,
    val onDownloadTorrent: (SearchHit.Torrent) -> Unit,
)

internal fun LazyListScope.bookSections(results: Results.Loaded, query: String, shelf: TorrentShelf, on: BookSections) {
    val merged = mergedBooks(results, query)
    val section = (merged as? SearchSection.Found)?.copy(items = merged.items.take(shelf.shown)) ?: merged
    hitSection(
        title = { stringResource(R.string.search_section_audiobooks) },
        section = section,
        failure = { failed ->
            SectionMessage(
                stringResource(
                    if (failed.slow) R.string.search_torrents_slow else R.string.search_torrents_unreachable
                ),
            )
        },
    ) { hit ->
        when (hit) {
            is SearchHit.Book -> BookHitRow(hit, onPlay = { on.onPlayBook(hit) })
            is SearchHit.Torrent ->
                TorrentHitRow(hit = hit, onPlay = { on.onPlayTorrent(hit) }, onDownload = { on.onDownloadTorrent(hit) })
            else -> Unit
        }
    }
    val hidden = merged.itemsOrNull.orEmpty().size - shelf.shown
    if (hidden > 0) {
        item {
            TextButton(onClick = shelf::showMore, modifier = Modifier.padding(horizontal = 8.dp)) {
                val more = minOf(hidden, TorrentShelf.PAGE)
                Text(pluralStringResource(R.plurals.torrent_show_more, more, more))
            }
        }
    }
}

internal fun mergedBooks(results: Results.Loaded, query: String): SearchSection<List<SearchHit>> {
    val parts = listOf(results.books, results.bookTorrents).filterNot { it is SearchSection.Absent }
    if (parts.isEmpty()) return SearchSection.Absent
    val items = AudiobookPicks.merged(
        query,
        results.books.itemsOrNull.orEmpty(),
        results.bookTorrents.itemsOrNull.orEmpty(),
    )
    val waiting = buildList {
        if (results.books.isSearching) add(FREE_BOOKS)
        if (results.bookTorrents.isSearching) add(BOOK_TORRENTS)
        addAll((results.bookTorrents as? SearchSection.Found)?.stillWaitingFor.orEmpty())
    }
    return when {
        items.isEmpty() && parts.all { it.isSearching } -> SearchSection.Searching
        items.isEmpty() && parts.all { it is SearchSection.Failed } -> parts.first()
        else -> SearchSection.Found(items, waiting)
    }
}

private const val FREE_BOOKS = "free books"
private const val BOOK_TORRENTS = "audiobook torrents"

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun BookHitRow(
    hit: SearchHit.Book,
    onPlay: () -> Unit,
    modifier: Modifier = Modifier,
    onOpen: (() -> Unit)? = LocalOpenSource.current?.let { open -> { open(hit.toSource()) } },
) {
    var menu by remember { mutableStateOf(false) }
    if (menu) BookMenuSheet(hit) { menu = false }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
            .fillMaxWidth()
            .combinedClickable(onClick = { onOpen?.invoke() }, onLongClick = { menu = true })
            .padding(horizontal = 16.dp, vertical = 8.dp)
            .testTag("$BOOK_ROW_TAG-${hit.identifier}"),
    ) {
        MediaThumbnail(url = hit.artworkUrl, contentDescription = hit.title, modifier = Modifier.size(BOOK_COVER))
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(hit.title, style = MaterialTheme.typography.bodyLarge)
            Text(
                stringResource(R.string.book_free, hit.subtitle.orEmpty()),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        TextButton(onClick = onPlay, modifier = Modifier.testTag("$BOOK_PLAY_TAG-${hit.identifier}")) {
            Text(stringResource(R.string.book_play))
        }
    }
}

@Composable
private fun BookMenuSheet(hit: SearchHit.Book, onDismiss: () -> Unit) {
    ActionSheet(
        title = hit.title,
        onPlayInsteadOfCurrent = null,
        onPlayNext = null,
        onAddToQueue = null,
        onAddToPlaylist = null,
        onRemoveFromPlaylist = null,
        onRemoveFromQueue = null,
        onPeek = null,
        onDownloadVideo = null,
        onDownload = null,
        onDeleteDownload = null,
        onSwitchMode = null,
        audioMode = false,
        onGoToSource = null,
        sourcePillar = MediaKind.PODCAST,
        onMoveToTop = null,
        onMoveToBottom = null,
        onSetPlayed = null,
        played = false,
        onDismiss = onDismiss,
        extras = SheetExtras(item = hit.asItem()),
    )
}

internal const val BOOK_ROW_TAG = "book-row"
internal const val BOOK_PLAY_TAG = "book-play"
private val BOOK_COVER = 56.dp
