package com.dewijones92.totum.ytdlp.process

import com.dewijones92.totum.common.HttpUrl
import com.dewijones92.totum.ytdlp.ExtractionResult
import com.dewijones92.totum.ytdlp.VideoSearchResult
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The desktop engine, without needing a Python on the machine.
 *
 * It is a thin shell around the shared bridge parser, so what is actually worth asserting is the
 * shell: the arguments it builds, and what it does when the process cannot be run at all — which
 * is the failure a user meets on a fresh machine and the one worth handling well.
 */
class ProcessYtDlpEngineTest {

    private val asked = mutableListOf<List<String>>()

    private fun engine(reply: (List<String>) -> String) = ProcessYtDlpEngine(
        CommandRunner { args ->
            asked += args
            reply(args)
        },
    )

    @Test
    fun `extract asks the bridge for that url and parses the answer`() = runTest {
        val result = engine { EXTRACTION }.extract(HttpUrl.of(URL))

        assertEquals(listOf(listOf("extract", URL)), asked)
        assertEquals("Me at the zoo", (result as ExtractionResult.Success).metadata.title)
    }

    @Test
    fun `the bridge's own failures come back as values, not exceptions`() = runTest {
        val result = engine { """{"ok": false, "kind": "extractor", "detail": "Video unavailable"}""" }
            .extract(HttpUrl.of(URL))

        assertEquals("Video unavailable", (result as ExtractionResult.Failure.Extractor).detail)
    }

    @Test
    fun `an unsupported url is told apart from a broken one`() = runTest {
        val result = engine { """{"ok": false, "kind": "unsupported", "detail": "no extractor"}""" }
            .extract(HttpUrl.of(URL))

        assertTrue(result is ExtractionResult.Failure.UnsupportedUrl)
    }

    @Test
    fun `a missing python is a failure to report, not a crash`() = runTest {
        // The commonest first-run problem by a distance. It must reach the user as a message.
        val result = engine { error("python3: command not found") }.extract(HttpUrl.of(URL))

        assertTrue(result is ExtractionResult.Failure.Network)
    }

    @Test
    fun `search passes the limit through and parses the entries`() = runTest {
        val result = engine { SEARCH }.searchVideos("jazz live stream", maxResults = 2)

        assertEquals(listOf(listOf("search", "jazz live stream", "2")), asked)
        assertEquals(listOf("First", "Second"), (result as VideoSearchResult.Success).entries.map { it.title })
    }

    @Test
    fun `a search that cannot be run fails rather than reporting no results`() = runTest {
        // "Nothing found" and "the extractor is not installed" must never look the same.
        val result = engine { error("boom") }.searchVideos("jazz", maxResults = 5)

        assertTrue(result is VideoSearchResult.Failure)
    }

    @Test
    fun `versions asks for versions`() = runTest {
        val versions = engine { """{"yt_dlp": "2026.07.04", "python": "3.12.3"}""" }.versions()

        assertEquals(listOf(listOf("versions")), asked)
        assertEquals("2026.07.04", versions.ytDlp)
    }

    @Test
    fun `solving n is a no-op off Android, and says so rather than pretending`() = runTest {
        // A desktop yt-dlp has a real JavaScript runtime and solves its own.
        assertTrue(engine { "" }.solveN(listOf("challenge"), "player.js").isEmpty())
        assertTrue("and it must not have run anything", asked.isEmpty())
    }

    @Test
    fun `the bridge script is packaged, or nothing here can ever work`() {
        // Guards the Gradle copy that keeps ONE python file for both engines: if the copy task
        // is ever removed the jar still builds, and every command fails at runtime instead.
        val script = ProcessYtDlpEngineTest::class.java.classLoader.getResource(BridgeScript.NAME)

        assertTrue("${BridgeScript.NAME} is not on the classpath", script != null)
    }

    @Test
    fun `the packaged script is the one the app runs`() {
        val packaged = ProcessYtDlpEngineTest::class.java.classLoader
            .getResourceAsStream(BridgeScript.NAME)!!.bufferedReader().readText()

        assertTrue("it must carry the command-line entry point", packaged.contains("""if __name__ == "__main__":"""))
        assertTrue("and the android player-client fallback the phone needs", packaged.contains("PLAYER_CLIENTS"))
    }

    private companion object {
        const val URL = "https://www.youtube.com/watch?v=jNQXAC9IVRw"

        val EXTRACTION = """
            {"ok": true, "info": {"id": "jNQXAC9IVRw", "title": "Me at the zoo", "uploader": "jawed",
             "duration": 19, "formats": [
               {"format_id": "140", "ext": "m4a", "vcodec": "none", "acodec": "mp4a.40.2",
                "url": "https://cdn.test/audio", "filesize": 300000}]}}
        """.trimIndent()

        val SEARCH = """
            {"ok": true, "entries": [
              {"id": "a", "title": "First", "url": "https://www.youtube.com/watch?v=a"},
              {"id": "b", "title": "Second", "url": "https://www.youtube.com/watch?v=b"}]}
        """.trimIndent()
    }
}
