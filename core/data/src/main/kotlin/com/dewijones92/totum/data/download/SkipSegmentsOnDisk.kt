package com.dewijones92.totum.data.download

import com.dewijones92.totum.common.Diag
import com.dewijones92.totum.data.sponsorblock.FreshSkipSegments
import com.dewijones92.totum.data.sponsorblock.SegmentLookup
import com.dewijones92.totum.domain.DownloadedMedia
import com.dewijones92.totum.domain.MediaItemId
import com.dewijones92.totum.domain.SkipSegment
import kotlinx.coroutines.flow.first

public class SkipSegmentsOnDisk(
    private val lookup: FreshSkipSegments,
    private val store: DownloadStore,
) {
    public suspend fun toSkip(id: MediaItemId): List<SkipSegment> = copyOf(id)?.segmentsToSkip.orEmpty()

    public suspend fun refresh(id: MediaItemId): List<SkipSegment>? = copyOf(id)?.let { refresh(it) }

    private suspend fun copyOf(id: MediaItemId): DownloadedMedia? =
        store.observeDownloaded().first().firstOrNull { it.item.id == id }

    public suspend fun refresh(copy: DownloadedMedia): List<SkipSegment>? {
        val id = copy.item.id.value
        val videoId = copy.youTubeVideoId
        if (videoId == null) {
            val why = if (copy.sponsorSegmentsCut) "its sponsors were cut at download" else "not a YouTube video"
            Diag.log(TAG, "$id on disk: not looking segments up, $why")
            return null
        }
        return when (val answer = lookup.lookup(videoId)) {
            is SegmentLookup.Unavailable -> {
                Diag.log(TAG, "$id on disk: lookup failed (${answer.reason}); keeping ${copy.skipSegments.size} stored")
                null
            }
            is SegmentLookup.Answered -> if (answer.segments == copy.skipSegments) {
                Diag.log(TAG, "$id on disk: ${answer.segments.size} segment(s), unchanged")
                null
            } else {
                store.rememberSkipSegments(copy.item.id, answer.segments)
                Diag.log(TAG, "$id on disk: segments ${copy.skipSegments.size} -> ${answer.segments.size}, stored")
                answer.segments
            }
        }
    }

    private companion object {
        const val TAG = "sponsorblock"
    }
}
