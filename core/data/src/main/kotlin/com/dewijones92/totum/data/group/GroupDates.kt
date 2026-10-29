package com.dewijones92.totum.data.group

import com.dewijones92.totum.domain.MediaItem
import com.dewijones92.totum.domain.MediaItemId
import com.dewijones92.totum.domain.PublishedAge
import java.time.Instant

public fun datedChannelItems(exact: List<MediaItem>, page: List<MediaItem>, observedAt: Instant): List<MediaItem> {
    val exactAt: Map<MediaItemId, Instant> =
        exact.mapNotNull { item -> item.publishedAt?.let { item.id to it } }.toMap()
    val nextExactBelow = arrayOfNulls<Instant>(page.size + 1)
    for (i in page.indices.reversed()) nextExactBelow[i] = exactAt[page[i].id] ?: nextExactBelow[i + 1]
    var above: Instant? = null
    val dated = page.mapIndexed { i, item ->
        val at = exactAt[item.id] ?: estimate(item, observedAt, above, nextExactBelow[i + 1])
        above = at ?: above
        item.copy(publishedAt = at)
    }
    val onPage = page.mapTo(mutableSetOf()) { it.id }
    return dated + exact.filter { it.id !in onPage }
}

private fun estimate(item: MediaItem, observedAt: Instant, above: Instant?, below: Instant?): Instant? {
    val aged = item.publishedText?.let { PublishedAge.parse(it, observedAt) } ?: above ?: return null
    val capped = above?.takeIf { aged.isAfter(it) } ?: aged
    return below?.takeIf { capped.isBefore(it) && fitsUnder(it, above) } ?: capped
}

private fun fitsUnder(below: Instant, above: Instant?): Boolean = above == null || !below.isAfter(above)
