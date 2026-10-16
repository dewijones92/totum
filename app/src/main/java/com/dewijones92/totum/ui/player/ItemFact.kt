package com.dewijones92.totum.ui.player

import com.dewijones92.totum.domain.DownloadState
import com.dewijones92.totum.domain.MediaContentKind
import com.dewijones92.totum.domain.MediaItem
import com.dewijones92.totum.domain.MediaKind
import com.dewijones92.totum.playback.PlaybackState
import java.time.Instant

sealed interface ItemFact {
    data class Published(val at: Instant?, val text: String?) : ItemFact
    data class Length(val ms: Long) : ItemFact
    data class Views(val text: String) : ItemFact
    data class By(val name: String) : ItemFact
    data class Publisher(val name: String) : ItemFact
    data class Album(val title: String) : ItemFact
    data class Kind(val kind: MediaContentKind, val isVideo: Boolean) : ItemFact
    data object MembersOnly : ItemFact
    data class Chapters(val count: Int) : ItemFact
    data class Subtitles(val languages: List<String>) : ItemFact
    data class Skips(val count: Int) : ItemFact
    data class Offline(val audioOnly: Boolean, val bytes: Long?) : ItemFact
    data class Source(val host: String) : ItemFact
}

fun itemFacts(item: MediaItem?, state: PlaybackState, offline: DownloadState, bytes: Long?): List<ItemFact> =
    whenAndHowLong(item, state) + whoAndWhat(item, state) + whatPlaybackKnows(item, state, offline, bytes)

private fun whenAndHowLong(item: MediaItem?, state: PlaybackState): List<ItemFact> = buildList {
    val publishedAt = item?.publishedAt ?: state.publishedAt
    val publishedText = (item?.publishedText ?: state.publishedText).takeIf { publishedAt == null }
    if (publishedAt != null || publishedText != null) add(ItemFact.Published(publishedAt, publishedText))
    (item?.duration?.inWholeMilliseconds ?: state.durationMs)?.takeIf { it > 0 }?.let { add(ItemFact.Length(it)) }
    (item?.viewsText ?: state.viewsText)?.takeIf { it.isNotBlank() }?.let { add(ItemFact.Views(it)) }
}

private fun whoAndWhat(item: MediaItem?, state: PlaybackState): List<ItemFact> = buildList {
    val by = (item?.author ?: state.artist)?.takeIf { it.isNotBlank() }
    by?.let { add(ItemFact.By(it)) }
    val publisher = (item?.publisher ?: state.publisher)?.takeIf { it.isNotBlank() && it != item?.author }
    publisher?.let { add(ItemFact.Publisher(it)) }
    item?.album?.title?.let { add(ItemFact.Album(it)) }
    add(ItemFact.Kind(item?.contentKind ?: state.contentKind, isVideo = state.kind == MediaKind.VIDEO))
    if (item?.membersOnly == true) add(ItemFact.MembersOnly)
}

private fun whatPlaybackKnows(
    item: MediaItem?,
    state: PlaybackState,
    offline: DownloadState,
    bytes: Long?,
): List<ItemFact> = buildList {
    state.chapters.size.takeIf { it > 0 }?.let { add(ItemFact.Chapters(it)) }
    state.subtitles.map { it.label }.distinct().takeIf { it.isNotEmpty() }?.let { add(ItemFact.Subtitles(it)) }
    state.skipSegments.size.takeIf { it > 0 }?.let { add(ItemFact.Skips(it)) }
    if (offline is DownloadState.Downloaded) add(ItemFact.Offline(offline.audioOnly, bytes))
    (item?.sourceUrl ?: item?.mediaUrl)?.let { url -> runCatching { java.net.URI(url.value).host }.getOrNull() }
        ?.let { add(ItemFact.Source(it)) }
}
