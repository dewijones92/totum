package com.dewijones92.totum.ui.search

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.dewijones92.totum.R
import com.dewijones92.totum.common.Diag
import com.dewijones92.totum.data.search.SearchHit
import com.dewijones92.totum.data.search.TorrentPicks
import com.dewijones92.totum.data.torrent.TorrentProgress
import com.dewijones92.totum.ui.common.LocalPlayStates

@Stable
internal class TorrentShelf {
    var resolution by mutableStateOf<String?>(null)
    var trustedOnly by mutableStateOf(false)
    var shown by mutableIntStateOf(PAGE)

    fun showMore() {
        shown += PAGE
    }

    companion object {
        const val PAGE = 8
    }
}

@Composable
internal fun rememberTorrentShelf(query: String): TorrentShelf = remember(query) { TorrentShelf() }

@Composable
internal fun TorrentControls(all: List<SearchHit.Torrent>, shelf: TorrentShelf, onPlay: (SearchHit.Torrent) -> Unit) {
    val best = TorrentPicks.best(TorrentPicks.filtered(all, shelf.resolution, shelf.trustedOnly))
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp).testTag(TORRENT_CONTROLS_TAG)) {
        best?.let { pick ->
            Button(
                onClick = {
                    Diag.log(
                        "search",
                        "best copy chosen: \"${pick.title}\" (${pick.resolution}, trusted=${pick.trusted}, " +
                            "${pick.seeders} seeders)",
                    )
                    onPlay(pick)
                },
                modifier = Modifier.fillMaxWidth().testTag(TORRENT_BEST_TAG),
            ) {
                Text(
                    stringResource(
                        R.string.torrent_best_copy,
                        listOfNotNull(
                            pick.resolution,
                            stringResource(R.string.torrent_best_trusted).takeIf { pick.trusted },
                            stringResource(R.string.torrent_best_seeders, pick.seeders),
                        ).joinToString(" · "),
                    ),
                )
            }
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            FilterChip(
                selected = shelf.resolution == null,
                onClick = { shelf.resolution = null },
                label = { Text(stringResource(R.string.torrent_filter_all, all.size)) },
            )
            TorrentPicks.RESOLUTIONS.forEach { resolution ->
                val count = all.count { it.resolution == resolution }
                if (count > 0) {
                    FilterChip(
                        selected = shelf.resolution == resolution,
                        onClick = {
                            Diag.log("search", "torrents filtered to $resolution ($count)")
                            shelf.resolution = resolution.takeUnless { shelf.resolution == it }
                        },
                        label = { Text("$resolution ($count)") },
                        modifier = Modifier.testTag("$TORRENT_FILTER_TAG-$resolution"),
                    )
                }
            }
            FilterChip(
                selected = shelf.trustedOnly,
                onClick = { shelf.trustedOnly = !shelf.trustedOnly },
                label = { Text(stringResource(R.string.torrent_filter_trusted, all.count { it.trusted })) },
                modifier = Modifier.testTag(TORRENT_TRUSTED_TAG),
            )
        }
    }
}

internal const val TORRENT_CONTROLS_TAG = "torrent-controls"
internal const val TORRENT_BEST_TAG = "torrent-best"
internal const val TORRENT_FILTER_TAG = "torrent-filter"
internal const val TORRENT_TRUSTED_TAG = "torrent-trusted-only"

@Composable
internal fun rememberTorrentProgress(viewModel: SearchViewModel): (SearchHit.Torrent) -> List<String> {
    val seen by viewModel.torrentsSeen.collectAsStateWithLifecycle()
    val plays = LocalPlayStates.current
    val played by remember(plays) { derivedStateOf { TorrentProgress.indexOf(plays.value) } }
    val seenKeys = remember(seen) { TorrentProgress.keysOf(seen) }
    return remember(seen, seenKeys, played) {
        {
                hit: SearchHit.Torrent ->
            TorrentProgress.chips(hit.title, hit.link, played, seen, seenKeys)
        }
    }
}
