package com.dewijones92.totum.data.torrent

import org.junit.Assert.assertEquals
import org.junit.Test

class TorrentSubtitlesTest {

    private val folder = "The.White.Lotus.S01.1080p.WEBRip.x265[eztv.re]"
    private fun ep(n: Int) = "The.White.Lotus.S01E0$n.1080p.WEBRip.x265-RARBG[eztv.re]"

    private val whiteLotus = PreparedTorrent(
        "bbea9eeb3d6d",
        "The White Lotus S01",
        (1..2).flatMap { n ->
            listOf(
                TorrentFile(n * 2 - 1, "$folder/Subs/${ep(n)}/2_English.srt", 62_742),
                TorrentFile(n * 2, "$folder/Subs/${ep(n)}/3_English.srt", 70_428),
            )
        } + listOf(
            TorrentFile(13, "$folder/${ep(1)}.mp4", 938_948_908),
            TorrentFile(14, "$folder/${ep(2)}.mp4", 960_412_901),
        ),
    )

    @Test
    fun `an episode gets the subtitle files in its own Subs folder and no other episode's`() {
        val episodeOne = whiteLotus.files.single { it.index == 13 }

        val found = TorrentSubtitles.forVideo(whiteLotus, episodeOne)

        assertEquals(listOf(1, 2), found.map { it.file.index })
        assertEquals(listOf("en", "en"), found.map { it.languageCode })
        assertEquals(listOf("English 1 (in the torrent)", "English 2 (in the torrent)"), found.map { it.label })
    }

    @Test
    fun `a subtitle file beside the video with its name is found`() {
        val movie = PreparedTorrent(
            "h",
            "Film",
            listOf(
                TorrentFile(0, "Film/Film.2020.mkv", 1_000_000_000),
                TorrentFile(1, "Film/Film.2020.en.srt", 50_000),
                TorrentFile(2, "Film/Other.srt", 50_000),
                TorrentFile(3, "Film/Film.2020.nfo", 1_000),
            ),
        )

        val found = TorrentSubtitles.forVideo(movie, movie.files[0])

        assertEquals(listOf(1), found.map { it.file.index })
        assertEquals("en", found.single().languageCode)
    }

    @Test
    fun `with one video, any file in its Subs folder is its subtitle`() {
        val movie = PreparedTorrent(
            "h",
            "Film",
            listOf(
                TorrentFile(0, "Film/Film.mp4", 1_000_000_000),
                TorrentFile(1, "Film/Subs/English.srt", 50_000),
                TorrentFile(2, "Film/Subs/French.srt", 50_000),
            ),
        )

        assertEquals(listOf("en", "fr"), TorrentSubtitles.forVideo(movie, movie.files[0]).map { it.languageCode })
    }
}
