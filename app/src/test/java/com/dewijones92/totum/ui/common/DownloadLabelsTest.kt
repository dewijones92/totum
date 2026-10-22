package com.dewijones92.totum.ui.common

import com.dewijones92.totum.R
import com.dewijones92.totum.common.HttpUrl
import com.dewijones92.totum.domain.MediaContentKind
import com.dewijones92.totum.domain.MediaItem
import com.dewijones92.totum.domain.MediaItemId
import com.dewijones92.totum.domain.SourceId
import org.junit.Assert.assertEquals
import org.junit.Test

class DownloadLabelsTest {

    private fun item(url: String, kind: MediaContentKind = MediaContentKind.STANDARD) = MediaItem(
        id = MediaItemId(url),
        sourceId = SourceId("s"),
        title = "t",
        publishedAt = null,
        duration = null,
        mediaUrl = HttpUrl.of(url),
        contentKind = kind,
    )

    @Test
    fun `every download says what it will fetch`() {
        val video = item("https://www.youtube.com/watch?v=abc")
        assertEquals(R.string.download_the_sound, downloadLabel(video, audioOnly = true))
        assertEquals(R.string.download_the_video, downloadLabel(video, audioOnly = false))
        assertEquals(R.string.download_the_file, downloadLabel(item("https://feed.test/ep1.mp3"), audioOnly = true))
        assertEquals(
            R.string.download_the_song,
            downloadLabel(item("https://www.youtube.com/watch?v=s", MediaContentKind.MUSIC), audioOnly = true),
        )
    }
}
