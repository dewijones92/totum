package com.dewijones92.totum.ui.library

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.dewijones92.totum.R
import com.dewijones92.totum.common.Diag
import com.dewijones92.totum.data.phone.PhoneMediaSource
import com.dewijones92.totum.data.queue.QueueGroup
import com.dewijones92.totum.di.AppContainer
import com.dewijones92.totum.domain.PhoneMediaKind
import com.dewijones92.totum.domain.toPlayableOrNull
import com.dewijones92.totum.ui.common.LoadMoreOnScrollToEnd
import com.dewijones92.totum.ui.common.LoadingMoreFooter
import com.dewijones92.totum.ui.common.MediaItemRow
import com.dewijones92.totum.ui.common.rowFacts
import kotlinx.coroutines.launch

@Composable
internal fun MediaListTab(container: AppContainer, kind: PhoneMediaKind, source: PhoneMediaSource) {
    val subject = stringResource(
        if (kind == PhoneMediaKind.VIDEO) R.string.phone_tab_videos else R.string.phone_tab_audio
    )
    val browse = rememberPhoneBrowse(container, kind, source, subject)
    val state = browse.state
    val playables = remember(state.entries) { state.entries.mapNotNull { it.toPlayableOrNull() } }
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val groupTitle = browse.controls.folder?.let { stringResource(R.string.phone_group_folder, it) }
        ?: stringResource(if (kind == PhoneMediaKind.VIDEO) R.string.phone_group_videos else R.string.phone_group_audio)
    val group = QueueGroup("phone:${kind.key}:${browse.controls.folder ?: "all"}", groupTitle)
    LoadMoreOnScrollToEnd(
        listState = listState,
        enabled = state.hasMore && !state.loading,
        shownCount = playables.size,
        loadMore = { scope.launch { browse.browser.more() } },
    )
    LazyColumn(state = listState, modifier = Modifier.fillMaxSize().testTag(PHONE_LIST_TAG)) {
        item(key = "controls") {
            Column {
                PhoneControls(kind, browse)
                Text(
                    stringResource(R.string.phone_play_note),
                    Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
        item(key = "notes") { PhoneListNotes(browse) }
        items(playables, key = { it.item.id.value }) { playable ->
            MediaItemRow(
                item = playable.item,
                subtitleLines = rowFacts(playable.item, playable.pillar),
                pillar = playable.pillar,
                onPlay = {
                    Diag.log(
                        "phone-media",
                        "play ${playable.item.id.value} (${playable.item.title}) " +
                            "with ${playables.size} from \"${group.title}\"",
                    )
                    container.playbackQueue.playAll(playables, group, startAt = playable.item.id)
                },
            )
        }
        if (state.loading && playables.isNotEmpty()) item(key = "more") { LoadingMoreFooter() }
    }
}

const val PHONE_LIST_TAG = "phone-media-list"
