package com.dewijones92.totum.data.torrent

import com.dewijones92.totum.data.torrent.fake.FakeHomeTorrentServer
import com.dewijones92.totum.domain.MediaContentKind
import com.dewijones92.totum.domain.PlayHandle
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

    private val server = FakeHomeTorrentServer()

    private fun pack(vararg names: String) = PreparedTorrent(
        hash = "abc123",
        name = "Some.Show.S01.1080p-GROUP",
        files = names.mapIndexed { i, n -> TorrentFile(i + 1, "Some.Show.S01/$n", 1_000_000_000) },
    )

    @Test
    fun `a season pack becomes one queue item per episode, in order`() {
        val items = TorrentPlayables.queueItems(server, pack("S01E02.mkv", "S01E01.mkv", "S01E03.mkv"))

        assertEquals(listOf("S01E01", "S01E02", "S01E03"), items.map { it.item.title })
    }

    /**
     * The unification claim, asserted rather than described: these must be directly-playable
     * items, so the existing playback path takes them with no torrent-specific branch.
     */
    @Test
    fun `items are directly playable with a plain URL`() {
        val items = TorrentPlayables.queueItems(server, pack("S01E01.mkv"))

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
        val items = TorrentPlayables.queueItems(server, pack("S01E01.mkv", "S01E02.mkv"))

        assertEquals(listOf("torrent:abc123:1", "torrent:abc123:2"), items.map { it.item.id.value })
        assertEquals(
            items.map { it.item.id },
            TorrentPlayables.queueItems(server, pack("S01E01.mkv", "S01E02.mkv")).map { it.item.id }
        )
    }

    /** A film's row should say the film, not "feature.mkv". */
    @Test
    fun `a single-file torrent is titled after the release, not the filename`() {
        val single =
            PreparedTorrent("h", "Night Of The Living Dead 1968 720p", listOf(TorrentFile(1, "x/feature.mkv", 1)))

        assertEquals(
            "Night Of The Living Dead 1968 720p",
            TorrentPlayables.queueItems(server, single).single().item.title
        )
    }

    @Test
    fun `samples and junk never reach the queue`() {
        val items = TorrentPlayables.queueItems(server, pack("sample.mkv", "S01E01.mkv", "notes.nfo"))

        assertEquals(1, items.size)
    }

    @Test
    fun `a book's chapters queue as chapters in order and play their own audio file`() {
        val items = TorrentPlayables.queueItems(
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
        val item = TorrentPlayables.queueItems(server, pack("S01E01.mkv")).single()

        assertNotNull((item.handle as PlayHandle.Podcast).audioUrl)
        assertEquals(MediaContentKind.STANDARD, item.item.contentKind)
    }
}
