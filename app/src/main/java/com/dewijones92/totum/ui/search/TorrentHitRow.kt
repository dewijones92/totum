package com.dewijones92.totum.ui.search

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.DownloadForOffline
import androidx.compose.material.icons.outlined.Headphones
import androidx.compose.material.icons.outlined.Movie
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.dewijones92.totum.R
import com.dewijones92.totum.data.search.SearchHit
import com.dewijones92.totum.data.torrent.TorrentCategory
import com.dewijones92.totum.data.torrent.TorrentProgress
import com.dewijones92.totum.domain.DownloadState
import com.dewijones92.totum.domain.Transfer
import com.dewijones92.totum.domain.TransferPlace
import com.dewijones92.totum.playback.cast.soundOnlyOnTheTvByName
import com.dewijones92.totum.ui.cast.LocalScreenControl
import com.dewijones92.totum.ui.common.LocalDownloadStates
import com.dewijones92.totum.ui.common.LocalHomeDownloads
import com.dewijones92.totum.ui.common.LocalHomeJobs
import com.dewijones92.totum.ui.common.MediaThumbnail
import com.dewijones92.totum.ui.common.breakableAtDotsAndUnderscores
import com.dewijones92.totum.ui.common.transferText

/**
 * One torrent result.
 *
 * The subtitle is seeders and size, because with twenty copies of one film that IS the decision —
 * a result with no seeders never plays however good its name looks. Tapping queues everything
 * playable inside it, which for a season pack is the whole season.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun TorrentHitRow(
    hit: SearchHit.Torrent,
    onPlay: () -> Unit,
    modifier: Modifier = Modifier,
    onDownload: (() -> Unit)? = null,
) {
    val casting = LocalScreenControl.current?.castDevice?.collectAsStateWithLifecycle()?.value
    val soundOnly = casting?.let { soundOnlyOnTheTvByName(hit.title) }
    val progress = LocalTorrentProgress.current(hit)
    val menu = LocalTorrentMenu.current
    val homeJobs = LocalHomeJobs.current.value
    val local = LocalDownloadStates.current.value
    val atHome = remember(hit, homeJobs, local) {
        torrentAtHome(TorrentProgress.hashOf(hit.link), homeJobs) { local[it] ?: DownloadState.NotDownloaded }
    }
    val homeChip = atHome?.let { torrentHomeWords(it) }
    val chips = listOfNotNull(homeChip) + progress + hit.trust
    ListItem(
        headlineContent = { Text(hit.title.breakableAtDotsAndUnderscores()) },
        supportingContent = {
            Column {
                hit.subtitle?.let { Text(it) }
                if (chips.isNotEmpty()) TrustChips(chips)
                soundOnly?.let {
                    Text(
                        stringResource(R.string.torrent_sound_only_on_tv, it),
                        color = MaterialTheme.colorScheme.tertiary,
                        modifier = Modifier.testTag(TORRENT_SOUND_ONLY_TAG),
                    )
                }
            }
        },
        leadingContent = { TorrentPoster(hit) },
        trailingContent = { TorrentActions(atHome, onDownload) },
        modifier = modifier.combinedClickable(onClick = onPlay, onLongClick = menu?.let { open -> { open(hit) } }),
    )
}

internal val LocalTorrentProgress = compositionLocalOf<(SearchHit.Torrent) -> List<String>> { { emptyList() } }

internal val LocalTorrentMenu = compositionLocalOf<((SearchHit.Torrent) -> Unit)?> { null }

@Composable
private fun torrentHomeWords(atHome: TorrentAtHome): String = when (atHome) {
    is TorrentAtHome.Ready -> stringResource(R.string.torrent_at_home)
    is TorrentAtHome.Downloading ->
        transferText(Transfer.running(TransferPlace.WEB, TransferPlace.PI, atHome.percent))
    TorrentAtHome.AlsoOnPhone -> stringResource(R.string.home_state_done_on_phone)
}

internal const val TORRENT_COPY_HOME_TAG = "torrent-copy-home"
internal const val TORRENT_SOUND_ONLY_TAG = "torrent-sound-only"
internal const val TORRENT_POSTER_TAG = "torrent-poster"
private val TORRENT_POSTER_WIDTH = 48.dp
private val TORRENT_POSTER_HEIGHT = 72.dp

@Composable
private fun TorrentPoster(hit: SearchHit.Torrent) {
    val poster = hit.artworkUrl
    if (poster != null) {
        MediaThumbnail(
            url = poster,
            contentDescription = null,
            shape = RoundedCornerShape(8.dp),
            modifier = Modifier.size(TORRENT_POSTER_WIDTH, TORRENT_POSTER_HEIGHT).testTag(TORRENT_POSTER_TAG),
        )
    } else {
        Box(Modifier.size(TORRENT_POSTER_WIDTH, TORRENT_POSTER_HEIGHT), contentAlignment = Alignment.Center) {
            Icon(
                imageVector = when (hit.category) {
                    TorrentCategory.AUDIOBOOKS -> Icons.Outlined.Headphones
                    TorrentCategory.ANY -> Icons.Outlined.Movie
                },
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
            )
        }
    }
}

@Composable
private fun TorrentActions(atHome: TorrentAtHome?, onDownload: (() -> Unit)?) {
    Row {
        val home = LocalHomeDownloads.current
        if (atHome is TorrentAtHome.Ready && home != null) {
            IconButton(
                onClick = { atHome.copyable.forEach(home::copyNow) },
                modifier = Modifier.testTag(TORRENT_COPY_HOME_TAG),
            ) {
                Icon(
                    Icons.Outlined.DownloadForOffline,
                    contentDescription = stringResource(R.string.home_copy_now)
                )
            }
        }
        onDownload?.let { download ->
            IconButton(onClick = download) {
                Icon(
                    Icons.Outlined.Download,
                    contentDescription = stringResource(R.string.torrent_download_episodes)
                )
            }
        }
    }
}
