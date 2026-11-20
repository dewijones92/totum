package com.dewijones92.totum.domain

import com.dewijones92.totum.common.HttpUrl
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class IPlayerEpisodesTest {

    private val link = IPlayer.episodeUrl("m0033v6x")

    private fun episode(mediaUrl: HttpUrl? = link, until: Instant? = null) = MediaItem(
        id = MediaItemId("bbc:m0033v6x"),
        sourceId = SourceId("https://ibl.api.bbci.co.uk/ibl/v1/programmes/b006mkw3/episodes"),
        title = "Series 72: Episode 6",
        publishedAt = Instant.parse("2026-11-06T21:25:00Z"),
        duration = null,
        mediaUrl = mediaUrl,
        availableUntil = until,
    )

    @Test
    fun `an iPlayer episode link is recognised and its programme id read from it`() {
        assertTrue(IPlayer.isEpisode(link))
        assertEquals("m0033v6x", IPlayer.pidOf(link))
        assertEquals(
            "m0033v6x",
            IPlayer.pidOf(HttpUrl.of("https://www.bbc.co.uk/iplayer/episode/m0033v6x/have-i-got-news?x=1")),
        )
        assertFalse(IPlayer.isEpisode(HttpUrl.of("https://www.youtube.com/watch?v=aaaaaaaaaaa")))
        assertNull(IPlayer.pidOf(HttpUrl.of("https://example.com/feed.mp3")))
    }

    @Test
    fun `an item with an iPlayer link opens externally and an ordinary one does not`() {
        assertTrue(episode().opensExternally)
        assertEquals(link, episode().iPlayerUrl)
        assertFalse(episode(HttpUrl.of("https://cdn.example.com/ep.mp3")).opensExternally)
        assertFalse(episode(mediaUrl = null).opensExternally)
    }

    @Test
    fun `an iPlayer item is playable only as an external handle`() {
        val playable = checkNotNull(episode().toPlayableOrNull())

        assertEquals(PlayHandle.External(link), playable.handle)
        assertEquals("External", playable.handle.label)
        assertEquals(MediaKind.PODCAST, playable.pillar)
    }

    @Test
    fun `an external handle never offers anything to download`() {
        assertNull(checkNotNull(episode().toPlayableOrNull()).fetchUrl)
    }

    @Test
    fun `every way of asking how to play an external item says open it elsewhere`() {
        val playable = checkNotNull(episode().toPlayableOrNull())
        val copy = LocalCopy("/data/user/0/totum/files/x.mp4")
        val home = HomeCopy(HttpUrl.of("https://home.test/fetch/files/aaaaaaaaaaa"), audioOnly = false)

        val routes = listOf(
            playable.routeNow(onDisk = null, offline = false, audioPreferred = false),
            playable.routeNow(onDisk = null, offline = true, audioPreferred = false),
            playable.routeNow(onDisk = copy, offline = false, audioPreferred = true),
            playable.routeNow(onDisk = null, offline = false, audioPreferred = false, streamRefused = true),
            playable.routeNow(onDisk = null, offline = false, audioPreferred = false, atHome = home),
        )

        assertEquals(List(routes.size) { PlayRoute.External(playable, link) }, routes)
    }

    @Test
    fun `an external handle survives being written down and read back`() {
        val (type, handle) = PlayHandle.External(link).persisted()

        assertEquals("EXTERNAL", type)
        assertEquals(link.value, handle)
        assertEquals(PlayHandle.External(link), playHandleFrom(type, handle))
        assertNull(playHandleFrom("EXTERNAL", null))
        assertNull(playHandleFrom("EXTERNAL", "not a url"))
    }

    @Test
    fun `an external item is never ready offline`() {
        val playable = checkNotNull(episode().toPlayableOrNull())

        assertFalse(playable.routeNow(onDisk = null, offline = true, audioPreferred = false) is PlayRoute.File)
    }

    @Test
    fun `an episode is gone once its end date has passed and not before`() {
        val ends = Instant.parse("2026-12-04T21:00:00Z")

        assertFalse(episode(until = ends).isGoneFromIPlayer(Instant.parse("2026-12-04T20:59:59Z")))
        assertTrue(episode(until = ends).isGoneFromIPlayer(Instant.parse("2026-12-04T21:00:01Z")))
        assertFalse(episode(until = null).isGoneFromIPlayer(Instant.parse("2030-01-01T00:00:00Z")))
        assertFalse(
            episode(HttpUrl.of("https://cdn.example.com/ep.mp3"), until = ends)
                .isGoneFromIPlayer(Instant.parse("2030-01-01T00:00:00Z")),
        )
    }

    @Test
    fun `an iPlayer item shows a picture, so nothing mistakes it for sound only`() {
        assertEquals(Picture.YES, episode().picture)
        assertEquals(Picture.YES, checkNotNull(episode().toPlayableOrNull()).picture)
    }
}
