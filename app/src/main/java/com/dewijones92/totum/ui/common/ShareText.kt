package com.dewijones92.totum.ui.common

import android.content.res.Resources
import com.dewijones92.totum.R
import com.dewijones92.totum.domain.MediaItem

internal fun Resources.shareListMessage(title: String, items: List<MediaItem>): String {
    val shown = items.take(SHARE_LIST_CAP)
    val lines = shown.mapIndexed { index, item -> "${index + 1}. ${item.title}" }
    val more = items.size - shown.size
    val tail = if (more > 0) listOf(getQuantityString(R.plurals.share_list_more, more, more)) else emptyList()
    return (listOf(title) + lines + tail).joinToString("\n")
}

internal const val SHARE_LIST_CAP = 100
