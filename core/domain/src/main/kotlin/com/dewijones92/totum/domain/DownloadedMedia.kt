package com.dewijones92.totum.domain

import com.dewijones92.totum.common.youTubeVideoId

/**
 * A finished download: what was fetched, where the bytes are, and which variant.
 *
 * The download record carries the item itself. Before it did, the downloads table held
 * ids alone, so listing what was offline meant joining against one pillar's own
 * catalogue — and the Library could only ever show podcast episodes. A downloaded video
 * was on disk and invisible.
 */
public data class DownloadedMedia(
    public val playable: PlayableItem,
    public val localPath: String,
    public val audioOnly: Boolean,
    public val sponsorSegmentsCut: Boolean = false,
    public val skipSegments: List<SkipSegment> = emptyList(),
) {
    public val youTubeVideoId: String?
        get() = playable.fetchUrl?.youTubeVideoId()?.takeIf { pillar == MediaKind.VIDEO && !sponsorSegmentsCut }

    public val segmentsToSkip: List<SkipSegment> get() = if (youTubeVideoId == null) emptyList() else skipSegments

    public val item: MediaItem get() = playable.item

    /** Which pillar it came from — for labelling. The file may still be audio-only. */
    public val pillar: MediaKind get() = playable.handle.pillar

    /** This download as something playable — the swap lives in [playedFromDisk]. */
    public val offline: PlayableItem get() = playable.playedFromDisk(LocalCopy(localPath, audioOnly))
}
