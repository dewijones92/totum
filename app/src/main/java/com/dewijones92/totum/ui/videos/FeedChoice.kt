package com.dewijones92.totum.ui.videos

import androidx.compose.foundation.lazy.LazyListState
import com.dewijones92.totum.domain.SourceGroup
import com.dewijones92.totum.innertube.feeds.AccountFeed
import com.dewijones92.totum.ui.common.ListFilter

/**
 * What the Videos tab is showing: one of YouTube's account feeds, or one of Dewi's own
 * groups of sources read as a merged feed.
 *
 * Sealed rather than "an AccountFeed, or else a group id if that is null", so loading,
 * refreshing and paging each route in an exhaustive `when` — the two behave differently
 * enough (a group does not paginate, and spans both pillars) that a nullable pair would
 * have meant remembering the rule at every call site.
 */
sealed interface FeedChoice {
    data class Account(val feed: AccountFeed) : FeedChoice

    /**
     * Holds the whole group, not its id: a group's membership can change while it is on
     * screen, and a loader given only an id would have to go and look it up again — with
     * nothing to stop it looking up a different version than the one being displayed.
     */
    data class Group(val group: SourceGroup) : FeedChoice
}

/** One key per feed, so account feeds and groups share the cache without colliding. */
internal fun FeedChoice.cacheKey(): String = when (this) {
    is FeedChoice.Account -> feed.name
    is FeedChoice.Group -> "group:${group.id.value}"
}

/**
 * Where the Videos tab was, for the place trail.
 *
 * The item count is here for a reason: a restored scroll index cannot survive being applied
 * to an empty list, so "scroll=40 videos=0" and "scroll=0 videos=40" are different bugs
 * needing different fixes, and without the count they look identical in a report.
 */
internal fun videosPlace(state: VideosViewModel.UiState, listState: LazyListState, listFilter: ListFilter): String =
    "feed=${state.selected} scroll=${listState.firstVisibleItemIndex}" +
        "+${listState.firstVisibleItemScrollOffset} videos=${state.videos.size} filter=\"${listFilter.query}\""
