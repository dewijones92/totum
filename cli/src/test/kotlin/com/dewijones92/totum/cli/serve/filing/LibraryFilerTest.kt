package com.dewijones92.totum.cli.serve.filing

import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.net.InetSocketAddress
import java.nio.file.Files

class LibraryFilerTest {

    private val root = Files.createTempDirectory("filer").toFile()
    private val movies = File(root, "movies").apply { mkdirs() }
    private val series = File(root, "series").apply { File(this, "Slow Horses/Series 2").mkdirs() }
    private val incoming = File(root, "totum").apply { mkdirs() }
    private val library = FolderLibrary(mapOf(Shelf.MOVIES to movies, Shelf.SERIES to series))
    private val told = mutableListOf<String>()
    private val jellyfinServer = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
        createContext("/") { exchange ->
            val body = exchange.requestBody.readBytes().decodeToString()
            told += "${exchange.requestURI.path} ${exchange.requestHeaders.getFirst("Authorization")} $body"
            exchange.sendResponseHeaders(
                if (exchange.requestURI.path == "/Library/Media/Updated") updatedCode else 204,
                -1
            )
            exchange.close()
        }
        start()
    }
    private var updatedCode = 204
    private val jellyfin = Jellyfin(
        OkHttpClient(),
        "http://127.0.0.1:${jellyfinServer.address.port}",
        "k3y",
        mapOf(Shelf.MOVIES to "/data/movies", Shelf.SERIES to "/data/tvshows"),
    )

    @After
    fun clean() {
        jellyfinServer.stop(0)
        root.deleteRecursively()
    }

    private fun download(bytes: String = "film") = File(incoming, "torrent-abc-1.media").apply { writeText(bytes) }

    private fun filer(decide: suspend (FileRequest) -> FilePlan) = LibraryFiler(library, jellyfin, decide = decide)

    @Test
    fun `an episode moves into the season folder the brain chose and Jellyfin is told its path`() = runBlocking {
        val source = download()
        val outcome = filer { request ->
            assertTrue(request.context, "Slow Horses/Series 2/" in request.context)
            FilePlan(FilePlan.Action.EPISODE, "series", "Slow Horses/Series 2", request.name, "next episode")
        }.file(source, "Slow.Horses.S02E03.mkv", "Slow.Horses.S02.1080p")

        val moved = (outcome as LibraryFiler.Outcome.Moved).file
        assertEquals(File(series, "Slow Horses/Series 2/Slow.Horses.S02E03.mkv").canonicalPath, moved.canonicalPath)
        assertEquals("film", moved.readText())
        assertFalse(source.exists())
        assertEquals(1, told.size)
        assertTrue(told[0], told[0].startsWith("/Library/Media/Updated MediaBrowser Token=\"k3y\""))
        assertTrue(told[0], "/data/tvshows/Slow Horses/Series 2/Slow.Horses.S02E03.mkv" in told[0])
        assertTrue(told[0], "\"UpdateType\":\"Created\"" in told[0])
    }

    @Test
    fun `when the targeted update fails the whole library is refreshed`() = runBlocking {
        updatedCode = 500
        filer { FilePlan(FilePlan.Action.MOVIE, "movies", "Zog (2018)", "Zog (2018).mkv", "film") }
            .file(download(), "Zog.2018.1080p.mkv", null)
        assertEquals(listOf("/Library/Media/Updated", "/Library/Refresh"), told.map { it.substringBefore(' ') })
    }

    @Test
    fun `a failing brain files by rules instead`() = runBlocking {
        val outcome = filer { throw IllegalStateException("not signed in") }
            .file(download(), "Slow.Horses.S02E04.mkv", null)
        assertEquals(
            File(series, "Slow Horses/Series 2/Slow.Horses.S02E04.mkv").canonicalPath,
            (outcome as LibraryFiler.Outcome.Moved).file.canonicalPath,
        )
    }

    @Test
    fun `a YouTube video is never filed by rules when the brain is unavailable or refused`() = runBlocking {
        val source = download()
        val failing = filer { throw IllegalStateException("not signed in") }
            .file(source, "Inside the Forbidden Town.mkv", "YouTube channel: Bald and Bankrupt")
        assertTrue(failing is LibraryFiler.Outcome.Left)
        val refused = filer { FilePlan(FilePlan.Action.MOVIE, "movies", "../../etc", "x.mkv", "evil") }
            .file(source, "Inside the Forbidden Town.mkv", "YouTube channel: Bald and Bankrupt")
        assertTrue(refused is LibraryFiler.Outcome.Left)
        assertTrue(source.isFile)
    }

    @Test
    fun `a plan escaping the library is refused and filed by rules`() = runBlocking {
        val outcome = filer { FilePlan(FilePlan.Action.MOVIE, "movies", "../../etc", "x.mkv", "evil") }
            .file(download(), "Zog.2018.1080p.mkv", null)
        assertEquals(
            File(movies, "Zog (2018)/Zog (2018).mkv").canonicalPath,
            (outcome as LibraryFiler.Outcome.Moved).file.canonicalPath,
        )
    }

    @Test
    fun `leave keeps the file where it is and tells nobody`() = runBlocking {
        val source = download()
        val outcome = filer { FilePlan(FilePlan.Action.LEAVE, "movies", "", "", "a sample") }
            .file(source, "sample.mkv", null)
        assertTrue(outcome is LibraryFiler.Outcome.Left)
        assertTrue(source.exists())
        assertTrue(told.isEmpty())
    }

    @Test
    fun `a file that is not a video is never filed`() = runBlocking {
        val outcome = filer { error("not asked") }.file(download(), "notes.nfo", null)
        assertTrue(outcome is LibraryFiler.Outcome.Left)
    }

    @Test
    fun `a name already taken gets a number rather than overwriting`() = runBlocking {
        File(movies, "Zog (2018)").mkdirs()
        File(movies, "Zog (2018)/Zog (2018).mkv").writeText("older copy")
        val outcome = filer { FilePlan(FilePlan.Action.MOVIE, "movies", "Zog (2018)", "Zog (2018).mkv", "film") }
            .file(download(), "Zog.2018.mkv", null)
        assertEquals("Zog (2018) (2).mkv", (outcome as LibraryFiler.Outcome.Moved).file.name)
        assertEquals("older copy", File(movies, "Zog (2018)/Zog (2018).mkv").readText())
    }

    @Test
    fun `a folder it cannot write to is skipped for a fresh, clean-named folder beside it`() = runBlocking {
        val locked = File(movies, "www.UIndex.org - Zog 2018 1080p").apply {
            mkdirs()
            setWritable(false)
        }
        assumeTrue("the test user can write to a read-only folder", !locked.canWrite())
        val source = download()
        val outcome = filer {
            FilePlan(
                FilePlan.Action.MOVIE,
                "movies",
                "www.UIndex.org - Zog 2018 1080p",
                "www.UIndex.org - Zog.mkv",
                "film",
            )
        }.file(source, "Zog.2018.1080p.mkv", null)
        locked.setWritable(true)

        val moved = (outcome as LibraryFiler.Outcome.Moved).file
        assertEquals(File(movies, "Zog (2018)/Zog (2018).mkv").canonicalPath, moved.canonicalPath)
        assertFalse(source.exists())
        assertTrue(File(locked, "Zog.mkv").exists().not())
    }

    @Test
    fun `a refused plan is reported and subtitles are made after the move`() = runBlocking {
        val reported = mutableListOf<String>()
        val made = mutableListOf<String>()
        val filer = LibraryFiler(
            library,
            jellyfin,
            { FilePlan(FilePlan.Action.MOVIE, "movies", "../../etc", "x.mkv", "evil") },
            refused = { reported += it },
            sidecars = {
                made += it.name
                emptyList()
            },
        )
        filer.file(download(), "Zog.2018.1080p.mkv", null)
        assertTrue(reported.single(), "refused" in reported.single())
        assertEquals(listOf("Zog (2018).mkv"), made)
    }

    @Test
    fun `a file nowhere writable stays and is a home problem until it is filed`() = runBlocking {
        val problems = com.dewijones92.totum.cli.serve.FilingProblems()
        val locked = File(movies, "Zog 2018 1080p").apply { mkdirs() }
        locked.setWritable(false)
        movies.setWritable(false)
        assumeTrue("the test user can write to a read-only folder", !locked.canWrite() && !movies.canWrite())
        val filer = LibraryFiler(
            library,
            jellyfin,
            { FilePlan(FilePlan.Action.MOVIE, "movies", "Zog 2018 1080p", "Zog.mkv", "film") },
            problems = problems,
        )
        val source = download()

        val left = filer.file(source, "Zog.2018.1080p.mkv", null)

        assertTrue(left.toString(), left is LibraryFiler.Outcome.Left)
        assertTrue(source.exists())
        assertTrue(problems.current().single().text, "not writable" in problems.current().single().text)

        movies.setWritable(true)
        locked.setWritable(true)
        val moved = filer.file(source, "Zog.2018.1080p.mkv", null)

        assertTrue(moved.toString(), moved is LibraryFiler.Outcome.Moved)
        assertTrue(problems.current().isEmpty())
    }

    @Test
    fun `a file is checked for late tracks before it is filed, and a failed fix is a home problem that stays`() =
        runBlocking {
            val problems = com.dewijones92.totum.cli.serve.FilingProblems()
            val fixed = mutableListOf<String>()
            val filer = LibraryFiler(
                library,
                jellyfin,
                { FilePlan(FilePlan.Action.MOVIE, "movies", "Zog (2018)", "Zog (2018).mkv", "film") },
                remux = {
                    fixed += it.name
                    MkvRemuxOutcome.Failed("ffmpeg exited 1")
                },
                problems = problems,
            )

            val outcome = filer.file(download(), "Zog.2018.mkv", null)

            assertEquals(listOf("torrent-abc-1.media"), fixed)
            assertTrue(outcome.toString(), outcome is LibraryFiler.Outcome.Moved)
            assertTrue(problems.current().single().text, "ffmpeg exited 1" in problems.current().single().text)
        }
}
