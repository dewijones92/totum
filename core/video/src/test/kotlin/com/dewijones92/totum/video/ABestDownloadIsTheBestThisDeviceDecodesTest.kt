package com.dewijones92.totum.video

import com.dewijones92.totum.common.HttpUrl
import com.dewijones92.totum.data.download.EngineDownloadStrategy
import com.dewijones92.totum.data.home.PhoneDecoder
import com.dewijones92.totum.domain.MediaItem
import com.dewijones92.totum.domain.MediaItemId
import com.dewijones92.totum.domain.PlayHandle
import com.dewijones92.totum.domain.PlayableItem
import com.dewijones92.totum.domain.SourceId
import com.dewijones92.totum.domain.VideoCodecFamily
import com.dewijones92.totum.ytdlp.DownloadEvent
import com.dewijones92.totum.ytdlp.DownloadRequest
import com.dewijones92.totum.ytdlp.YtDlpEngine
import com.dewijones92.totum.ytdlp.fake.FakeYtDlpEngine
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ABestDownloadIsTheBestThisDeviceDecodesTest {

    @get:Rule
    val folder: TemporaryFolder = TemporaryFolder()

    private class RecordingEngine : YtDlpEngine by FakeYtDlpEngine() {
        val selectors: MutableList<String?> = mutableListOf()

        override fun download(request: DownloadRequest): Flow<DownloadEvent> = flow {
            selectors += request.formatId
            emit(DownloadEvent.Started(request.url))
        }
    }

    private val fireTablet = PhoneCodecs(
        listOf(
            PhoneDecoder("avc", 1080, hardware = true),
            PhoneDecoder("hevc", 1080, hardware = true),
            PhoneDecoder("vp9", 1080, hardware = true),
            PhoneDecoder("vp8", 1080, hardware = true),
            PhoneDecoder("av1", 2160, hardware = false),
        ),
    )

    private val pixel = PhoneCodecs(
        listOf("avc", "hevc", "vp9", "vp8", "av1").map { PhoneDecoder(it, 4320, hardware = true) },
    )

    private val url = HttpUrl.of("https://www.youtube.com/watch?v=vid")
    private val item = PlayableItem(
        MediaItem(
            MediaItemId("vid"),
            SourceId("youtube"),
            "a video",
            publishedAt = null,
            duration = null,
            mediaUrl = url,
        ),
        PlayHandle.Video(url),
    )

    private suspend fun selectorFor(support: VideoCodecSupport, maxHeight: Int? = null): String? {
        val engine = RecordingEngine()
        EngineDownloadStrategy(
            engine,
            videoCodecsToAvoid = support::codecsToAvoid,
            bestHeight = support::bestHeight,
        ).download(item, folder.newFile("out-${System.nanoTime()}.mp4"), false, maxHeight).toList()
        return engine.selectors.single()
    }

    private val noAv1 = "[vcodec!^=?av01][vcodec!^=?av1]"

    @Test
    fun `best on a tablet that decodes VP9 only to 1080p is capped at 1080p, not 4K VP9 it cannot play`() = runTest {
        assertEquals(
            "bv*[height<=1080]$noAv1+ba/b[height<=1080]/bv*$noAv1+ba/b",
            selectorFor(fireTablet),
        )
    }

    @Test
    fun `best on a device that decodes everything stays uncapped`() = runTest {
        assertEquals("bv*+ba/b", selectorFor(pixel))
    }

    @Test
    fun `an explicit height still wins over the device's best`() = runTest {
        assertEquals(
            "bv*[height<=720]$noAv1+ba/b[height<=720]/bv*$noAv1+ba/b",
            selectorFor(fireTablet, maxHeight = 720),
        )
    }

    @Test
    fun `the best height and each codec's reach are read from the same decoder answers streaming uses`() {
        assertEquals(1080, fireTablet.bestHeight())
        assertNull(pixel.bestHeight())
        assertEquals(1080, fireTablet.reach(VideoCodecFamily.VP9))
        assertEquals(2160, fireTablet.reach(VideoCodecFamily.AV1))
        assertNull(PhoneCodecs(emptyList()).bestHeight())
    }
}
