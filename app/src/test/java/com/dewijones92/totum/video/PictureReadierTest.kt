package com.dewijones92.totum.video

import com.dewijones92.totum.common.HttpUrl
import com.dewijones92.totum.domain.MediaItem
import com.dewijones92.totum.domain.MediaItemId
import com.dewijones92.totum.domain.PlayHandle
import com.dewijones92.totum.domain.PlayableItem
import com.dewijones92.totum.domain.SourceId
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

class PictureReadierTest {

    private val watch = HttpUrl.of("https://www.youtube.com/watch?v=abc")
    private val asked = mutableListOf<HttpUrl>()
    private val video = PlayableItem(
        MediaItem(MediaItemId("abc"), SourceId("s"), "A talk", publishedAt = null, duration = null, mediaUrl = watch),
        PlayHandle.Video(watch),
    )

    private fun readier(metered: Boolean) = PictureReadier({ metered }) { url, _ ->
        asked += url
        null
    }

    @Test
    fun `on an unmetered network the video is resolved in the background`() = runTest {
        readier(metered = false).ready(video)
        assertEquals(listOf(watch), asked)
    }

    @Test
    fun `on mobile data nothing is spent`() = runTest {
        readier(metered = true).ready(video)
        assertEquals(emptyList<HttpUrl>(), asked)
    }

    @Test
    fun `a video handed in as its downloaded file from the Library is still readied by its watch URL`() = runTest {
        readier(metered = false).ready(video.copy(handle = PlayHandle.Podcast("/downloads/abc.m4a")))
        assertEquals(listOf(watch), asked)
    }

    @Test
    fun `a podcast has no picture to ready`() = runTest {
        val enclosure = HttpUrl.of("https://example.test/ep.mp3")
        readier(metered = false).ready(
            PlayableItem(
                MediaItem(
                    MediaItemId("ep"),
                    SourceId("f"),
                    "Ep",
                    publishedAt = null,
                    duration = null,
                    mediaUrl = enclosure
                ),
                PlayHandle.Podcast(),
            ),
        )
        assertEquals(emptyList<HttpUrl>(), asked)
    }
}
