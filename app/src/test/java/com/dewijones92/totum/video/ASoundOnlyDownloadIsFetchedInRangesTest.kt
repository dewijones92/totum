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

class ASoundOnlyDownloadIsFetchedInRangesTest {

    @get:Rule val temp = TemporaryFolder()

    private val server = MockWebServer()
    private val audioBytes = ByteArray(27) { (50 + it).toByte() }
    private val ranges = mutableListOf<String>()
    private var plainGets = 0

    @Before fun setUp() {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val range = request.headers["Range"]!!.removePrefix("bytes=")
                ranges += range
                val (from, to) = range.split('-').map(String::toInt)
                val slice = okio.Buffer().write(audioBytes.copyOfRange(from, to + 1))
                return MockResponse.Builder().code(206).body(slice)
                    .addHeader("Content-Range", "bytes $from-$to/${audioBytes.size}").build()
            }
        }
        server.start()
    }

    @After fun tearDown() = server.close()

    private val item = PlayableItem(
        MediaItem(
            MediaItemId("hiI0Vk4NgeY"),
            SourceId("yt"),
            "Anthropic Rebuilt Git",
            publishedAt = null,
            duration = null
        ),
        PlayHandle.Video(HttpUrl.of("https://www.youtube.com/watch?v=hiI0Vk4NgeY")),
    )

    private val oneOpenGet = object : DownloadStrategy {
        override fun download(item: PlayableItem, target: File, audioOnly: Boolean): Flow<DownloadState> {
            plainGets++
            return flowOf(DownloadState.Failed("fetched in one open-ended GET, which YouTube paces at ~33 KB/s"))
        }
    }

    @Test
    fun `the sound of a refused video is fetched in bounded ranges, not one paced GET`() = runTest {
        val strategy = PlayerBackedDownloadStrategy(
            resolveAudioUrl = { HttpUrl.of(server.url("/videoplayback?itag=251&clen=27").toString()) },
            http = oneOpenGet,
            fetch = RangedFetch(OkHttpClient(), chunkBytes = 10),
        )
        val target = temp.newFile("sound.media")

        val states = strategy.download(item, target, audioOnly = true).toList()

        assertEquals("took the single open GET", 0, plainGets)
        val done = states.last()
        assertTrue("expected a finished download, got $states", done is DownloadState.Downloaded)
        assertEquals(true, (done as DownloadState.Downloaded).audioOnly)
        assertEquals(listOf("0-9", "10-19", "20-26"), ranges)
        assertArrayEquals(audioBytes, target.readBytes())
    }
}
