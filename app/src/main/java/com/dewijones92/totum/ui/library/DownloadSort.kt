package com.dewijones92.totum.ui.library

import androidx.annotation.StringRes
import com.dewijones92.totum.R
import com.dewijones92.totum.domain.MediaItem
import com.dewijones92.totum.ui.common.MediaSort

/**
 * How the downloads list is ordered.
 *
 * Dewi, 2026-08-07: *"sort by size, sort by other stuff etc etc etc"*. Size is the one that matters
 * most here and the one [MediaSort] cannot express: it orders by what a [MediaItem] knows, and an
 * item does not know how many bytes its file takes. Only a download does.
 *
 * So this **wraps** [MediaSort] rather than restating it. Every order that is about the item is
 * still the shared one — same comparators, same stable-sort behaviour for items with no date or
 * duration — and only the two that are about the FILE are new. Adding an option to `MediaSort`
 * makes it appear here automatically; nothing has to be kept in step by hand.
 */
sealed interface DownloadSort {

    /** The label to show in the sort menu. */
    @get:StringRes
    val labelRes: Int

    /** An order that is about the item, borrowed whole from the shared list sort. */
    data class ByItem(val order: MediaSort) : DownloadSort {
        override val labelRes: Int get() = order.labelRes
    }

    /** Biggest first — what you open the list for when the phone is full. */
    data object Largest : DownloadSort {
        override val labelRes: Int get() = R.string.sort_largest
    }

    data object Smallest : DownloadSort {
        override val labelRes: Int get() = R.string.sort_smallest
    }

    fun <T> sortedBy(
        items: List<T>,
        item: (T) -> MediaItem,
        size: (T) -> Long,
        added: ((T) -> Long?)? = null,
    ): List<T> = when (this) {
        is ByItem -> order.sortedBy(items, added = added, item = item)
        Largest -> items.sortedByDescending(size)
        Smallest -> items.sortedBy(size)
    }

    companion object {
        /**
         * Every option, in menu order: the item ones first because they are the familiar ones, then
         * the file ones.
         *
         * Derived from `MediaSort.entries` rather than listed, so this cannot fall behind it.
         *
         * A `get()` rather than a stored `val`, and that is not style. The companion of a sealed
         * interface initialises BEFORE the nested objects it declares, so a stored list built here
         * captured `Largest` while it was still null — the menu really did contain
         * `[…, null, Smallest]`, which would have been a null first file-sort entry on a device.
         * Computed on read, everything it names is already constructed. Caught by
         * `DownloadSortTest`, which is the only reason it is not shipping.
         */
        val ALL: List<DownloadSort> get() = MediaSort.entries.map(::ByItem) + listOf(Largest, Smallest)

        val DEFAULT: DownloadSort get() = ByItem(MediaSort.DEFAULT)
    }
}
