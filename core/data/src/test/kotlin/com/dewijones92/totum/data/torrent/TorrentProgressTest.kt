package com.dewijones92.totum.data.torrent

import com.dewijones92.totum.domain.PlayState
import org.junit.Assert.assertEquals
import org.junit.Test

class TorrentProgressTest {

    private val hash = "a".repeat(40)
    private val magnet = TorrentLink.Magnet("magnet:?xt=urn:btih:${hash.uppercase()}&dn=x")
    private fun id(index: Int, of: String = hash) = TorrentProgress.itemId(of, index)
    private fun pack(name: String = "Public Domain Show S01 1080p", of: String = hash, files: Int = 4) =
        SeenTorrent(of, name, (0 until files).map { SeenFile(it, "S01E%02d".format(it + 1)) }, atEpochMs = 0)

    @Test
    fun `nothing played says nothing`() {
        assertEquals(emptyList<String>(), TorrentProgress.chips("Film 1968 1080p", magnet, emptyMap(), emptyMap()))
    }

    @Test
    fun `a single film says watched, or how far in`() {
        val film = mapOf(hash to pack(name = "Film 1968 1080p", files = 1))
        assertEquals(
            listOf("✅ Watched"),
            TorrentProgress.chips("Film 1968", magnet, mapOf(id(0) to PlayState.Played), film)
        )
        assertEquals(
            listOf("▶️ 34 of 96 min"),
            TorrentProgress.chips(
                "Film 1968",
                magnet,
                mapOf(id(0) to PlayState.InProgress(34 * 60_000L, 96 * 60_000L)),
                film
            ),
        )
    }

    @Test
    fun `a pack says how many are watched and which one you are in the middle of`() {
        val states = mapOf(
            id(0) to PlayState.Played,
            id(1) to PlayState.Played,
            id(2) to PlayState.InProgress(12 * 60_000L, null),
        )
        assertEquals(
            listOf("✅ 2 of 4 watched", "▶️ S01E03: 12 min in"),
            TorrentProgress.chips("Show S01", magnet, states, mapOf(hash to pack())),
        )
    }

    @Test
    fun `a pack never opened on this phone still counts what played, by file number`() {
        val states = mapOf(id(0) to PlayState.Played, id(5) to PlayState.InProgress(60_000L, null))
        assertEquals(
            listOf("✅ 1 watched", "▶️ file 5: 1 min in"),
            TorrentProgress.chips("Show S01", magnet, states, emptyMap())
        )
    }

    @Test
    fun `another copy of the same film you played is noticed`() {
        val other = "b".repeat(40)
        val seen = mapOf(other to pack(name = "Night.of.the.Living.Dead.1968.720p.BRRip", of = other, files = 1))
        val states = mapOf(id(0, of = other) to PlayState.InProgress(60_000L, null))
        val viaIndexer = TorrentLink.ViaIndexer("/1/download")

        assertEquals(
            listOf("👀 You've played another copy of this"),
            TorrentProgress.chips("Night of the Living Dead (1968) [1080p] [YTS]", viaIndexer, states, seen),
        )
        assertEquals(
            emptyList<String>(),
            TorrentProgress.chips("Night of the Comet 1984 1080p", viaIndexer, states, seen)
        )
    }

    @Test
    fun `resuming starts at the file in progress, else the next unwatched, else the first`() {
        val files = (0..3).map(::id)
        assertEquals(
            id(2),
            TorrentProgress.resumeAt(files, mapOf(id(0) to PlayState.Played, id(2) to PlayState.InProgress(1, null)))
        )
        assertEquals(
            id(2),
            TorrentProgress.resumeAt(files, mapOf(id(0) to PlayState.Played, id(1) to PlayState.Played))
        )
        assertEquals(id(0), TorrentProgress.resumeAt(files, emptyMap()))
        assertEquals(id(0), TorrentProgress.resumeAt(files, files.associateWith { PlayState.Played }))
        assertEquals(
            id(1),
            TorrentProgress.resumeAt(files, mapOf(id(0) to PlayState.Played, id(3) to PlayState.Played).let { it })
        )
    }

    @Test
    fun `the title key is the words before the year or the quality, plus the year`() {
        assertEquals(
            "night of the living dead 1968",
            TorrentProgress.keyOf("Night.of.the.Living.Dead.1968.REMASTERED.1080p")
        )
        assertEquals("some show s01e02", TorrentProgress.keyOf("Some Show S01E02 720p WEB"))
        assertEquals(null, TorrentProgress.keyOf("1080p only"))
    }

    @Test
    fun `a pack opened before this build, one episode played, is not called watched`() {
        val states = mapOf(id(3) to PlayState.Played)
        assertEquals(listOf("✅ 1 watched"), TorrentProgress.chips("Show S01", magnet, states, emptyMap()))
    }

    @Test
    fun `the chip names the same file the tap would resume`() {
        val states = mapOf(id(3) to PlayState.InProgress(60_000L, null), id(1) to PlayState.InProgress(120_000L, null))
        assertEquals(
            listOf("▶️ S01E02: 2 min in"),
            TorrentProgress.chips("Show S01", magnet, states, mapOf(hash to pack()))
        )
    }

    @Test
    fun `under a minute in says so`() {
        val film = mapOf(hash to pack(name = "Film 1968", files = 1))
        assertEquals(
            listOf("▶️ under a minute of 96 min"),
            TorrentProgress.chips(
                "Film 1968",
                magnet,
                mapOf(id(0) to PlayState.InProgress(20_000L, 96 * 60_000L)),
                film
            ),
        )
    }
}
