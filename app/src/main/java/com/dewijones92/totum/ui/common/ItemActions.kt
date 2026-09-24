package com.dewijones92.totum.ui.common

import androidx.compose.runtime.staticCompositionLocalOf
import com.dewijones92.totum.domain.MediaItem
import com.dewijones92.totum.domain.MediaItemId
import com.dewijones92.totum.domain.MediaSource

/**
 * Everything you can do to a media item, available to any row without the screen wiring it.
 *
 * This exists because of a measured failure, not a theory. `MediaItemRow`'s actions were all
 * optional parameters with null defaults, so each of ten call sites hand-picked a subset —
 * and adoption collapsed: `pillar` (required) was passed by 10/10 screens, while
 * `onGoToSource` reached 4/10, `onPeek` 4/10 and `onSetPlayed` 1/10. Nobody *decided* to
 * omit them; an optional parameter makes forgetting silent and indistinguishable from
 * choosing. So the menu differed by screen for no reason anyone intended.
 *
 * Providing the capability once and defaulting the row to it inverts that: a screen now has
 * to work to *remove* an action, and genuinely contextual ones (move within the queue,
 * remove from a playlist) stay explicit because they really do only exist somewhere.
 */
internal interface ItemActions {
    fun playNext(item: MediaItem)
    fun addToQueue(item: MediaItem)
    fun addToPlaylist(items: List<MediaItem>)
    fun peek(item: MediaItem)
    fun download(item: MediaItem, audioOnly: Boolean)
    fun deleteDownload(id: MediaItemId)
    fun setPlayed(id: MediaItemId, played: Boolean)

    /** Navigates to the item's channel/feed. Hosted once by the shell, so it works anywhere. */
    fun goToSource(item: MediaItem)

    fun canGoToSource(item: MediaItem): Boolean

    /** True while the app is in listen-only mode, so a row can label its switch action. */
    val audioMode: Boolean

    fun switchMode(item: MediaItem)
}

/** Null only in previews and tests, where a row legitimately has nothing behind it. */
internal val LocalItemActions = staticCompositionLocalOf<ItemActions?> { null }

internal val LocalOpenSource = staticCompositionLocalOf<((MediaSource) -> Unit)?> { null }

/**
 * Binds one action to a row callback, or null where no actions are provided.
 *
 * Exists so a row can default a dozen callbacks without a dozen `?.let`s in its
 * signature — which is both unreadable and, taken together, more branching than the
 * function's actual logic.
 */
internal inline fun ItemActions?.bind(crossinline action: ItemActions.() -> Unit): (() -> Unit)? {
    val actions = this ?: return null
    return { actions.action() }
}
