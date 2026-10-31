package com.dewijones92.totum.data.download

import com.dewijones92.totum.common.HttpUrl
import com.dewijones92.totum.domain.DownloadState
import com.dewijones92.totum.domain.HomeCopy
import com.dewijones92.totum.domain.MediaItem
import com.dewijones92.totum.domain.MediaItemId
import com.dewijones92.totum.domain.PlayHandle
import com.dewijones92.totum.domain.PlayableItem
import com.dewijones92.totum.domain.SourceId
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

class HomeCopyFirstTest {

    private val watch = HttpUrl.of("https://www.youtube.com/watch?v=abcdefghijk")
    private val video = PlayableItem(
        MediaItem(MediaItemId("abcdefghijk"), SourceId("s"), "A video", publishedAt = null, duration = null),
        PlayHandle.Video(watch),
    )
    private val homeUrl = HttpUrl.of("https://home.test/fetch/files/abcdefghijk")
    private val fetched = mutableListOf<PlayableItem>()
    private val http = DownloadStrategy { item, target, _ ->
        fetched += item
        flowOf(DownloadState.Downloaded(target.path))
    }
    private val nextUsed = mutableListOf<PlayableItem>()
    private val next = DownloadStrategy { item, _, _ ->
        nextUsed += item
        flowOf(DownloadState.Failed("next"))
    }

    private suspend fun run(copy: HomeCopy?, audioOnly: Boolean) =
        HomeCopyFirst({ copy }, http, next).download(video, File("/t"), audioOnly).toList()

    @Test
    fun `a finished copy at home is copied over plain HTTP, whatever the pillar`() = runTest {
        val states = run(HomeCopy(homeUrl, audioOnly = false), audioOnly = false)
        assertEquals(homeUrl, fetched.single().item.mediaUrl)
        assertEquals(DownloadState.Downloaded("/t", audioOnly = false), states.last())
        assertEquals(emptyList<PlayableItem>(), nextUsed)
    }

    @Test
    fun `a sound-only copy at home never stands in for a video, and none at all goes the usual way`() = runTest {
        run(HomeCopy(homeUrl, audioOnly = true), audioOnly = false)
        run(null, audioOnly = true)
        assertEquals(2, nextUsed.size)
        assertEquals(DownloadState.Downloaded("/t", audioOnly = true), run(HomeCopy(homeUrl, true), true).last())
    }
}
