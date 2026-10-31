package com.dewijones92.totum.video

import com.dewijones92.totum.common.HttpUrl
import com.dewijones92.totum.data.download.DownloadStrategy
import com.dewijones92.totum.domain.DownloadState
import com.dewijones92.totum.domain.MediaItem
import com.dewijones92.totum.domain.MediaItemId
import com.dewijones92.totum.domain.PlayHandle
import com.dewijones92.totum.domain.PlayableItem
import com.dewijones92.totum.domain.SourceId
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class AFallbackAudioDownloadIsRecordedAsAudioTest {

    private val video = PlayableItem(
        item = MediaItem(
            id = MediaItemId("nX0fgBL3sIM"),
            sourceId = SourceId("yt"),
            title = "AI NEWS",
            publishedAt = null,
            duration = null,
            mediaUrl = HttpUrl.of("https://www.youtube.com/watch?v=nX0fgBL3sIM"),
        ),
        handle = PlayHandle.Video(HttpUrl.of("https://www.youtube.com/watch?v=nX0fgBL3sIM")),
    )

    private val plainHttp = object : DownloadStrategy {
        override fun download(item: PlayableItem, target: File, audioOnly: Boolean): Flow<DownloadState> =
            flowOf(DownloadState.Downloading(10, 10), DownloadState.Downloaded(target.absolutePath))
    }

    @Test
    fun `audio fetched over plain https by the signed-in path is recorded as audio only`() = runTest {
        val strategy = PlayerBackedDownloadStrategy(
            resolveAudioUrl = { HttpUrl.of("https://rr1---sn.googlevideo.com/videoplayback?itag=140") },
            http = plainHttp,
        )

        val states = strategy.download(video, File.createTempFile("download", ".media"), audioOnly = false).toList()

        val done = states.last()
        assertTrue("expected a finished download, got $states", done is DownloadState.Downloaded)
        assertEquals(
            "an audio stream must never be recorded as a full copy",
            true,
            (done as DownloadState.Downloaded).audioOnly
        )
    }
}
