package com.dewijones92.totum.data.torrent

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class TorrentsSeenTest {

    @get:Rule
    val folder = TemporaryFolder()

    private fun torrent(hash: String, vararg paths: String) =
        PreparedTorrent(
            hash,
            "Public Domain Show S01",
            paths.mapIndexed { i, path -> TorrentFile(i, path, 1_000_000_000) }
        )

    @Test
    fun `a prepared torrent's playable files are kept across a restart`() = runTest {
        val file = folder.root.resolve("seen.json")
        TorrentsSeen(file).remember(torrent("ABC", "Show/S01E01.mkv", "Show/notes.nfo", "Show/S01E02.mkv"))

        val again = TorrentsSeen(file).also { it.load() }

        val record = again.records.value.getValue("abc")
        assertEquals("Public Domain Show S01", record.name)
        assertEquals(listOf(0, 2), record.files.map { it.index })
    }

    @Test
    fun `only the most recent are kept`() = runTest {
        var now = 0L
        val seen = TorrentsSeen(folder.root.resolve("seen.json"), keep = 2, clock = { now++ })
        listOf("a", "b", "c").forEach { seen.remember(torrent(it, "x.mkv")) }

        assertEquals(setOf("b", "c"), seen.records.value.keys)
    }

    @Test
    fun `an unreadable file starts afresh rather than failing`() = runTest {
        val file = folder.root.resolve("seen.json").apply { writeText("not json") }
        val seen = TorrentsSeen(file).also { it.load() }
        assertEquals(emptyMap<String, SeenTorrent>(), seen.records.value)
    }
}
