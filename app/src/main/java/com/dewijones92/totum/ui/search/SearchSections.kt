package com.dewijones92.totum.ui.search

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.dewijones92.totum.R
import com.dewijones92.totum.data.search.SearchHit
import com.dewijones92.totum.data.search.SearchSection
import com.dewijones92.totum.ui.search.SearchViewModel.Results

/**
 * How a search section is drawn, in every state it can be in.
 *
 * Its own file because the sections became a thing of their own on 2026-08-07: results now arrive
 * one source at a time, so a section has a lifecycle — still looking, answered, failed, absent —
 * where before it was a list and a boolean. One renderer serves all three sections, which is the
 * same reason there is one `SearchSource` and one `SearchHit`.
 */

/**
 * The home-server section, first in the list.
 *
 * First because it is the section a torrent search was FOR — putting it below podcasts and
 * videos would mean scrolling past both to reach the thing being looked for.
 */
internal fun LazyListScope.torrentSection(
    results: Results.Loaded,
    onPlayTorrent: (SearchHit.Torrent) -> Unit,
) {
    // Named rather than a generic error: the home server is only reachable at home or on
    // wg-home, so "cannot reach it" is usually a fact about where you are, not a fault.
    hitSection(
        title = { stringResource(R.string.search_section_torrents) },
        section = results.torrents,
        failure = { failed ->
            SectionMessage(
                stringResource(
                    if (failed.slow) R.string.search_torrents_slow else R.string.search_torrents_unreachable
                ),
            )
        },
    ) { hit -> TorrentHitRow(hit = hit, onPlay = { onPlayTorrent(hit) }) }
}

/**
 * One section of results, in whichever state it is in — the same rendering for all three.
 *
 * The states are the point. Each source answers on its own schedule now, so a section can be
 * **still searching** while the others are already listed, and the header appears for a section
 * that has not answered yet: seeing "Torrents" with a quiet bar under it says the app is still
 * looking, where an absent section says it found nothing. Before this the screen waited for every
 * source before drawing any of them, and a torrent search made a YouTube search feel broken.
 */
internal fun <T> LazyListScope.hitSection(
    title: @Composable () -> String,
    section: SearchSection<List<T>>,
    failure: @Composable (SearchSection.Failed) -> Unit = { SectionError() },
    row: @Composable (T) -> Unit,
) {
    // Absent is not a section at all: no home server means no torrent heading to explain away.
    if (section is SearchSection.Absent) return
    val items = section.itemsOrNull.orEmpty()
    // A source that answered with nothing says nothing; only a state worth reading gets a heading.
    val stillWaitingFor = (section as? SearchSection.Found)?.stillWaitingFor.orEmpty()
    if (section is SearchSection.Found && items.isEmpty() && stillWaitingFor.isEmpty()) return

    item { SectionHeader(title()) }
    when (section) {
        is SearchSection.Searching -> item { SectionSearching() }
        is SearchSection.Failed -> item { failure(section) }
        else -> Unit
    }
    items(items.size) { index -> row(items[index]) }
    if (stillWaitingFor.isNotEmpty()) {
        item {
            SectionMessage(stringResource(R.string.search_still_waiting, stillWaitingFor.joinToString()))
            SectionSearching()
        }
    }
}

@Composable
private fun SectionHeader(title: String, modifier: Modifier = Modifier) {
    Text(
        text = title,
        style = MaterialTheme.typography.titleMedium,
        modifier = modifier.padding(horizontal = 16.dp, vertical = 12.dp),
    )
}

/**
 * A section that has not answered yet.
 *
 * Deliberately quiet — a slim indeterminate bar rather than a spinner or a skeleton. The rest of
 * the results are already usable above and below it, and this must read as "more may arrive", not
 * as "the screen is busy".
 */
@Composable
internal fun SectionSearching() {
    LinearProgressIndicator(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
    )
}

@Composable
private fun SectionError(modifier: Modifier = Modifier) {
    Text(
        text = stringResource(R.string.search_section_failed),
        color = MaterialTheme.colorScheme.error,
        style = MaterialTheme.typography.bodySmall,
        modifier = modifier.padding(horizontal = 16.dp),
    )
}

/** A plain explanatory line for a section that could not load, in the section's own words. */
@Composable
internal fun SectionMessage(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier.padding(horizontal = 16.dp, vertical = 8.dp),
    )
}
