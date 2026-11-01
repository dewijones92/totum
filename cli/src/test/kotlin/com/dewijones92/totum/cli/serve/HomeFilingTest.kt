package com.dewijones92.totum.cli.serve

import com.dewijones92.totum.cli.serve.filing.FilePlan
import com.dewijones92.totum.cli.serve.filing.FolderLibrary
import com.dewijones92.totum.cli.serve.filing.LibraryFiler
import com.dewijones92.totum.cli.serve.filing.Shelf
import com.dewijones92.totum.common.HttpUrl
import com.dewijones92.totum.data.download.JsonDownloadStore
import com.dewijones92.totum.data.torrent.TorrentPlayables
import com.dewijones92.totum.domain.DownloadState
import com.dewijones92.totum.domain.MediaItem
import com.dewijones92.totum.domain.MediaItemId
import com.dewijones92.totum.domain.PlayHandle
import com.dewijones92.totum.domain.PlayableItem
import com.dewijones92.totum.domain.SourceId
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class HomeFilingTest {

    private val root = Files.createTempDirectory("home-filing").toFile()
    private val data = File(root, "data")
    private val downloads = File(root, "media/totum").apply { mkdirs() }
    private val movies = File(root, "media/videos/movies").apply { mkdirs() }
    private val series = File(root, "media/videos/series").apply { mkdirs() }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val asked = mutableListOf<Pair<String, String?>>()
    private val filer = LibraryFiler(
        FolderLibrary(mapOf(Shelf.MOVIES to movies, Shelf.SERIES to series)),
        null,
        decide = { r ->
            asked += r.name to r.from
            FilePlan(FilePlan.Action.MOVIE, "movies", "Toy Story 5 (2026)", "Toy Story 5 (2026).mkv", "a film")
        },
    )

    @After
    fun clean() {
        scope.cancel()
        root.deleteRecursively()
    }

    private fun item(id: String, source: SourceId, url: String) = PlayableItem(
        MediaItem(
            id = MediaItemId(id),
            sourceId = source,
            title = "Toy Story 5",
            publishedAt = null,
            duration = null,
            author = "Toy.Story.5.2026.1080p.x265.10bit",
            mediaUrl = HttpUrl.of(url),
        ),
        PlayHandle.Podcast(),
    )

    private val torrent = item(
        "torrent:abc:1",
        TorrentPlayables.SOURCE,
        "http://gluetun:8090/stream/Toy%20Story%205%20(2026)%20x265.mkv?link=abc&index=1&play",
    )

    private fun seed(item: PlayableItem, path: String, audioOnly: Boolean = false) = runBlocking {
        JsonDownloadStore(File(data, "downloads.json"))
            .put(item, DownloadState.Downloaded(path, audioOnly = audioOnly), audioOnly = audioOnly)
    }

    private fun youTube(id: String) = PlayableItem(
        MediaItem(
            id = MediaItemId(id),
            sourceId = SourceId("yt:channel"),
            title = "Muddy Puddles - Peppa Pig Full Episode",
            publishedAt = null,
            duration = null,
            author = "Peppa Pig - Official Channel",
        ),
        PlayHandle.Video(HttpUrl.of("https://www.youtube.com/watch?v=$id")),
    )

    private fun home() = HomeDownloads(data, downloads, scope, freeFloorBytes = 0, filer = filer)

    private suspend fun until(what: String, check: suspend () -> Boolean) = withTimeout(10_000) {
        while (!check()) delay(50)
        what
    }

    @Test
    fun `a finished torrent is filed into the library and its record follows it`() = runBlocking {
        seed(torrent, File(downloads, "abc.media").apply { writeText("film") }.path)
        val home = home()
        val filed = File(movies, "Toy Story 5 (2026)/Toy Story 5 (2026).mkv")
        until("filed") { home.fileOf("torrent:abc:1")?.canonicalPath == filed.canonicalPath }
        assertEquals("film", filed.readText())
        assertEquals(listOf("Toy Story 5 (2026) x265.mkv" to "Toy.Story.5.2026.1080p.x265.10bit"), asked)
        assertTrue(home.jobs().jobs.single().bytes == 4L)
    }

    @Test
    fun `a YouTube video is offered to the filer by its title and channel, a sound-only copy is not`() = runBlocking {
        seed(youTube("aaaaaaaaaaa"), File(downloads, "aaaaaaaaaaa.mkv").apply { writeText("pig") }.path)
        seed(
            youTube("bbbbbbbbbbb"),
            File(downloads, "bbbbbbbbbbb.m4a").apply { writeText("oink") }.path,
            audioOnly = true
        )
        val home = home()
        until("asked") { asked.isNotEmpty() }
        delay(300)
        assertEquals(
            listOf("Muddy Puddles - Peppa Pig Full Episode.mkv" to "YouTube channel: Peppa Pig - Official Channel"),
            asked,
        )
        assertEquals(File(downloads, "bbbbbbbbbbb.m4a").canonicalPath, home.fileOf("bbbbbbbbbbb")?.canonicalPath)
    }

    @Test
    fun `podcast episodes are not filed`() = runBlocking {
        val episode = item("ep1", SourceId("feed"), "http://example.com/stream/ep.mp3")
        val file = File(downloads, "ep1.media").apply { writeText("talk") }
        seed(episode, file.path)
        val home = home()
        delay(500)
        assertEquals(file.canonicalPath, home.fileOf("ep1")?.canonicalPath)
        assertTrue(asked.isEmpty())
    }

    @Test
    fun `a record whose file moved with its folder is pointed at where it is now`() = runBlocking {
        val podcast = item("ep2", SourceId("feed"), "http://example.com/ep2.mp3")
        val file = File(downloads, "ep2.media").apply { writeText("talk") }
        seed(podcast, "/downloads/ep2.media")
        val home = home()
        until("relocated") { home.fileOf("ep2")?.canonicalPath == file.canonicalPath }
        Unit
    }
}
