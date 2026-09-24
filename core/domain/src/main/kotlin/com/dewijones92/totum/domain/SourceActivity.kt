package com.dewijones92.totum.domain

public data class SourceActivity(val source: MediaSource, val latest: MediaItem?)

public fun MediaItem.isFrom(source: MediaSource): Boolean {
    if (sourceId == source.id) return true
    val channel = source as? MediaSource.VideoChannel ?: return false
    val id = channel.youTubeChannelId ?: return false
    return sourceUrl?.youTubeChannelId == id
}

public fun latestUploadFirst(sources: List<MediaSource>, items: List<MediaItem>): List<SourceActivity> =
    sources
        .distinctBy { it.id }
        .map { source ->
            SourceActivity(
                source = source,
                latest = items.filter { it.isFrom(source) && it.publishedAt != null }.maxByOrNull { it.publishedAt!! },
            )
        }
        .sortedWith(
            compareByDescending<SourceActivity> { it.latest?.publishedAt }
                .thenBy(String.CASE_INSENSITIVE_ORDER) { it.source.title },
        )
