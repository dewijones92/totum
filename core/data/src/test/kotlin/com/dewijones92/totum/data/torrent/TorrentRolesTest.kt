package com.dewijones92.totum.data.torrent

import com.dewijones92.totum.data.torrent.fake.FakeHomeTorrentServer
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

class TorrentRolesTest {

    private fun file(index: Int, path: String, size: Long) = TorrentFile(index, path, size)

    private fun roles(vararg files: TorrentFile): Map<Int, TorrentFileRole> =
        TorrentRoles.classify(files.toList()).associate { it.file.index to it.role }

    @Test
    fun `a small video beside a much larger one is an extra, whatever its folder`() {
        assertEquals(
            mapOf(1 to TorrentFileRole.MAIN, 2 to TorrentFileRole.EXTRA),
            roles(file(1, "F/film.mkv", 5_000_000_000), file(2, "F/making of.mkv", 700_000_000)),
        )
    }

    @Test
    fun `a video in an extras folder is an extra even when it is not small`() {
        assertEquals(
            mapOf(1 to TorrentFileRole.MAIN, 2 to TorrentFileRole.EXTRA),
            roles(file(1, "F/film.mkv", 5_000_000_000), file(2, "F/Behind The Scenes/doc.mkv", 3_000_000_000)),
        )
    }

    @Test
    fun `a sample folder is a sample`() {
        assertEquals(
            mapOf(1 to TorrentFileRole.MAIN, 2 to TorrentFileRole.SAMPLE),
            roles(file(1, "F/film.mkv", 5_000_000_000), file(2, "F/Sample/clip.mkv", 50_000_000)),
        )
    }

    @Test
    fun `a pack of films of similar size has no extras`() {
        assertEquals(
            mapOf(1 to TorrentFileRole.MAIN, 2 to TorrentFileRole.MAIN, 3 to TorrentFileRole.MAIN),
            roles(
                file(1, "P/One.mkv", 4_000_000_000),
                file(2, "P/Two.mkv", 3_500_000_000),
                file(3, "P/Three.mkv", 3_000_000_000),
            ),
        )
    }

    @Test
    fun `episodes are all main, the short ones included, and keep their order`() {
        val files = listOf(
            file(1, "S/Show.S01E02.mkv", 300_000_000),
            file(2, "S/Show.S01E01.mkv", 2_000_000_000),
            file(3, "S/Show.S01E03.mkv", 100_000_000),
        )

        assertEquals(setOf(TorrentFileRole.MAIN), TorrentRoles.classify(files).map { it.role }.toSet())
        assertEquals(listOf(2, 1, 3), TorrentEpisodes.playableInOrder(files).map { it.index })
    }

    @Test
    fun `an audiobook's short chapters are not extras`() {
        assertEquals(
            setOf(TorrentFileRole.MAIN),
            roles(file(1, "B/Chapter 1.mp3", 90_000_000), file(2, "B/Chapter 2.mp3", 4_000_000)).values.toSet(),
        )
    }

    @Test
    fun `when everything looks like an extra the largest is the main file`() {
        assertEquals(
            mapOf(1 to TorrentFileRole.EXTRA, 2 to TorrentFileRole.MAIN),
            roles(file(1, "X/Extras/a.mkv", 100), file(2, "X/Extras/b.mkv", 900)),
        )
    }

    @Test
    fun `the clean name drops the release tail and keeps the year`() {
        assertEquals(
            "Toy Story 5 (2026)",
            TorrentNames.clean("Toy.Story.5.2026.1080p.WEBRip.10Bit.DDP5.1.x265-NeoNoir")
        )
        assertEquals("Some Show", TorrentNames.clean("Some.Show.S01.1080p-GROUP"))
        assertEquals("plain name", TorrentNames.clean("plain name"))
    }

    @Test
    fun `details give every queued file its size and role`() {
        val torrent = PreparedTorrent(
            "abc",
            "A.Film.2020.1080p",
            listOf(file(1, "A/a.mkv", 5_000_000_000), file(2, "A/Extras/b.mkv", 100)),
        )

        val details = TorrentPlayables.details(torrent)

        assertEquals(TorrentFileRole.EXTRA, details.getValue(TorrentProgress.itemId("abc", 2)).role)
        assertEquals(5_000_000_000, details.getValue(TorrentProgress.itemId("abc", 1)).sizeBytes)
        assertEquals(
            listOf("A Film (2020)", "b"),
            runBlocking { TorrentPlayables.queueItems(FakeHomeTorrentServer(), torrent) }.map { it.item.title },
        )
    }
}
