package com.dewijones92.totum.ui.common

import com.dewijones92.totum.common.HttpUrl
import com.dewijones92.totum.domain.IPlayer
import com.dewijones92.totum.domain.MediaContentKind
import com.dewijones92.totum.domain.MediaItem
import com.dewijones92.totum.domain.MediaItemId
import com.dewijones92.totum.domain.MediaKind
import com.dewijones92.totum.domain.SourceId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.ZoneOffset

class IPlayerEpisodesAreShownTest {

    private val now = Instant.parse("2026-11-20T12:00:00Z")

    private fun episode(until: Instant?, mediaUrl: HttpUrl? = IPlayer.episodeUrl("m002n2bm")) = MediaItem(
        id = MediaItemId("bbc:m002n2bm"),
        sourceId = SourceId("https://ibl.api.bbci.co.uk/ibl/v1/programmes/b006mkw3/episodes"),
        title = "Series 70: Episode 9",
        publishedAt = Instant.parse("2025-12-05T00:00:00Z"),
        duration = null,
        author = "Have I Got News for You",
        publisher = "BBC One",
        mediaUrl = mediaUrl,
        availableUntil = until,
    )

    @Test
    fun `an episode with an end date says when it leaves iPlayer`() {
        val line = iPlayerAvailability(episode(Instant.parse("2027-12-22T00:45:00Z")), now, ZoneOffset.UTC)

        assertEquals("⏳ On iPlayer until 22 Dec 2027", line)
    }

    @Test
    fun `an episode leaving within two weeks counts the days left, and within a day says so`() {
        assertEquals(
            "⏳ On iPlayer until 4 Dec 2026 · ⏰ 14 days left",
            iPlayerAvailability(episode(Instant.parse("2026-12-04T21:00:00Z")), now, ZoneOffset.UTC),
        )
        assertEquals(
            "⏳ On iPlayer until 20 Nov 2026 · ⏰ less than a day left",
            iPlayerAvailability(episode(Instant.parse("2026-11-20T21:00:00Z")), now, ZoneOffset.UTC),
        )
    }

    @Test
    fun `an episode already gone says so rather than pretending it is there`() {
        assertEquals(
            "⌛ No longer on iPlayer (it ended on 4 Dec 2025)",
            iPlayerAvailability(episode(Instant.parse("2025-12-04T21:00:00Z")), now, ZoneOffset.UTC),
        )
    }

    @Test
    fun `nothing is said for an episode with no end date or for anything that is not on iPlayer`() {
        assertNull(iPlayerAvailability(episode(until = null), now, ZoneOffset.UTC))
        assertNull(
            iPlayerAvailability(
                episode(Instant.parse("2027-12-22T00:45:00Z"), HttpUrl.of("https://cdn.example.com/a.mp3")),
                now,
                ZoneOffset.UTC,
            ),
        )
    }

    @Test
    fun `the row's facts end with the availability and the maker wears the BBC glyph`() {
        val facts = mediaItemFacts(episode(Instant.parse("2027-12-22T00:45:00Z")), MediaKind.PODCAST, now)

        assertTrue(facts.toString(), facts.first().startsWith("${FactEmoji.BBC} Have I Got News for You"))
        assertTrue(facts.toString(), facts.last().startsWith("⏳ On iPlayer until"))
    }

    @Test
    fun `an iPlayer episode is shown as BBC iPlayer wherever it is described`() {
        val item = episode(null)

        assertEquals(Shown.BBC, shownAs(item, MediaKind.PODCAST))
        assertEquals(Shown.BBC, shownAs(item.id, MediaKind.PODCAST, MediaContentKind.STANDARD))
        assertEquals(Shown.PODCAST, shownAs(item.copy(id = MediaItemId("ep1"), mediaUrl = null), MediaKind.PODCAST))
    }

    @Test
    fun `the details sheet lists the availability beside the date`() {
        val lines = itemDetails(episode(Instant.parse("2027-12-22T00:45:00Z")), ZoneOffset.UTC, now)

        assertTrue(lines.toString(), lines.any { it.startsWith("⏳ On iPlayer until") })
    }
}
