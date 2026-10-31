package com.dewijones92.totum.data.torrent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Reading episode numbers out of release filenames.
 *
 * Deliberately conservative, because this small fragile thing sits in front of a feature that
 * must not break. A wrong episode label is worse than none — the wrong one is believed — so
 * anything unreadable keeps its filename and its place in the list.
 */
class TorrentEpisodesTest {

    private fun file(name: String, size: Long = 1_000) =
        TorrentFile(index = 1, path = "Some.Release/$name", sizeBytes = size)

    @Test
    fun `a file whose name holds a hash or a question mark is still playable, a sample is not`() {
        val playable = listOf("Show/Peep Show #3.mkv", "Show/Why?.mp4", "Show/sample.mkv", "Show/notes.txt")
            .mapIndexed { i, path -> TorrentFile(i, path, 1L).isPlayable }
        assertEquals(listOf(true, true, false, false), playable)
    }

    @Test
    fun `reads the common SxxExx form`() {
        assertEquals(TorrentEpisodes.Episode(1, 3), TorrentEpisodes.episodeOf("Show.S01E03.1080p.mkv"))
        assertEquals(TorrentEpisodes.Episode(12, 24), TorrentEpisodes.episodeOf("Show.s12e24.mkv"))
    }

    @Test
    fun `reads the older 1x03 form`() {
        assertEquals(TorrentEpisodes.Episode(1, 3), TorrentEpisodes.episodeOf("Dragnet 1x03 The Big Ruling.avi"))
    }

    /**
     * The trap this exists to avoid: a resolution looks exactly like the old cross form. Reading
     * `1920x1080` as season 1920 would be silently absurd.
     */
    @Test
    fun `a resolution is not an episode number`() {
        assertNull(TorrentEpisodes.episodeOf("Night.of.the.Living.Dead.1968.1920x1080.mkv"))
    }

    /** A film has no episode, and inventing one would mislabel every film in the app. */
    @Test
    fun `a film parses as nothing`() {
        assertNull(TorrentEpisodes.episodeOf("Night Of The Living Dead 1968 720p BRRip x264-x0r.mkv"))
    }

    @Test
    fun `a season pack queues in broadcast order, not torrent order`() {
        val files = listOf(file("Show.S01E03.mkv"), file("Show.S01E01.mkv"), file("Show.S01E02.mkv"))

        val ordered = TorrentEpisodes.playableInOrder(files).map { TorrentEpisodes.label(it) }

        assertEquals(listOf("S01E01", "S01E02", "S01E03"), ordered)
    }

    /**
     * A pack nothing can be read from must come out EXACTLY as the torrent had it. A
     * half-successful parse that scrambles the order is worse than not trying.
     */
    @Test
    fun `files that parse as nothing keep their original order`() {
        val files = listOf(file("part3.mkv"), file("part1.mkv"), file("part2.mkv"))

        val ordered = TorrentEpisodes.playableInOrder(files).map { it.name }

        assertEquals(listOf("part3.mkv", "part1.mkv", "part2.mkv"), ordered)
    }

    /** Extras sort after the episodes: queueing "behind the scenes" before episode one is odd. */
    @Test
    fun `unparsed files sort after the ones that parsed`() {
        val files = listOf(file("Behind.The.Scenes.mkv"), file("Show.S01E02.mkv"), file("Show.S01E01.mkv"))

        val ordered = TorrentEpisodes.playableInOrder(files).map { it.name }

        assertEquals(listOf("Show.S01E01.mkv", "Show.S01E02.mkv", "Behind.The.Scenes.mkv"), ordered)
    }

    @Test
    fun `an unreadable name still gets a usable label, not a blank`() {
        assertEquals("some.odd.release", TorrentEpisodes.label(file("some.odd.release.mkv")))
    }

    /** Samples and non-video files are never offered — a 30s sample.mkv is a valid video. */
    @Test
    fun `samples and junk are left out`() {
        val files = listOf(file("sample.mkv"), file("Show.S01E01.mkv"), file("readme.nfo"))

        assertEquals(listOf("Show.S01E01.mkv"), TorrentEpisodes.playableInOrder(files).map { it.name })
    }

    @Test
    fun `an audiobook's chapters play in their numbered order, not the torrent's`() {
        val files = listOf(
            TorrentFile(0, "Book/Chapter 10.mp3", 1L),
            TorrentFile(1, "Book/Chapter 2.mp3", 1L),
            TorrentFile(2, "Book/cover.jpg", 1L),
            TorrentFile(3, "Book/Chapter 1.mp3", 1L),
        )

        val ordered = TorrentEpisodes.playableInOrder(files).map { it.name }

        assertEquals(listOf("Chapter 1.mp3", "Chapter 2.mp3", "Chapter 10.mp3"), ordered)
    }

    @Test
    fun `an m4b book is playable`() {
        assertEquals(true, TorrentFile(0, "Book/Dune.m4b", 1L).isPlayable)
    }
}
