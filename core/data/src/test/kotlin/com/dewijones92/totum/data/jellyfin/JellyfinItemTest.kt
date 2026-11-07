package com.dewijones92.totum.data.jellyfin

import com.dewijones92.totum.common.HttpUrl
import com.dewijones92.totum.data.targets.TargetsJson
import com.dewijones92.totum.domain.Picture
import com.dewijones92.totum.domain.PlayHandle
import com.dewijones92.totum.domain.picture
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class JellyfinItemTest {

    private val episode = JellyfinItem(
        id = "0123456789abcdef0123456789abcdef",
        name = "Mr Wolf",
        kind = JellyfinKind.EPISODE,
        year = 2020,
        runtimeMs = 7 * 60_000L,
        seriesName = "Bluey",
        seasonNumber = 1,
        episodeNumber = 4,
        container = "mkv,webm",
    )

    @Test
    fun `an episode is titled with its season and number, a film by its name`() {
        assertEquals("S01E04 · Mr Wolf", episode.title)
        assertEquals(
            "Heat",
            episode.copy(kind = JellyfinKind.MOVIE, episodeNumber = null, seasonNumber = null, name = "Heat").title
        )
        assertEquals(listOf("2020", "7 min", "Bluey"), episode.facts)
    }

    @Test
    fun `the stream address keeps the file's own extension, so the app knows it has a picture`() {
        assertEquals("jellyfin/stream/0123456789abcdef0123456789abcdef.mkv", episode.streamPath())
        assertEquals(
            "jellyfin/stream/0123456789abcdef0123456789abcdef.mkv",
            episode.copy(container = null).streamPath()
        )

        val playable = episode.toPlayable(HttpUrl.of("https://totum.example/fetch/${episode.streamPath()}"))

        assertEquals(Picture.YES, playable.picture)
        assertEquals("jellyfin:0123456789abcdef0123456789abcdef", playable.item.id.value)
        assertEquals("Bluey", playable.item.author)
        assertEquals(7 * 60_000L, playable.item.duration?.inWholeMilliseconds)
        assertTrue(playable.handle is PlayHandle.Podcast)
    }

    @Test
    fun `a zero runtime does not make an invalid item`() {
        val playable = episode.copy(runtimeMs = 0).toPlayable(HttpUrl.of("https://totum.example/x.mkv"))

        assertEquals(null, playable.item.duration)
    }

    @Test
    fun `a listing survives the wire`() {
        val listing = JellyfinListing(null, "🎞️ Films and series", listOf(episode))

        val back = TargetsJson.decodeFromString(
            JellyfinListing.serializer(),
            TargetsJson.encodeToString(JellyfinListing.serializer(), listing),
        )

        assertEquals(listing, back)
        assertTrue(JellyfinKind.EPISODE.playable && !JellyfinKind.SEASON.playable)
    }
}
