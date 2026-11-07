package com.dewijones92.totum.ui.common

import com.dewijones92.totum.common.HttpUrl
import com.dewijones92.totum.data.jellyfin.JellyfinItem
import com.dewijones92.totum.data.jellyfin.JellyfinKind
import com.dewijones92.totum.data.jellyfin.toPlayable
import com.dewijones92.totum.domain.FollowedSources
import com.dewijones92.totum.domain.MediaContentKind
import com.dewijones92.totum.domain.MediaItem
import com.dewijones92.totum.domain.MediaKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class JellyfinItemsAreShownLikeVideosTest {

    private val film: MediaItem = JellyfinItem(
        id = "00112233445566778899aabbccddeeff",
        name = "Heat",
        kind = JellyfinKind.MOVIE,
        year = 1995,
        overview = "A crew of thieves.",
        container = "mkv",
    ).toPlayable(HttpUrl.of("https://pi.example/jellyfin/stream/00112233445566778899aabbccddeeff.mkv")).item

    private val episode: MediaItem = JellyfinItem(
        id = "fedcba9876543210fedcba9876543210",
        name = "Mr Wolf",
        kind = JellyfinKind.EPISODE,
        year = 2020,
        seriesName = "Bluey",
        seasonNumber = 1,
        episodeNumber = 4,
        container = "mkv",
    ).toPlayable(HttpUrl.of("https://pi.example/jellyfin/stream/fedcba9876543210fedcba9876543210.mkv")).item

    @Test
    fun `a film or an episode is shown as a video wherever it is described, though it plays as a podcast`() {
        assertEquals(Shown.JELLYFIN, shownAs(film, MediaKind.PODCAST))
        assertEquals(Shown.JELLYFIN, shownAs(episode, MediaKind.PODCAST))
        assertEquals(Shown.JELLYFIN, shownAs(film.id, MediaKind.PODCAST, MediaContentKind.STANDARD))
        assertEquals(Shown.VIDEO.icon, Shown.JELLYFIN.icon)
        assertEquals(Shown.VIDEO.maker, Shown.JELLYFIN.maker)
    }

    @Test
    fun `a film or an episode has no follow mark, it came from no channel or show`() {
        val rows = RowFollowing(FollowedSources(emptyList(), emptyList(), signedIn = true), null)

        assertNull(rows.markFor(film, MediaKind.PODCAST))
        assertNull(rows.markFor(episode, MediaKind.PODCAST))
    }

    @Test
    fun `a film's year is its date fact, like any other item's date`() {
        val facts = mediaItemFacts(film, MediaKind.PODCAST)

        assertTrue(facts.toString(), facts.any { it.contains("1995") })
    }

    @Test
    fun `an episode's show is its maker line, drawn as a channel line`() {
        val maker = mediaItemFacts(episode, MediaKind.PODCAST).first()

        assertTrue(maker, maker.startsWith(FactEmoji.CHANNEL))
    }

    @Test
    fun `the overview is the description the Details tab reads`() {
        assertEquals("A crew of thieves.", film.description)
    }
}
