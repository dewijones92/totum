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
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class ABlockedVideoDownloadsWithPictureTest {

    @get:Rule val temp = TemporaryFolder()

    private val server = MockWebServer()
    private val videoBytes = ByteArray(25) { it.toByte() }
    private val audioBytes = ByteArray(7) { (100 + it).toByte() }
    private val ranges = mutableListOf<String>()

    @Before fun setUp() {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val bytes = if (request.url.encodedPath == "/video") videoBytes else audioBytes
                val range = request.headers["Range"]!!.removePrefix("bytes=")
                ranges += "${request.url.encodedPath} $range"
                val (from, to) = range.split('-').map(String::toInt)
                val slice = okio.Buffer().write(bytes.copyOfRange(from, to + 1))
                return MockResponse.Builder().code(206).body(slice)
                    .addHeader("Content-Range", "bytes $from-$to/${bytes.size}").build()
            }
        }
        server.start()
    }

    @After fun tearDown() = server.close()

    private val item = PlayableItem(
        MediaItem(MediaItemId("C6pdB9oikdo"), SourceId("yt"), "Illuminati", publishedAt = null, duration = null),
        PlayHandle.Video(HttpUrl.of("https://www.youtube.com/watch?v=C6pdB9oikdo")),
    )

    private val soundOnly = object : DownloadStrategy {
        override fun download(item: PlayableItem, target: File, audioOnly: Boolean): Flow<DownloadState> =
            flowOf(DownloadState.Downloaded(target.absolutePath))
    }

    private fun quality() = VideoQuality(
        id = "1080",
        label = "1080p",
        height = 1080,
        videoUrl = HttpUrl.of(server.url("/video?clen=25").toString()),
        audioUrl = HttpUrl.of(server.url("/audio?clen=7").toString()),
    )

    private fun strategy(merge: suspend (File, File, File) -> String?) = PlayerBackedDownloadStrategy(
        resolveAudioUrl = { HttpUrl.of(server.url("/audio?clen=7").toString()) },
        http = soundOnly,
        resolveVideo = { _, _ -> quality() },
        fetch = RangedFetch(OkHttpClient(), chunkBytes = 10),
        merge = merge,
    )

    @Test
    fun `a full video is fetched in ranges as picture and sound and merged into one file`() = runTest {
        val target = temp.newFile("C6pdB9oikdo.media")
        val states = strategy { video, audio, out ->
            null.also {
                out.writeBytes(
                    video.readBytes() + audio.readBytes()
                )
            }
        }
            .download(item, target, audioOnly = false).toList()

        val done = states.last() as DownloadState.Downloaded
        assertEquals(false, done.audioOnly)
        assertArrayEquals(videoBytes + audioBytes, target.readBytes())
        assertEquals(listOf("/video 0-9", "/video 10-19", "/video 20-24", "/audio 0-6"), ranges)
        assertTrue(states.any { it is DownloadState.Downloading && it.downloadedBytes == 32L })
    }

    @Test
    fun `a muxed stream whose link states no size is sized by the server and needs no merge`() = runTest {
        val target = temp.newFile("jNQXAC9IVRw.media")
        val muxed = quality().copy(videoUrl = HttpUrl.of(server.url("/video").toString()), audioUrl = null)
        val strategy = PlayerBackedDownloadStrategy(
            resolveAudioUrl = { null },
            http = soundOnly,
            resolveVideo = { _, _ -> muxed },
            fetch = RangedFetch(OkHttpClient(), chunkBytes = 10),
            merge = { _, _, _ -> "must not be asked" },
        )
        val done = strategy.download(item, target, audioOnly = false).toList().last() as DownloadState.Downloaded
        assertEquals(false, done.audioOnly)
        assertArrayEquals(videoBytes, target.readBytes())
    }

    @Test
    fun `a failed merge falls back to the sound alone, recorded as such`() = runTest {
        val target = temp.newFile("C6pdB9oikdo.media")
        val states = strategy { _, _, _ -> "ffmpeg exited 1" }.download(item, target, audioOnly = false).toList()

        assertEquals(true, (states.last() as DownloadState.Downloaded).audioOnly)
        assertTrue(temp.root.listFiles()!!.none { it.name.endsWith(".parts") })
    }
}
