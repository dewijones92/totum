package com.dewijones92.totum.ytdlp.process

import com.dewijones92.totum.common.HttpUrl
import com.dewijones92.totum.ytdlp.ExtractionResult
import com.dewijones92.totum.ytdlp.VideoSearchResult
import com.dewijones92.totum.ytdlp.bestAudioFormat
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * The real thing: system Python, real yt-dlp, live YouTube.
 *
 * **Skipped unless `RUN_LIVE_EXTRACTION=1`**, because YouTube refuses datacentre addresses and a
 * CI runner is one — the same reason the app's live tests go through the home tunnel
 * (`tools/ci/live-test-via-home.sh`). Every rule this exercises is covered deterministically by
 * [ProcessYtDlpEngineTest]; what only this can prove is that the plumbing between Kotlin, the
 * script and yt-dlp still fits together after any of the three moves.
 *
 * The video is the oldest on YouTube and 19 seconds long: it will outlive this repo, and nobody
 * is paying for the bandwidth.
 */
class LiveExtractionTest {

    private val engine = ProcessYtDlpEngine()

    @Test
    fun `it extracts a real video through the real bridge`() = runTest {
        assumeLive()

        val result = engine.extract(HttpUrl.of(URL))

        val metadata = (result as? ExtractionResult.Success)?.metadata
            ?: error("extraction failed: $result")
        assertEquals("Me at the zoo", metadata.title)
        assertTrue("it must come back with formats", metadata.formats.isNotEmpty())
    }

    @Test
    fun `and picks an audio stream that has a url`() = runTest {
        assumeLive()

        val metadata = (engine.extract(HttpUrl.of(URL)) as ExtractionResult.Success).metadata
        val audio = metadata.bestAudioFormat(listOf("en"))

        assertTrue("nothing playable came back", audio?.url?.startsWith("http") == true)
    }

    @Test
    fun `search finds something for an ordinary phrase`() = runTest {
        assumeLive()

        val result = engine.searchVideos("jazz live stream", maxResults = 3)

        val entries = (result as? VideoSearchResult.Success)?.entries ?: error("search failed: $result")
        assertTrue("a common phrase returning nothing means the search path is broken", entries.isNotEmpty())
    }

    @Test
    fun `the engine reports the versions it is actually running`() = runTest {
        assumeLive()

        val versions = engine.versions()

        assertTrue("yt-dlp did not report a version", versions.ytDlp.first().isDigit())
        assertTrue("python did not report a version", versions.python.startsWith("3."))
    }

    private fun assumeLive() =
        assumeTrue("set RUN_LIVE_EXTRACTION=1 to run this", System.getenv("RUN_LIVE_EXTRACTION") == "1")

    private companion object {
        const val URL = "https://www.youtube.com/watch?v=jNQXAC9IVRw"
    }
}
