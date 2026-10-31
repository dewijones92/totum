package com.dewijones92.totum.ui.podcasts

import android.content.res.Resources
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.dewijones92.totum.R
import com.dewijones92.totum.common.Diag
import com.dewijones92.totum.di.AppContainer
import com.dewijones92.totum.domain.MediaItem
import com.dewijones92.totum.domain.MediaItemId
import com.dewijones92.totum.domain.MediaSource
import com.dewijones92.totum.domain.PlayHandle
import com.dewijones92.totum.domain.PlayState
import com.dewijones92.totum.domain.PlayableItem
import com.dewijones92.totum.domain.whereToResume
import com.dewijones92.totum.ui.common.LocalPlayStates
import kotlinx.coroutines.launch

@Composable
internal fun BookActions(container: AppContainer, book: MediaSource.PodcastFeed, chapters: List<MediaItem>) {
    val plays = LocalPlayStates.current
    val resumeAt by remember(chapters) {
        derivedStateOf {
            chapters.takeIf { list -> list.any { (plays.value[it.id] ?: PlayState.Unplayed) != PlayState.Unplayed } }
                ?.let { list -> whereToResume(list.map { it.id }, plays.value) }
        }
    }
    val resources = LocalResources.current
    val play: (MediaItemId?) -> Unit = { startAt -> playBook(container, resources, book, chapters, startAt) }
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
    ) {
        Button(onClick = { play(null) }, modifier = Modifier.testTag(BOOK_PLAY_TAG)) {
            val chapter = chapters.indexOfFirst { it.id == resumeAt }
            Text(
                if (chapter < 0) {
                    stringResource(R.string.book_play_from_start)
                } else {
                    stringResource(R.string.book_resume, chapter + 1, chapters.size)
                },
            )
        }
        OutlinedButton(
            onClick = {
                Diag.log("audiobook", "\"${book.title}\": choose chapters to download (${chapters.size})")
                container.episodeDownloads.ask(book.title, chapters.map { PlayableItem(it, PlayHandle.Podcast()) })
            },
            modifier = Modifier.testTag(BOOK_DOWNLOAD_TAG),
        ) { Text(stringResource(R.string.book_download)) }
    }
}

internal const val BOOK_PLAY_TAG = "book-page-play"
internal const val BOOK_DOWNLOAD_TAG = "book-page-download"

@Composable
internal fun rememberBookChapterPlayer(
    container: AppContainer,
    book: Pair<MediaSource.PodcastFeed, List<MediaItem>>?,
): ((MediaItem) -> Unit)? {
    val resources = LocalResources.current
    return remember(book) {
        book?.let { (source, chapters) ->
            {
                    chapter: MediaItem ->
                Diag.log(
                    "audiobook",
                    "\"${source.title}\": chapter \"${chapter.title}\" tapped; playing the book from it"
                )
                playBook(container, resources, source, chapters, chapter.id)
            }
        }
    }
}

private fun playBook(
    container: AppContainer,
    resources: Resources,
    book: MediaSource.PodcastFeed,
    chapters: List<MediaItem>,
    startAt: MediaItemId?,
) {
    val label = resources.getString(R.string.book_opening_strip, book.title)
    container.applicationScope.launch { container.bookPlayer.play(book, label, chapters, startAt) }
}
