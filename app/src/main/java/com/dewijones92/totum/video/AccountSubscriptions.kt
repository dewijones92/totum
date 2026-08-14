package com.dewijones92.totum.video

import com.dewijones92.totum.common.Diag
import com.dewijones92.totum.domain.MediaSource
import com.dewijones92.totum.domain.SourceId
import com.dewijones92.totum.domain.youTubeChannelId
import com.dewijones92.totum.innertube.actions.ActionResult
import com.dewijones92.totum.innertube.actions.YouTubeActions
import com.dewijones92.totum.innertube.auth.YouTubeAccount
import com.dewijones92.totum.innertube.subscriptions.SubscribedChannel
import com.dewijones92.totum.innertube.subscriptions.SubscriptionsResult
import com.dewijones92.totum.innertube.subscriptions.YouTubeSubscriptions
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * The signed-in account's subscribed channels, read LIVE from YouTube — there
 * is no local mirror. This is the SmartTube model: sign in and your
 * subscriptions ARE your YouTube subscriptions; subscribing or unsubscribing
 * writes straight to YouTube and updates this list optimistically. Signed out,
 * the list is empty. Both the Videos tab's channel row and a channel page's
 * subscribe state read this one flow, so there is a single source of truth.
 */
class AccountSubscriptions(
    private val subscriptions: YouTubeSubscriptions,
    private val actions: YouTubeActions,
    private val account: YouTubeAccount,
    private val scope: CoroutineScope,
    /** Injected so the freshness window is testable without waiting in real time. */
    private val now: () -> Long = System::currentTimeMillis,
) {
    private val _channels = MutableStateFlow<List<MediaSource.VideoChannel>>(emptyList())
    val channels: StateFlow<List<MediaSource.VideoChannel>> = _channels.asStateFlow()

    private val _signedIn = MutableStateFlow(false)

    /** Whether the account is signed in, updated on each [refresh]; drives the feed UI. */
    val signedIn: StateFlow<Boolean> = _signedIn.asStateFlow()

    private var loading: Job? = null
    private var loadedAtMs = 0L

    /**
     * Reloads the live subscription list (on launch, on sign-in/out). Never blocks.
     *
     * Coalesced, because there are two unrelated callers and they collide. `AppContainer` refreshes
     * at launch and `VideosViewModel` refreshes when the Videos tab is entered — in report 0.1.383
     * that was **1,594 channels over two pages, fetched twice, 21 seconds apart**, and the second
     * one told us nothing the first had not. A load already running is joined rather than repeated,
     * and a list this fresh is left alone.
     *
     * [force] is for sign-in and sign-out, where the answer has genuinely changed and the age of
     * the last one is beside the point.
     */
    fun refresh(force: Boolean = false) {
        if (loading?.isActive == true) {
            Diag.log("subs", "refresh already running — joining it instead of fetching again")
            return
        }
        val age = now() - loadedAtMs
        if (!force && loadedAtMs != 0L && age < FRESH_FOR_MS) {
            Diag.log("subs", "list is ${age}ms old, inside the ${FRESH_FOR_MS}ms window — not refetching")
            return
        }
        loading = scope.launch { reload() }
    }

    /**
     * Re-checks whether the account is still signed in, WITHOUT refetching the channel list
     * unless the answer has actually changed.
     *
     * This is what a feed coming back `SignedOut` mid-session actually wants: the question is
     * "is the token still good?", and the 1,594-channel fetch was a side effect of the only
     * method that asked it. Three call sites in `VideosViewModel` were paying for the list to
     * learn one boolean.
     */
    fun recheckSignedIn() {
        scope.launch {
            val signed = account.isSignedIn()
            if (signed == _signedIn.value) {
                Diag.log("subs", "still signedIn=$signed — no need to refetch the channel list")
                return@launch
            }
            Diag.log("subs", "signedIn changed to $signed")
            _signedIn.value = signed
            if (signed) reload() else _channels.value = emptyList()
        }
    }

    private suspend fun reload() {
        val signed = account.isSignedIn()
        _signedIn.value = signed
        if (!signed) {
            _channels.value = emptyList()
            return
        }
        when (val result = subscriptions.list()) {
            is SubscriptionsResult.Success -> {
                _channels.value = result.channels.map { it.toSource() }
                // Stamped only on success, so a transient failure does not buy silence for a
                // minute — the next caller should be allowed to try again straight away.
                loadedAtMs = now()
            }
            SubscriptionsResult.SignedOut -> {
                _signedIn.value = false
                _channels.value = emptyList()
            }
            is SubscriptionsResult.Failure -> Unit // transient — keep what we have
        }
    }

    fun isSubscribed(id: SourceId): Boolean = _channels.value.any { it.id == id }

    /**
     * Subscribes to / unsubscribes from [source] on YouTube, updating the list
     * optimistically and reverting if the write fails. A channel URL without a
     * `/channel/<id>` can't be mirrored to the account, so it only updates the
     * in-memory list. Returns whether the write actually persisted to the account
     * (false on a revert or when there was no channel id to write).
     */
    suspend fun setSubscribed(source: MediaSource.VideoChannel, subscribed: Boolean): Boolean {
        val before = _channels.value
        _channels.update { list ->
            val without = list.filterNot { it.id == source.id }
            if (subscribed) without + source else without
        }
        val channelId = source.channelId() ?: return false
        val ok = actions.setSubscribed(channelId, subscribed) is ActionResult.Success
        if (!ok) _channels.value = before // revert on failure
        return ok
    }

    private fun SubscribedChannel.toSource() = MediaSource.VideoChannel(
        id = SourceId(channelUrl.value),
        title = title,
        channelUrl = channelUrl,
    )

    private fun MediaSource.VideoChannel.channelId(): String? = youTubeChannelId
}

/**
 * How long a fetched list counts as current. Long enough to collapse launch-plus-tab-entry, short
 * enough that subscribing on another device shows up when you next open the tab.
 */
private const val FRESH_FOR_MS = 60_000L
