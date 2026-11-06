package com.dewijones92.totum.ui.notifications

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.dewijones92.totum.data.content.SeenItemsTracker
import com.dewijones92.totum.di.AppContainer
import com.dewijones92.totum.domain.ChosenWay
import com.dewijones92.totum.domain.MediaItem
import com.dewijones92.totum.domain.PlayHandle
import com.dewijones92.totum.domain.PlayReason
import com.dewijones92.totum.domain.PlayableItem
import com.dewijones92.totum.innertube.feeds.FeedResult
import com.dewijones92.totum.innertube.feeds.YouTubeFeeds
import com.dewijones92.totum.notifications.YouTubeSubscriptionItemsSource.Companion.SUBSCRIPTIONS_SOURCE
import com.dewijones92.totum.queue.PlaybackQueue
import com.dewijones92.totum.ui.common.toMediaItem
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * The new-uploads "bell": how many subscription videos have appeared since the
 * user last looked, and the list of them. Stands in for YouTube's real
 * notification feed (unreachable with this app's token). Shares the unified
 * [SeenItemsTracker] mechanism with the background notifications, but under its
 * own namespace — the bell clears when you *open the list*, notifications when
 * they *fire*, so their seen-state stays independent.
 */
class NotificationsViewModel(
    private val feeds: YouTubeFeeds,
    private val tracker: SeenItemsTracker,
    private val queue: PlaybackQueue,
) : ViewModel() {

    /** One row of the inbox: an upload, and whether it is still unread. */
    data class Upload(val item: MediaItem, val unread: Boolean)

    private var lastFeed: List<MediaItem> = emptyList()

    /**
     * The whole recent feed, unread first — an inbox rather than a queue of alerts.
     *
     * It used to hold ONLY the unseen uploads and empty itself the moment you opened it,
     * so the bell could never answer "what did I already look at". Dewi asked for the
     * history to stay and the unread to float to the top, which is what an inbox is.
     */
    private val inbox = MutableStateFlow<List<Upload>>(emptyList())

    val count: StateFlow<Int> = inbox
        .map { uploads -> uploads.count { it.unread } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS), 0)

    /** The inbox as it stands — read on demand (always current). */
    fun snapshotUploads(): List<Upload> = inbox.value

    init {
        refresh()
    }

    fun refresh() {
        viewModelScope.launch {
            val feed = (feeds.subscriptionsFeed() as? FeedResult.Success)?.page?.items ?: return@launch
            lastFeed = feed.map { it.toMediaItem(SUBSCRIPTIONS_SOURCE.id) }
            val unread = tracker.newItems(SUBSCRIPTIONS_SOURCE.id, lastFeed).map { it.id }.toSet()
            // Feed order is preserved within each group rather than sorted by date: the
            // subscriptions feed arrives newest-first already, and its items carry YouTube's
            // relative text ("2 days ago") rather than a timestamp to sort on.
            inbox.value = lastFeed
                .map { Upload(it, unread = it.id in unread) }
                .sortedByDescending { it.unread }
        }
    }

    /**
     * Called when the user opens the list — everything current becomes "seen".
     *
     * The rows STAY, and keep the unread flag they had when the screen opened: clearing the
     * badge is the point, but re-sorting the list under the reader's finger the instant they
     * arrive would move the very things they came to look at.
     */
    fun markAllSeen() {
        tracker.markSeen(SUBSCRIPTIONS_SOURCE.id, lastFeed)
    }

    fun play(video: MediaItem) {
        val watchUrl = video.mediaUrl ?: return
        viewModelScope.launch {
            queue.playNow(
                PlayableItem(video, PlayHandle.Video(watchUrl)),
                reason = PlayReason.Chosen(ChosenWay.NOTIFICATION),
            )
        }
    }

    companion object {
        private const val STOP_TIMEOUT_MILLIS = 5_000L

        fun factory(container: AppContainer): ViewModelProvider.Factory = viewModelFactory {
            initializer {
                NotificationsViewModel(
                    feeds = container.youTubeFeeds,
                    tracker = container.bellSeenTracker,
                    queue = container.playbackQueue,
                )
            }
        }
    }
}
