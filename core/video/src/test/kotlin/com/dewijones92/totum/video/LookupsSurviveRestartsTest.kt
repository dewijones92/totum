package com.dewijones92.totum.video

import com.dewijones92.totum.common.HttpUrl
import com.dewijones92.totum.common.SubtitleFormat
import com.dewijones92.totum.common.SubtitleTrack
import com.dewijones92.totum.data.sponsorblock.SkipSegmentSource
import com.dewijones92.totum.domain.SkipSegment
import com.dewijones92.totum.domain.SourceId
import com.dewijones92.totum.ytdlp.ChapterInfo
import com.dewijones92.totum.ytdlp.ExtractionResult
import com.dewijones92.totum.ytdlp.MediaFormat
import com.dewijones92.totum.ytdlp.MediaMetadata
import com.dewijones92.totum.ytdlp.YtDlpEngine
import com.dewijones92.totum.ytdlp.fake.FakeYtDlpEngine
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.time.Instant
import kotlin.time.Duration.Companion.seconds

class LookupsSurviveRestartsTest {

    @get:Rule
    val folder = TemporaryFolder()

    private val url = HttpUrl.of("https://www.youtube.com/watch?v=abcdefghijk")
    private val start = 1_800_000_000_000L
    private var clock = start
    private var extractions = 0
    private var segmentLookups = 0
    private val segment = SkipSegment(5.seconds, 9.seconds)

    private fun metadata() = MediaMetadata(
        id = "abcdefghijk",
        title = "A video",
        uploader = "A channel",
        durationSeconds = 600,
        thumbnailUrl = "https://i.ytimg.com/x.jpg",
        formats = listOf(
            MediaFormat(
                "18", "mp4", 640, 360, true, true, 1234,
                "https://rr1.googlevideo.com/videoplayback?expire=${(start + 6 * 3_600_000) / 1000}&itag=18",
                "avc1", "mp4a", "en", 10,
            ),
        ),
        description = "words",
        uploaderUrl = "https://www.youtube.com/channel/UCabc",
        chapters = listOf(ChapterInfo(0.0, "Intro")),
        publishedAt = Instant.ofEpochSecond(1_700_000_000),
        subtitles = listOf(
            SubtitleTrack(
                "en",
                "English",
                HttpUrl.of("https://www.youtube.com/api/timedtext?v=x"),
                false,
                SubtitleFormat.VTT
            ),
        ),
    )

    private val engine = object : YtDlpEngine by FakeYtDlpEngine() {
        override suspend fun extract(url: HttpUrl): ExtractionResult {
            extractions++
            return ExtractionResult.Success(metadata())
        }
    }

    private val segments = SkipSegmentSource {
        segmentLookups++
        listOf(segment)
    }

    private fun freshProcess() = VideoResolver(
        engine,
        segments,
        now = { clock },
        lookupStore = FileLookupStore(folder.root),
    )

    @Test
    fun `a lookup made before a restart is used after it, with no extraction and no SponsorBlock call`() = runTest {
        freshProcess().resolve(url, SourceId("s"))
        clock += 60 * 60_000L

        val afterRestart = freshProcess().resolve(url, SourceId("s"))

        assertEquals(1, extractions)
        assertEquals(1, segmentLookups)
        assertEquals(listOf(segment), afterRestart?.skipSegments)
        assertEquals("A video", afterRestart?.item?.title)
    }

    @Test
    fun `a stored lookup near its URLs' expiry is not trusted`() = runTest {
        freshProcess().resolve(url, SourceId("s"))
        clock += 5 * 60 * 60_000L + 40 * 60_000L

        freshProcess().resolve(url, SourceId("s"))

        assertEquals(2, extractions)
    }

    @Test
    fun `a forgotten lookup is extracted afresh rather than read back from the store`() = runTest {
        val resolver = freshProcess()
        resolver.resolve(url, SourceId("s"))

        resolver.forget(url)
        resolver.resolve(url, SourceId("s"))

        assertEquals(2, extractions)
    }

    @Test
    fun `a lookup forgotten before a restart is not used after it`() = runTest {
        freshProcess().apply {
            resolve(url, SourceId("s"))
            forget(url)
        }

        freshProcess().resolve(url, SourceId("s"))

        assertEquals(2, extractions)
    }

    @Test
    fun `every field of the extraction survives the round trip`() {
        val store = FileLookupStore(folder.root)
        val stored = StoredLookup(metadata(), SourceId("s"), listOf(segment), savedAtMs = start)

        store.save(url, stored)

        assertEquals(stored, store.load(url))
    }

    @Test
    fun `a damaged file is ignored and removed`() {
        val store = FileLookupStore(folder.root)
        store.save(url, StoredLookup(metadata(), SourceId("s"), emptyList(), savedAtMs = start))
        folder.root.listFiles()!!.single().writeText("{ not json")

        assertNull(store.load(url))
        assertEquals(0, folder.root.listFiles()!!.size)
    }

    @Test
    fun `only the most recent lookups are kept`() {
        val store = FileLookupStore(folder.root, keep = 3)
        (1..5).forEach { n ->
            store.save(
                HttpUrl.of("https://www.youtube.com/watch?v=video$n"),
                StoredLookup(metadata(), SourceId("s"), emptyList(), start)
            )
            folder.root.listFiles()!!.forEach { it.setLastModified(it.lastModified() - 1_000) }
        }

        assertEquals(3, folder.root.listFiles()!!.size)
        assertNull(store.load(HttpUrl.of("https://www.youtube.com/watch?v=video1")))
    }
}
