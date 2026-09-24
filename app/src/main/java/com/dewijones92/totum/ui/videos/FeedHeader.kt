package com.dewijones92.totum.ui.videos

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AssistChip
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.dewijones92.totum.R
import com.dewijones92.totum.domain.MediaFilter
import com.dewijones92.totum.domain.MediaSource
import com.dewijones92.totum.innertube.feeds.AccountFeed
import com.dewijones92.totum.ui.common.MediaFilterChips
import com.dewijones92.totum.ui.common.MediaSort
import com.dewijones92.totum.ui.common.SectionHeaderWithSort
import com.dewijones92.totum.ui.common.SourceChip

internal fun LazyListScope.feedHeader(
    state: VideosViewModel.UiState,
    onChannelClick: (MediaSource.VideoChannel) -> Unit,
    selector: @Composable () -> Unit,
) {
    if (state.subscriptions.isNotEmpty()) {
        item { SubscriptionChips(state.subscriptions, onChannelClick) }
    }
    // Signed in OR holding groups. The account feeds need an account, but a group can be
    // all podcasts and needs none — gating the whole selector on sign-in hid every group
    // Dewi had made, which is a strange way to treat the one part that was still working.
    if (state.signedIn || state.groups.isNotEmpty()) {
        item { selector() }
    }
}

@Composable
internal fun FeedSelector(
    state: VideosViewModel.UiState,
    onSelectFeed: (FeedChoice?) -> Unit,
    onOpenPlaylists: () -> Unit,
    onOpenShorts: () -> Unit,
) {
    LazyRow(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        contentPadding = PaddingValues(horizontal = 16.dp),
        modifier = Modifier.padding(top = 8.dp),
    ) {
        // YouTube's own feeds need a signed-in account; groups do not.
        items(if (state.signedIn) AccountFeed.entries else emptyList()) { feed ->
            FilterChip(
                selected = state.selected == FeedChoice.Account(feed),
                onClick = { onSelectFeed(FeedChoice.Account(feed)) },
                label = { Text(stringResource(feedChipRes(feed))) },
            )
        }
        // Dewi's own groups sit alongside YouTube's feeds rather than behind a sub-tab:
        // they are the same kind of thing to choose between — "what am I looking at" —
        // and a group he made is likelier to be what he wants than HISTORY.
        items(state.groups, key = { it.id.value }) { group ->
            FilterChip(
                selected = (state.selected as? FeedChoice.Group)?.group?.id == group.id,
                onClick = { onSelectFeed(FeedChoice.Group(group)) },
                label = { Text(group.name) },
            )
        }
        // Not feed filters — open the Shorts reel and the playlists list.
        item {
            AssistChip(
                onClick = onOpenShorts,
                label = { Text(stringResource(R.string.shorts_title)) },
            )
        }
        item {
            AssistChip(
                onClick = onOpenPlaylists,
                label = { Text(stringResource(R.string.playlists_title)) },
            )
        }
    }
}

private fun feedChipRes(feed: AccountFeed): Int = when (feed) {
    AccountFeed.RECOMMENDED -> R.string.feed_home
    AccountFeed.SUBSCRIPTIONS -> R.string.feed_subscriptions
    AccountFeed.WATCH_LATER -> R.string.feed_watch_later
    AccountFeed.HISTORY -> R.string.feed_history
}

/**
 * Where the Videos tab was, for the place trail.
 *
 * The item count is here for a reason: a restored scroll index cannot survive being applied
 * to an empty list, so "scroll=40 videos=0" and "scroll=0 videos=40" are different bugs
 * needing different fixes, and without the count they look identical in a report.
 */
/** The horizontal strip of subscribed channels above the feed. */

@Composable
private fun SubscriptionChips(
    subscriptions: List<MediaSource.VideoChannel>,
    onChannelClick: (MediaSource.VideoChannel) -> Unit,
) {
    LazyRow(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        contentPadding = PaddingValues(horizontal = 16.dp),
    ) {
        items(subscriptions) { channel ->
            SourceChip(channel, onClick = { onChannelClick(channel) })
        }
    }
}

@Composable
internal fun feedTitle(selected: FeedChoice?): String = when (selected) {
    null -> stringResource(R.string.latest_videos)
    is FeedChoice.Account -> stringResource(feedChipRes(selected.feed))
    // The group's own name, which is the whole point of having named it.
    is FeedChoice.Group -> selected.group.name
}

internal fun LazyListScope.sortAndFilter(
    state: VideosViewModel.UiState,
    onSetSort: (MediaSort) -> Unit,
    filter: MediaFilter,
    onSetFilter: (MediaFilter) -> Unit,
) {
    item { SectionHeaderWithSort(title = feedTitle(state.selected), sort = state.sort, onSetSort = onSetSort) }
    item { MediaFilterChips(selected = filter, onSelect = onSetFilter) }
}
