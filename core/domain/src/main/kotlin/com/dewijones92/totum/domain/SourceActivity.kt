package com.dewijones92.totum.domain

public data class SourceActivity(val source: MediaSource, val latest: MediaItem?)

public fun MediaItem.isFrom(source: MediaSource): Boolean {
    if (sourceId == source.id) return true
    val channel = source as? MediaSource.VideoChannel ?: return false
    val id = channel.youTubeChannelId ?: return false
    return sourceUrl?.youTubeChannelId == id
}

public fun latestUploadFirst(sources: List<MediaSource>, items: List<MediaItem>): List<SourceActivity> {
    val dated = items.filter { it.publishedAt != null }
    val newestBySourceId = dated.newestBy { it.sourceId.value }
    val newestByChannelId = dated.newestBy { it.sourceUrl?.youTubeChannelId }
    return sources
        .distinctBy { it.id }
        .map { source ->
            val channelId = (source as? MediaSource.VideoChannel)?.youTubeChannelId
            val candidates = listOfNotNull(newestBySourceId[source.id.value], channelId?.let(newestByChannelId::get))
            SourceActivity(source, candidates.maxByOrNull { it.publishedAt!! })
        }
        .sortedWith(
            compareByDescending<SourceActivity> { it.latest?.publishedAt }
                .thenBy(String.CASE_INSENSITIVE_ORDER) { it.source.title },
        )
}

private fun List<MediaItem>.newestBy(key: (MediaItem) -> String?): Map<String, MediaItem> {
    val newest = HashMap<String, MediaItem>()
    for (item in this) {
        val k = key(item) ?: continue
        val held = newest[k]
        if (held == null || item.publishedAt!! > held.publishedAt!!) newest[k] = item
    }
    return newest
}
