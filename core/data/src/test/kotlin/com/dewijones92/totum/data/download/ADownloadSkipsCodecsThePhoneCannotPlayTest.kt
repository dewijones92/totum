package com.dewijones92.totum.data.download

import com.dewijones92.totum.common.HttpUrl
import com.dewijones92.totum.domain.MediaItem
import com.dewijones92.totum.domain.MediaItemId
import com.dewijones92.totum.domain.PlayHandle
import com.dewijones92.totum.domain.PlayableItem
import com.dewijones92.totum.domain.SourceId
import com.dewijones92.totum.ytdlp.DownloadEvent
import com.dewijones92.totum.ytdlp.DownloadRequest
import com.dewijones92.totum.ytdlp.YtDlpEngine
import com.dewijones92.totum.ytdlp.fake.FakeYtDlpEngine
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ADownloadSkipsCodecsThePhoneCannotPlayTest {

    @get:Rule
    val folder: TemporaryFolder = TemporaryFolder()

    private class RecordingEngine : YtDlpEngine by FakeYtDlpEngine() {
        val selectors: MutableList<String?> = mutableListOf()

        override fun download(request: DownloadRequest): Flow<DownloadEvent> = flow {
            selectors += request.formatId
            emit(DownloadEvent.Started(request.url))
        }
    }

    private val url = HttpUrl.of("https://www.youtube.com/watch?v=vid")

    private fun item() = PlayableItem(
        item = MediaItem(
            id = MediaItemId("vid"),
            sourceId = SourceId("youtube"),
            title = "a video",
            publishedAt = null,
            duration = null,
            mediaUrl = url,
        ),
        handle = PlayHandle.Video(url),
    )

    private val askedFor = mutableListOf<Int?>()

    private suspend fun selector(
        audioOnly: Boolean = false,
        maxHeight: Int? = null,
        languages: List<String> = emptyList(),
        avoid: (Int?) -> List<String> = { emptyList() },
    ): String? {
        val engine = RecordingEngine()
        EngineDownloadStrategy(
            engine,
            preferredAudioLanguages = { languages },
            videoCodecsToAvoid = { height ->
                askedFor += height
                avoid(height)
            },
        ).download(item(), folder.newFile("out-${System.nanoTime()}.mp4"), audioOnly, maxHeight).toList()
        return engine.selectors.single()
    }

    @Test
    fun `nothing to avoid leaves the best merged selector exactly as it was`() = runTest {
        assertEquals("bv*+ba/b", selector())
        assertEquals("bv*[height<=720]+ba/b[height<=720]/bv*+ba/b", selector(maxHeight = 720))
    }

    @Test
    fun `an avoided codec is excluded from the video stream, with the rest still allowed`() = runTest {
        assertEquals("bv*[vcodec!^=?av01]+ba/b", selector(avoid = { listOf("av01") }))
    }

    @Test
    fun `several avoided prefixes each exclude`() = runTest {
        assertEquals(
            "bv*[vcodec!^=?av01][vcodec!^=?av1]+ba/b",
            selector(avoid = { listOf("av01", "av1") }),
        )
    }

    @Test
    fun `a height cap carries the exclusion into the capped and the fallback selectors`() = runTest {
        assertEquals(
            "bv*[height<=720][vcodec!^=?av01]+ba/b[height<=720]/bv*[vcodec!^=?av01]+ba/b",
            selector(maxHeight = 720, avoid = { listOf("av01") }),
        )
    }

    @Test
    fun `the avoid list is asked for the capped height, so a phone can reject a codec only above a size`() = runTest {
        val onlyAbove1080 = { height: Int? -> if (height == null || height > 1080) listOf("vp09") else emptyList() }

        assertEquals("bv*[vcodec!^=?vp09]+ba/b", selector(avoid = onlyAbove1080))
        assertEquals(
            "bv*[height<=2160][vcodec!^=?vp09]+ba/b[height<=2160]/bv*[vcodec!^=?vp09]+ba/b",
            selector(maxHeight = 2160, avoid = onlyAbove1080),
        )
        assertEquals("bv*[height<=720]+ba/b[height<=720]/bv*+ba/b", selector(maxHeight = 720, avoid = onlyAbove1080))
        assertEquals(listOf<Int?>(null, 2160, 720), askedFor)
    }

    @Test
    fun `the audio language and the codec exclusion apply together`() = runTest {
        assertEquals(
            "bv*[vcodec!^=?av01]+ba[language^=de]/bv*[vcodec!^=?av01]+ba/b",
            selector(languages = listOf("de-DE"), avoid = { listOf("av01") }),
        )
    }

    @Test
    fun `a sound-only download never asks about video codecs`() = runTest {
        assertEquals("ba/b", selector(audioOnly = true, maxHeight = 480, avoid = { listOf("av01") }))
        assertEquals(emptyList<Int?>(), askedFor)
    }
}
