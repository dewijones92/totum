package com.dewijones92.totum.playback

import com.dewijones92.totum.common.SubtitleTrack
import com.dewijones92.totum.domain.Chapter
import com.dewijones92.totum.domain.MediaItem
import com.dewijones92.totum.domain.MediaKind
import com.dewijones92.totum.domain.SkipSegment
import com.dewijones92.totum.domain.SourceId
import java.time.Instant

internal data class ItemContext(
    val id: String,
    val kind: MediaKind,
    val listedDurationMs: Long?,
    val skipSegments: List<SkipSegment>,
    val chapters: List<Chapter>,
    // Held rather than read back from the player's text tracks: the tracks know a
    // language code but not the label or whether it's machine-generated, and those are
    // exactly what a menu needs to show.
    val subtitles: List<SubtitleTrack>,
    /**
     * What the listing said — held here, like the segments and subtitles above and for the same
     * reason: it does not reliably cross the session.
     *
     * It rode in `MediaMetadata.extras` first, which worked locally and then failed **intermittently**
     * on CI: the view count came back null from the queue's play path in one run and not the next,
     * on a commit that touched only test files. Extras are not dependably carried by a
     * `MediaController`'s copy of an item, so a channel that appears to work is really a race — and
     * the video page would have dropped the numbers a moment after showing them, on a device, with
     * nothing to explain it. Every other per-item fact the UI needs is already held exactly this way.
     */
    val viewsText: String?,
    val publishedText: String?,
    val publishedAt: Instant?,
    val sourceId: SourceId?,
) {
    companion object {
        val NONE = ItemContext(
            id = "",
            kind = MediaKind.PODCAST,
            listedDurationMs = null,
            skipSegments = emptyList(),
            chapters = emptyList(),
            subtitles = emptyList(),
            viewsText = null,
            publishedText = null,
            publishedAt = null,
            sourceId = null,
        )

        fun of(item: MediaItem, kind: MediaKind, skipSegments: List<SkipSegment>, subtitles: List<SubtitleTrack>) =
            ItemContext(
                id = item.id.value,
                kind = kind,
                listedDurationMs = item.duration?.inWholeMilliseconds,
                skipSegments = skipSegments,
                chapters = item.chapters,
                subtitles = subtitles,
                viewsText = item.viewsText,
                publishedText = item.publishedText,
                publishedAt = item.publishedAt,
                sourceId = item.sourceId,
            )
    }
}
