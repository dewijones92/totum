package com.dewijones92.totum.ui.player

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SecondaryScrollableTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.dewijones92.totum.R
import com.dewijones92.totum.common.Diag
import com.dewijones92.totum.domain.MediaItem
import com.dewijones92.totum.domain.MediaKind
import com.dewijones92.totum.playback.PlaybackState
import com.dewijones92.totum.ui.common.ExploreToggle
import com.dewijones92.totum.ui.common.LocalExploreMode
import com.dewijones92.totum.ui.player.WatchViewModel.CommentsState
import com.dewijones92.totum.ui.player.WatchViewModel.RelatedState

enum class PlayerTab(@param:StringRes val label: Int) {
    UP_NEXT(R.string.player_tab_up_next),
    RELATED(R.string.player_tab_related),
    COMMENTS(R.string.player_tab_comments),
    TRANSCRIPT(R.string.player_tab_transcript),
    DETAILS(R.string.player_tab_details),
}

internal fun playerTabsFor(kind: MediaKind, hasTranscript: Boolean = false): List<PlayerTab> = when (kind) {
    MediaKind.VIDEO -> PlayerTab.entries
    MediaKind.PODCAST -> listOf(PlayerTab.UP_NEXT, PlayerTab.TRANSCRIPT, PlayerTab.DETAILS)
}.filter { hasTranscript || it != PlayerTab.TRANSCRIPT }

@Suppress("LongParameterList")
internal class PlayerTabContent(
    val queue: QueueControls,
    val related: RelatedState,
    val onPlayRelated: (MediaItem) -> Unit,
    val comments: CommentsState,
    val replies: CommentReplies,
    val watchActions: WatchActions,
    val onSeekTo: (Long) -> Unit,
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun PlayerTabs(state: PlaybackState, content: PlayerTabContent) {
    val tabs = playerTabsFor(state.kind, rememberHasTranscript(state))
    var chosen by rememberSaveable { mutableStateOf(PlayerTab.UP_NEXT) }
    val selected = chosen.takeIf { it in tabs } ?: PlayerTab.UP_NEXT
    Row(verticalAlignment = Alignment.CenterVertically) {
        SecondaryScrollableTabRow(
            selectedTabIndex = tabs.indexOf(selected),
            edgePadding = 0.dp,
            modifier = Modifier.weight(1f),
        ) {
            tabs.forEach { tab ->
                Tab(
                    selected = tab == selected,
                    onClick = {
                        Diag.log("player", "tab ${selected.name} -> ${tab.name}")
                        chosen = tab
                    },
                    text = { Text(stringResource(tab.label), style = MaterialTheme.typography.labelLarge) },
                    modifier = Modifier.testTag(PLAYER_TAB_TAG + tab.name),
                )
            }
        }
    }
    Spacer(Modifier.height(16.dp))
    Column(Modifier.fillMaxWidth()) {
        when (selected) {
            PlayerTab.UP_NEXT -> {
                ExploreLine()
                UpNextOrEmpty(content.queue)
            }
            PlayerTab.RELATED -> {
                ExploreLine()
                RelatedSection(content.related, content.onPlayRelated)
            }
            PlayerTab.COMMENTS -> {
                LaunchedEffect(state.itemId) { content.replies.onWanted() }
                CommentsSection(content.comments, content.watchActions, content.replies)
            }
            PlayerTab.TRANSCRIPT -> TranscriptSection(state, content.onSeekTo)
            PlayerTab.DETAILS -> {
                ItemFactsCard(state, detailsPublished(state, content.related))
                LyricsSection()
                NotesChaptersAndSponsors(state, content.onSeekTo, detailsDescription(state, content.related))
                SourceSkipsSection(state)
            }
        }
    }
}

@Composable
private fun ExploreLine() {
    val explore = LocalExploreMode.current ?: return
    val on by explore.on.collectAsStateWithLifecycle()
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        Text(
            text = stringResource(if (on) R.string.explore_line_on else R.string.explore_line_off),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
        ExploreToggle("player")
    }
}

@Composable
private fun UpNextOrEmpty(queue: QueueControls) {
    if (queue.upNext.isEmpty()) {
        Text(stringResource(R.string.player_up_next_empty))
    } else {
        UpNextSection(queue)
    }
}

const val PLAYER_TAB_TAG = "player-tab:"

internal fun detailsPublished(state: PlaybackState, related: RelatedState): java.time.Instant? =
    (related as? RelatedState.Loaded)
        ?.takeIf { state.kind == MediaKind.VIDEO && it.videoId == state.itemId.value }
        ?.publishedOn?.let(com.dewijones92.totum.domain.PublishedAge::ofDay)

internal fun detailsDescription(state: PlaybackState, related: RelatedState): String? =
    (related as? RelatedState.Loaded)
        ?.takeIf { state.kind == MediaKind.VIDEO && it.videoId == state.itemId.value }
        ?.description
