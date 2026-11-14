package com.dewijones92.totum.data.torrent

import com.dewijones92.totum.common.HttpUrl
import com.dewijones92.totum.data.torrent.fake.FakeHomeTorrentServer
import com.dewijones92.totum.domain.MediaContentKind
import com.dewijones92.totum.domain.PlayHandle
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A torrent becoming ordinary queue items, which is the whole unification argument.
 *
 * If these come out as plain `PlayableItem`s with a direct URL, then "queue this season" is the
 * same operation as "play all of this playlist" and nothing above this point needs to know a
 * torrent was involved.
 */
class TorrentPlayablesTest {

    private fun queued(
        server: HomeTorrentServer,
        torrent: PreparedTorrent,
        kind: MediaContentKind = MediaContentKind.STANDARD,
        artwork: HttpUrl? = null,
    ) = runBlocking { TorrentPlayables.queueItems(server, torrent, kind, artwork) }

    private val server = FakeHomeTorrentServer()

    private fun pack(vararg names: String) = PreparedTorrent(
        hash = "abc123",
        name = "Some.Show.S01.1080p-GROUP",
        files = names.mapIndexed { i, n -> TorrentFile(i + 1, "Some.Show.S01/$n", 1_000_000_000) },
    )

    @Test
    fun `a season pack becomes one queue item per episode, in order`() {
        val items = queued(server, pack("S01E02.mkv", "S01E01.mkv", "S01E03.mkv"))

        assertEquals(listOf("S01E01", "S01E02", "S01E03"), items.map { it.item.title })
    }

    /**
     * The unification claim, asserted rather than described: these must be directly-playable
     * items, so the existing playback path takes them with no torrent-specific branch.
     */
    @Test
    fun `every episode wears the torrent's picture`() {
        val poster = HttpUrl.of("https://images.metahub.space/poster/medium/tt1160419/img")

        val items = queued(server, pack("S01E01.mkv", "S01E02.mkv"), artwork = poster)

        assertEquals(listOf(poster, poster), items.map { it.item.thumbnailUrl })
    }

    @Test
    fun `items are directly playable with a plain URL`() {
        val items = queued(server, pack("S01E01.mkv"))

        val item = items.single()
        assertTrue("must use the direct-URL handle", item.handle is PlayHandle.Podcast)
        assertNotNull("and carry a media URL the player can take", item.item.mediaUrl)
        assertTrue(item.item.mediaUrl!!.value.contains("link=abc123"))
    }

    /**
     * Ids must survive a restart, or play-position and history cannot work — the same episode
     * has to be the same item tomorrow.
     */
    @Test
    fun `ids are stable and unique per file`() {
        val items = queued(server, pack("S01E01.mkv", "S01E02.mkv"))

        assertEquals(listOf("torrent:abc123:1", "torrent:abc123:2"), items.map { it.item.id.value })
        assertEquals(
            items.map { it.item.id },
            queued(server, pack("S01E01.mkv", "S01E02.mkv")).map { it.item.id }
        )
    }

    /** A film's row should say the film, not "feature.mkv". */
    @Test
    fun `a single-file torrent is titled after the release, not the filename`() {
        val single =
            PreparedTorrent("h", "Night Of The Living Dead 1968 720p", listOf(TorrentFile(1, "x/feature.mkv", 1)))

        assertEquals(
            "Night Of The Living Dead 1968 720p",
            queued(server, single).single().item.title
        )
    }

    @Test
    fun `samples and junk never reach the queue`() {
        val torrent = PreparedTorrent(
            hash = "abc123",
            name = "Some.Show.S01.1080p-GROUP",
            files = listOf(
                TorrentFile(1, "Some.Show.S01/sample.mkv", 40_000_000),
                TorrentFile(2, "Some.Show.S01/S01E01.mkv", 1_000_000_000),
                TorrentFile(3, "Some.Show.S01/notes.nfo", 1_000),
            ),
        )

        val items = queued(server, torrent)

        assertEquals(1, items.size)
    }

    @Test
    fun `a book's chapters queue as chapters in order and play their own audio file`() {
        val items = queued(
            server,
            pack("Chapter 10.mp3", "Chapter 2.mp3", "Chapter 1.mp3"),
            MediaContentKind.AUDIOBOOK,
        )

        assertEquals(listOf("Chapter 1", "Chapter 2", "Chapter 10"), items.map { it.item.title })
        assertEquals(setOf(MediaContentKind.AUDIOBOOK), items.map { it.item.contentKind }.toSet())
        assertEquals(listOf(null, null, null), items.map { (it.handle as PlayHandle.Podcast).audioUrl })
    }

    @Test
    fun `a video keeps its audio-only stream and its kind`() {
        val item = queued(server, pack("S01E01.mkv")).single()

        assertNotNull((item.handle as PlayHandle.Podcast).audioUrl)
        assertEquals(MediaContentKind.STANDARD, item.item.contentKind)
    }

    private val spinalTap = PreparedTorrent(
        hash = "6ce2aa412ca6e28a35f711b40b687f0a3d4148b4",
        name = "Spinal Tap II: The End Continues (2025) (1080p BluRay x265 10bit EAC3 Atmos 7.1 English + French " +
            "+ German + Italian + Spanish r00t) [QxR]",
        files = listOf(
            TorrentFile(1, "Spinal Tap II/Subs/English.srt", 90_000),
            TorrentFile(2, "Spinal Tap II/Subs/French.srt", 90_000),
            TorrentFile(3, "Spinal Tap II/Subs/German.srt", 90_000),
            TorrentFile(4, "Spinal Tap II/Subs/Italian.srt", 90_000),
            TorrentFile(5, "Spinal Tap II/Subs/Spanish.srt", 90_000),
            TorrentFile(6, "Spinal Tap II/poster.jpg", 400_000),
            TorrentFile(7, "Spinal Tap II/RARBG.txt", 100),
            TorrentFile(8, "Spinal Tap II/info.nfo", 2_000),
            TorrentFile(9, "Spinal Tap II/cover.jpg", 300_000),
            TorrentFile(10, "Spinal Tap II/Featurettes/History of the Group.mkv", 107_843_601),
            TorrentFile(11, "Spinal Tap II/Featurettes/The Legacy Continues.mkv", 139_304_509),
            TorrentFile(
                12,
                "Spinal Tap II/Spinal Tap II - The End Continues (2025) (1080p BluRay x265 10bit r00t).mkv",
                5_500_000_000,
            ),
        ),
    )

    @Test
    fun `a film with its featurettes queues the film first and is titled by its clean name`() {
        val items = queued(server, spinalTap)

        assertEquals(
            listOf("torrent:6ce2aa412ca6e28a35f711b40b687f0a3d4148b4:12") +
                listOf(10, 11).map { "torrent:6ce2aa412ca6e28a35f711b40b687f0a3d4148b4:$it" },
            items.map { it.item.id.value },
        )
        assertEquals("Spinal Tap II: The End Continues (2025)", items.first().item.title)
        assertEquals(listOf("History of the Group", "The Legacy Continues"), items.drop(1).map { it.item.title })
    }

    @Test
    fun `a picture the caller already has is used and nothing is looked up`() {
        val given = HttpUrl.of("https://site.test/poster.jpg")

        queued(server, pack("S01E01.mkv"), artwork = given)

        assertEquals(emptyList<String>(), server.postersAsked)
    }

    @Test
    fun `a poster found alongside fills only the items that have none`() {
        val looked = HttpUrl.of("https://images.metahub.space/poster/medium/tt1/img")
        val given = HttpUrl.of("https://site.test/poster.jpg")
        server.posters["Some.Show.S01.1080p-GROUP"] = looked
        val torrent = pack("S01E01.mkv", "S01E02.mkv")
        val bare = queued(server, torrent)
        val mixed = listOf(bare[0], bare[1].let { it.copy(item = it.item.copy(thumbnailUrl = given)) })

        val found = runBlocking { TorrentPlayables.posterFor(server, torrent) }
        val patched = TorrentPlayables.withPoster(mixed, found)

        assertEquals(listOf(looked, given), patched.map { it.item.thumbnailUrl })
    }

    @Test
    fun `an audiobook is never looked up as a film`() {
        val found = runBlocking {
            TorrentPlayables.posterFor(
                server,
                pack("Chapter 1.mp3"),
                MediaContentKind.AUDIOBOOK
            )
        }

        assertEquals(null, found)
        assertEquals(emptyList<String>(), server.postersAsked)
    }

    @Test
    fun `no picture found leaves the thumbnail empty`() {
        assertEquals(listOf(null), queued(server, pack("S01E01.mkv")).map { it.item.thumbnailUrl })
    }

    @Test
    fun `a poster lookup that never answers gives up rather than holding a send`() {
        val slow = object : HomeTorrentServer by FakeHomeTorrentServer() {
            override suspend fun posterFor(torrentName: String): HttpUrl? {
                delay(60_000)
                return null
            }
        }

        val found = runBlocking { withTimeout(10_000) { TorrentPlayables.posterFor(slow, pack("S01E01.mkv")) } }

        assertEquals(null, found)
    }
}
