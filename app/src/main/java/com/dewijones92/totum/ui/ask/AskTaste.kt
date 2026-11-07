package com.dewijones92.totum.ui.ask

import com.dewijones92.totum.common.Diag
import com.dewijones92.totum.data.ask.squashed
import com.dewijones92.totum.domain.MediaItem
import com.dewijones92.totum.domain.MediaSource
import com.dewijones92.totum.domain.PlayableItem
import com.dewijones92.totum.domain.asPlayable

fun sourceNamed(name: String, sources: List<MediaSource>): MediaSource? {
    val wanted = name.squashed()
    if (wanted.isEmpty()) return null
    return sources.firstOrNull { it.title.squashed() == wanted }
        ?: sources.filter { it.title.squashed().let { title -> title.contains(wanted) || wanted.contains(title) } }
            .minByOrNull { it.title.length }
}

class AskLatest(
    private val sources: suspend () -> List<MediaSource>,
    private val latest: suspend (MediaSource) -> List<MediaItem>?,
) {
    suspend fun find(name: String): List<PlayableItem> {
        val all = sources()
        val source = sourceNamed(name, all)
        if (source == null) {
            Diag.log("ask", "latest from \"$name\": no followed channel or show by that name among ${all.size}")
            return emptyList()
        }
        val items = latest(source).orEmpty()
            .sortedByDescending { it.publishedAt }
            .take(LATEST_SHOWN)
            .map { it.asPlayable() }
        Diag.log("ask", "latest from \"$name\" -> \"${source.title}\": ${items.size} item(s)")
        return items
    }
}

private const val LATEST_SHOWN = 5
