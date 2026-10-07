package com.dewijones92.totum.data.download

import com.dewijones92.totum.common.HttpUrl
import com.dewijones92.totum.data.sponsorblock.SegmentLookup
import com.dewijones92.totum.domain.DownloadState
import com.dewijones92.totum.domain.DownloadedMedia
import com.dewijones92.totum.domain.MediaItem
import com.dewijones92.totum.domain.MediaItemId
import com.dewijones92.totum.domain.PlayHandle
import com.dewijones92.totum.domain.PlayableItem
import com.dewijones92.totum.domain.SkipSegment
import com.dewijones92.totum.domain.SourceId
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import kotlin.time.Duration.Companion.seconds

class SkipSegmentsOnDiskTest {

    private val video = PlayableItem(
        MediaItem(MediaItemId("abc123def45"), SourceId("src"), "A video", publishedAt = null, duration = null),
        PlayHandle.Video(HttpUrl.of("https://www.youtube.com/watch?v=abc123def45")),
    )
    private val old = listOf(SkipSegment(10.seconds, 20.seconds))
    private val fresh = listOf(SkipSegment(10.seconds, 20.seconds), SkipSegment(60.seconds, 90.seconds))

    private suspend fun stored(
        cut: Boolean,
        segments: List<SkipSegment>,
    ): Pair<InMemoryDownloadStore, DownloadedMedia> {
        val store = InMemoryDownloadStore()
        store.put(video, DownloadState.Downloaded("/data/v.mkv", sponsorSegmentsCut = cut), audioOnly = false)
        store.rememberSkipSegments(video.item.id, segments)
        return store to store.observeDownloaded().first().single()
    }

    @Test
    fun `segments submitted since the download are stored and returned`() = runTest {
        val (store, copy) = stored(cut = false, segments = old)
        val updated = SkipSegmentsOnDisk({ SegmentLookup.Answered(fresh) }, store).refresh(copy)
        assertEquals(fresh, updated)
        assertEquals(fresh, store.observeDownloaded().first().single().skipSegments)
    }

    @Test
    fun `offline, the stored segments are kept`() = runTest {
        val (store, copy) = stored(cut = false, segments = old)
        assertNull(SkipSegmentsOnDisk({ SegmentLookup.Unavailable("offline") }, store).refresh(copy))
        assertEquals(old, store.observeDownloaded().first().single().skipSegments)
    }

    @Test
    fun `an unchanged answer changes nothing`() = runTest {
        val (store, copy) = stored(cut = false, segments = old)
        assertNull(SkipSegmentsOnDisk({ SegmentLookup.Answered(old) }, store).refresh(copy))
    }

    @Test
    fun `a file cut at download is never looked up`() = runTest {
        val (store, copy) = stored(cut = true, segments = emptyList())
        var asked = false
        val lookup = SkipSegmentsOnDisk({
            asked = true
            SegmentLookup.Answered(fresh)
        }, store)
        assertNull(lookup.refresh(copy))
        assertEquals(false, asked)
    }
}
