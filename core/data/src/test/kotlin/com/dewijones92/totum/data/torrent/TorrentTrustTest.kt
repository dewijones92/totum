package com.dewijones92.totum.data.torrent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Duration
import java.time.Instant

class TorrentTrustTest {

    private val now = Instant.parse("2026-10-30T12:00:00Z")

    private fun result(
        title: String = "Night of the Living Dead 1968 1080p BluRay x264-AMIABLE",
        seeders: Int = 104,
        sizeBytes: Long = 1_600_000_000,
        indexer: String? = "The Pirate Bay",
        uploader: Uploader = Uploader.UNKNOWN,
        uploaderName: String? = null,
        leechers: Int? = null,
        publishedAt: Instant? = null,
    ) = TorrentResult(
        title, TorrentLink.Magnet("magnet:?xt=urn:btih:x"), seeders, sizeBytes, indexer,
        leechers = leechers, publishedAt = publishedAt, uploader = uploader, uploaderName = uploaderName,
    )

    @Test
    fun `each uploader standing has its own words`() {
        assertEquals(
            "💚 VIP uploader (someone)",
            TorrentTrust.of(result(uploader = Uploader.VIP, uploaderName = "someone"), now).uploader
        )
        assertEquals("💗 Trusted uploader", TorrentTrust.of(result(uploader = Uploader.TRUSTED), now).uploader)
        assertEquals("👤 Ordinary uploader", TorrentTrust.of(result(uploader = Uploader.MEMBER), now).uploader)
        assertEquals("❔ Uploader unknown", TorrentTrust.of(result(indexer = "1337x"), now).uploader)
    }

    @Test
    fun `YTS is an official uploader, through Prowlarr or Torrentio`() {
        assertEquals("💚 Official YTS release", TorrentTrust.of(result(indexer = "YTS"), now).uploader)
        assertEquals("💚 Official YTS release", TorrentTrust.of(result(indexer = "Torrentio · YTS"), now).uploader)
        assertEquals(Uploader.UNKNOWN, TorrentTrust.standing(result(indexer = "Torrentio · 1337x")))
    }

    @Test
    fun `a cinema recording is flagged, a word merely containing TS is not`() {
        val cam = TorrentTrust.of(result(title = "Some Film 2026 HDCAM x264"), now)
        assertTrue(cam.flagged)
        assertEquals(listOf("⚠️ Looks like a cinema recording (HDCAM)"), cam.flags)
        assertFalse(TorrentTrust.of(result(title = "Some Film 2026 1080p WEB DTS-HD x264"), now).flagged)
        assertFalse(TorrentTrust.of(result(title = "The Hearts of Lights 2026 1080p"), now).flagged)
    }

    @Test
    fun `a size far too small for its quality is flagged as fake`() {
        val tiny = TorrentTrust.of(result(title = "Some Film 2026 1080p BluRay", sizeBytes = 80_000_000), now)
        assertEquals(listOf("⚠️ Looks fake: 80 MB for 1080p"), tiny.flags)
        assertFalse(TorrentTrust.of(result(title = "Some Film 2026 720p", sizeBytes = 700_000_000), now).flagged)
        assertFalse(TorrentTrust.of(result(title = "Some Film 2026 1080p", sizeBytes = 0), now).flagged)
    }

    @Test
    fun `a program or a password in the name is flagged`() {
        assertTrue(TorrentTrust.of(result(title = "Some Film 2026 1080p setup.exe"), now).flagged)
        assertTrue(TorrentTrust.of(result(title = "Some Film 2026 1080p password in txt"), now).flagged)
    }

    @Test
    fun `a known release group is named, an unknown one is not`() {
        assertEquals("🏷️ AMIABLE release", TorrentTrust.of(result(), now).group)
        assertEquals("YIFY", TorrentTrust.groupOf("Night of the Living Dead (1968) [1080p] [YIFY]"))
        assertNull(TorrentTrust.groupOf("Night of the Living Dead 1968 1080p BluRay x264-NOBODY"))
    }

    @Test
    fun `health says how well seeded, how many are leeching and how old`() {
        val old = TorrentTrust.of(result(leechers = 15, publishedAt = now.minus(Duration.ofDays(800))), now)
        assertEquals("🌱 Well seeded (104 seeding, 15 leeching) · 2 year(s) old", old.health)
        assertEquals("🌿 Some seeders (6 seeding)", TorrentTrust.of(result(seeders = 6), now).health)
        assertEquals("🥀 Few seeders (2 seeding)", TorrentTrust.of(result(seeders = 2), now).health)
        assertEquals("🥀 No seeders (0 seeding)", TorrentTrust.of(result(seeders = 0), now).health)
    }

    @Test
    fun `warnings come first among the chips`() {
        val chips = TorrentTrust.of(result(title = "Some Film 2026 CAM-AMIABLE", uploader = Uploader.VIP), now).chips
        assertTrue(chips.first(), chips.first().startsWith("⚠️"))
        assertTrue(
            chips.any { it.startsWith("💚") } && chips.any { it.startsWith("🏷️") } && chips.any { it.startsWith("🌱") }
        )
    }

    @Test
    fun `a program among a torrent's files is named, plain video and subtitles are not`() {
        val files = listOf(
            TorrentFile(0, "Film/Film.mkv", 1_000),
            TorrentFile(1, "Film/Film.srt", 10),
            TorrentFile(2, "Film/Codec Setup.EXE", 5),
        )
        assertEquals(
            "⚠️ It also holds 1 program(s) (Codec Setup.EXE); only its video is played",
            TorrentTrust.filesWarning(files),
        )
        assertNull(TorrentTrust.filesWarning(files.take(2)))
    }

    @Test
    fun `the quality is read from the name`() {
        assertEquals("🎞️ 1080p · BluRay · H.264", TorrentTrust.of(result(), now).quality)
        assertEquals(
            "4K · WEB · HEVC · Dolby Vision · Atmos · 5.1",
            TorrentTrust.qualityOf("Some Film 2026 2160p WEB-DL DDP5.1 Atmos DV HEVC-GROUP"),
        )
        assertNull(TorrentTrust.qualityOf("Some Film 2026"))
    }
}
