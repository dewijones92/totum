package com.dewijones92.totum.cli

import com.dewijones92.totum.common.HttpUrl
import com.dewijones92.totum.ytdlp.ExtractionResult
import com.dewijones92.totum.ytdlp.MediaFormat
import com.dewijones92.totum.ytdlp.MediaMetadata
import com.dewijones92.totum.ytdlp.VideoSearchEntry
import com.dewijones92.totum.ytdlp.VideoSearchResult
import com.dewijones92.totum.ytdlp.YtDlpEngine
import com.dewijones92.totum.ytdlp.fake.FakeYtDlpEngine
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What the CLI actually does, with the engine, the terminal and the player all injected.
 *
 * The pyramid's base: every decision — which stream, which player arguments, what to print, what
 * to exit with — is checked here in milliseconds, so the one live test that needs the network is
 * checking the network rather than the logic.
 */
class CliBehaviourTest {

    private val printed = mutableListOf<String>()
    private val errors = mutableListOf<String>()
    private val launched = mutableListOf<List<String>>()

    private fun cli(engine: YtDlpEngine = Engine(), wanted: List<String> = listOf("en")) = Cli(
        engine = engine,
        out = { printed += it },
        err = { errors += it },
        launch = {
            launched += it
            Cli.OK
        },
        playerOnPath = { it == "mpv" },
        wanted = wanted,
    )

    @Test
    fun `playing a url hands the audio stream to the player`() = runTest {
        val exit = cli().run(Command.Play(Target.Url(HttpUrl.of(URL)), watch = false))

        assertEquals(Cli.OK, exit)
        val command = launched.single()
        assertEquals("mpv", command.first())
        assertTrue("audio unless watching", "--no-video" in command)
        assertTrue("it must play the AUDIO stream", command.last().contains("audio-en"))
    }

    @Test
    fun `watching keeps the picture and takes the video stream`() = runTest {
        cli().run(Command.Play(Target.Url(HttpUrl.of(URL)), watch = true))

        val command = launched.single()
        assertTrue("--no-video" !in command)
        assertTrue(command.last().contains("muxed"))
    }

    @Test
    fun `a stream that only exists with a picture is still played as audio`() = runTest {
        // A 24/7 live stream publishes no audio-only format at all, so "totum jazz" would
        // otherwise decode video nobody is looking at.
        cli(engine = Engine(audioOnly = false)).run(Command.Play(Target.Query("jazz"), watch = false))

        assertTrue("--no-video" in launched.single())
    }

    @Test
    fun `the language rules are the app's, not a second set`() = runTest {
        // The dub is the LARGER file, which is what used to win. Same picker as the phone.
        cli(wanted = listOf("en")).run(Command.Play(Target.Url(HttpUrl.of(URL)), watch = false))

        assertTrue(launched.single().last().contains("audio-en"))
    }

    @Test
    fun `asking for the dub gets the dub`() = runTest {
        cli(wanted = listOf("de")).run(Command.Play(Target.Url(HttpUrl.of(URL)), watch = false))

        assertTrue(launched.single().last().contains("audio-de"))
    }

    @Test
    fun `a phrase is searched and the first hit played`() = runTest {
        val exit = cli().run(Command.Play(Target.Query("jazz live stream"), watch = false))

        assertEquals(Cli.OK, exit)
        assertTrue(printed.any { it.contains("A Video") })
    }

    @Test
    fun `a phrase that finds nothing fails loudly and plays nothing`() = runTest {
        val exit = cli(engine = Engine(hits = emptyList())).run(Command.Play(Target.Query("nothing"), watch = false))

        assertEquals(Cli.FAILURE, exit)
        assertTrue(launched.isEmpty())
        assertTrue(errors.any { it.contains("nothing found") })
    }

    @Test
    fun `an unresolvable url says why and plays nothing`() = runTest {
        val failing = Engine(extraction = ExtractionResult.Failure.Extractor("Video unavailable"))

        val exit = cli(engine = failing).run(Command.Play(Target.Url(HttpUrl.of(URL)), watch = false))

        assertEquals(Cli.FAILURE, exit)
        assertTrue(launched.isEmpty())
        assertTrue(errors.any { it.contains("Video unavailable") })
    }

    @Test
    fun `resolve prints the stream and never launches anything`() = runTest {
        val exit = cli().run(Command.Resolve(Target.Url(HttpUrl.of(URL)), json = false))

        assertEquals(Cli.OK, exit)
        assertTrue(launched.isEmpty())
        assertTrue(printed.any { it.startsWith("https://") })
    }

    @Test
    fun `resolve --json is one line a script can read`() = runTest {
        cli().run(Command.Resolve(Target.Url(HttpUrl.of(URL)), json = true))

        val line = printed.single()
        assertTrue(line.startsWith("{") && line.endsWith("}"))
        listOf("title", "uploader", "format", "language", "url").forEach {
            assertTrue("$it is missing from $line", line.contains("\"$it\":"))
        }
    }

    @Test
    fun `a title with quotes in it does not break the json`() = runTest {
        val quoted = Engine(title = """He said "hello" \ goodbye""")

        cli(engine = quoted).run(Command.Resolve(Target.Url(HttpUrl.of(URL)), json = true))

        assertTrue(printed.single().contains("""\"hello\""""))
    }

    @Test
    fun `search lists what it found and succeeds`() = runTest {
        val exit = cli().run(Command.Search("jazz", limit = 5))

        assertEquals(Cli.OK, exit)
        assertTrue(printed.any { it.contains("A Video") })
    }

    @Test
    fun `search that finds nothing is a failure, not a silent success`() = runTest {
        val exit = cli(engine = Engine(hits = emptyList())).run(Command.Search("jazz", limit = 5))

        assertEquals(Cli.FAILURE, exit)
    }

    @Test
    fun `help prints the usage and succeeds when it was asked for`() = runTest {
        assertEquals(Cli.OK, cli().run(Command.Help()))
        assertTrue(printed.any { it.contains("Usage:") })
    }

    @Test
    fun `help prints the usage and FAILS when it is a correction`() = runTest {
        // The exit code is what a script notices, and being told off should not look like success.
        assertEquals(Cli.FAILURE, cli().run(Command.Help("play needs a URL")))
        assertTrue(errors.any { it.contains("play needs a URL") })
    }

    private class Engine(
        private val audioOnly: Boolean = true,
        private val hits: List<VideoSearchEntry> = listOf(entry()),
        private val extraction: ExtractionResult? = null,
        private val title: String = "A Video",
    ) : YtDlpEngine by FakeYtDlpEngine() {

        override suspend fun extract(url: HttpUrl): ExtractionResult = extraction ?: ExtractionResult.Success(
            MediaMetadata(
                id = "abc",
                title = title,
                uploader = "A Channel",
                durationSeconds = 60,
                thumbnailUrl = null,
                formats = buildList {
                    add(muxed("muxed", "acont=original:lang=en"))
                    if (audioOnly) {
                        // The dub is the BIGGER file, which is exactly what used to win.
                        add(audio("audio-de", size = 900, xtags = "acont=dubbed-auto:lang=de-DE"))
                        add(audio("audio-en", size = 100, xtags = "acont=original:lang=en-US"))
                    }
                },
            ),
        )

        override suspend fun searchVideos(query: String, maxResults: Int): VideoSearchResult =
            VideoSearchResult.Success(hits.take(maxResults))
    }

    private companion object {
        const val URL = "https://www.youtube.com/watch?v=jNQXAC9IVRw"

        fun entry() = VideoSearchEntry(
            id = "abc",
            title = "A Video",
            uploader = "A Channel",
            durationSeconds = 60,
            watchUrl = HttpUrl.of(URL),
            thumbnailUrl = null,
        )

        fun muxed(id: String, xtags: String) =
            format(id, hasVideo = true, size = 100, xtags = xtags)

        fun audio(id: String, size: Long, xtags: String) =
            format(id, hasVideo = false, size = size, xtags = xtags)

        fun format(id: String, hasVideo: Boolean, size: Long, xtags: String) = MediaFormat(
            formatId = id,
            container = if (hasVideo) "mp4" else "m4a",
            width = if (hasVideo) 1280 else null,
            height = if (hasVideo) 720 else null,
            hasVideo = hasVideo,
            hasAudio = true,
            fileSizeBytes = size,
            url = "https://cdn.test/$id?xtags=${xtags.replace("=", "%3D")}",
            videoCodec = if (hasVideo) "avc1" else null,
            audioCodec = "mp4a.40.2",
        )
    }
}
