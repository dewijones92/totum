package com.dewijones92.totum.ui.common

import com.dewijones92.totum.common.HttpUrl
import com.dewijones92.totum.domain.MediaItem
import com.dewijones92.totum.domain.MediaItemId
import com.dewijones92.totum.domain.MediaKind
import com.dewijones92.totum.domain.SourceId
import com.dewijones92.totum.domain.formatViewCount
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Duration
import java.time.Instant

/**
 * The facts under every video title, everywhere — channel, views, date, **one per line**.
 *
 * Dewi, 2026-08-06: *"i want things like videoviews, datestuff, datepublished always visible
 * whenever videos are listed"*. They were listed, and then they were not visible: all three shared
 * one line capped at `maxLines = 1`, so a real phone replaced the tail with an ellipsis. Dewi again,
 * 2026-08-15: *"the view count and the date published sometimes gets hidden, there's like a 3-dot
 * thing. I want them each to be on a separate line."* So this returns the parts, in order, and
 * whoever renders puts one on each line.
 *
 * Every list routes through here and so does the video page, which is what stops the page drifting
 * from the row that led to it.
 *
 * Testable on the JVM only because `@Composable` came off both functions: neither ever called
 * anything composable, and the annotation was the only thing holding the app's most-seen piece of
 * formatting behind an instrumented test.
 */
class MediaItemSubtitleTest {

    private fun video(
        author: String? = "Novara Media",
        viewsText: String? = "1.2M views",
        publishedText: String? = "2 days ago",
        publishedAt: Instant? = null,
    ) = MediaItem(
        id = MediaItemId("abc"),
        sourceId = SourceId("s"),
        title = "a video",
        publishedAt = publishedAt,
        publishedText = publishedText,
        duration = null,
        author = author,
        viewsText = viewsText,
    )

    private fun podcastEpisode() = MediaItem(
        id = MediaItemId("ep"),
        sourceId = SourceId("feed"),
        title = "an episode",
        publishedAt = null,
        publishedText = "2 days ago",
        duration = null,
        author = "Novara Media",
        mediaUrl = HttpUrl.of("https://example.test/ep.mp3"),
    )

    @Test
    fun `a video gives channel then views then date, each its own line`() {
        assertEquals(
            listOf("📺 Novara Media", "👁️ 1.2M views", "📅 2 days ago"),
            mediaItemFacts(video(), MediaKind.VIDEO)
        )
    }

    /**
     * Separate entries, not one string with separators — the whole point. Joining them is what put
     * three facts on a line that could only show one and a bit of the next.
     */
    @Test
    fun `the facts are separate entries, not one line with separators`() {
        val facts = mediaItemFacts(video(), MediaKind.VIDEO)

        assertEquals(3, facts.size)
        assertTrue("no entry may carry a separator: $facts", facts.none { it.contains(" · ") })
    }

    /**
     * Duration is deliberately absent — it rides on the thumbnail corner, where nothing truncates it
     * and it costs no vertical space. Dewi's call when choosing this layout, 2026-08-15.
     */
    @Test
    fun `duration is not one of the lines`() {
        assertTrue(mediaItemFacts(video(), MediaKind.VIDEO).none { it.contains(":") })
    }

    // ---- the emoji labels ------------------------------------------------------------------------

    /**
     * Dewi, 2026-08-15: *"can we put in emojis? Views has an eyes emoji prefix to it … I love
     * emojis."* They earn their place beyond decoration: three stacked grey lines of similar length
     * are hard to tell apart at a glance, and a leading glyph says which fact a line is before you
     * read it.
     */
    @Test
    fun `each fact is labelled with its own emoji`() {
        val facts = mediaItemFacts(video(), MediaKind.VIDEO)

        assertTrue("views should be eyes: $facts", facts.any { it.startsWith("${FactEmoji.VIEWS} ") })
        assertTrue("the date should be a calendar: $facts", facts.any { it.startsWith("${FactEmoji.PUBLISHED} ") })
    }

    /** No two facts share a glyph, or the labelling tells you nothing. */
    @Test
    fun `no two facts wear the same emoji`() {
        val emojis = mediaItemFacts(video(), MediaKind.VIDEO).map { it.substringBefore(' ') }

        assertEquals(emojis.toString(), emojis.size, emojis.toSet().size)
    }

    /**
     * The maker's badge follows the PILLAR, read from the one place that answers "is this a video".
     * A mixed list — search, library, queue — then says which of the two things a row is without a
     * second rule for it.
     */
    @Test
    fun `a podcast episodes maker gets a microphone and a videos gets a screen`() {
        assertTrue(mediaItemFacts(video(), MediaKind.VIDEO).first().startsWith(FactEmoji.CHANNEL))
        assertTrue(mediaItemFacts(podcastEpisode(), MediaKind.PODCAST).first().startsWith(FactEmoji.PODCAST))
    }

    /** An emoji must never appear on its own — a label with nothing to label is noise. */
    @Test
    fun `a missing fact does not leave a bare emoji`() {
        val facts = mediaItemFacts(video(author = null, viewsText = null, publishedText = null), MediaKind.VIDEO)

        assertEquals(emptyList<String>(), facts)
    }

    /** A podcast episode has no view count and must not leave a blank line where one would be. */
    @Test
    fun `a missing view count leaves no empty line`() {
        assertEquals(
            listOf("📺 Novara Media", "📅 2 days ago"),
            mediaItemFacts(video(viewsText = null), MediaKind.VIDEO)
        )
    }

    @Test
    fun `a video with nothing to say has no lines at all`() {
        assertEquals(
            emptyList<String>(),
            mediaItemFacts(video(author = null, viewsText = null, publishedText = null), MediaKind.VIDEO),
        )
    }

    /** A source that says "" rather than null must not become a blank line either. */
    @Test
    fun `a blank fact is dropped rather than rendered as an empty line`() {
        assertEquals(
            listOf("📺 Novara Media", "📅 2 days ago"),
            mediaItemFacts(video(viewsText = "   "), MediaKind.VIDEO)
        )
    }

    // ---- the date rule ---------------------------------------------------------------------------

    /**
     * The instant wins and is rendered against the clock, so the label AGES. This reverses the
     * earlier "the source's wording wins" rule on Dewi's instruction (2026-09-06): a row's
     * "2 hours ago" must become "3 hours ago" an hour later, which frozen wording cannot do.
     */
    @Test
    fun `an instant is rendered as an age that grows with the clock`() {
        val now = Instant.parse("2026-09-06T16:00:00Z")
        val at = now.minus(Duration.ofHours(2))
        assertEquals("2 hours ago", mediaDateText("2 hours ago", at, now))
        assertEquals("3 hours ago", mediaDateText("2 hours ago", at, now.plus(Duration.ofHours(1))))
    }

    /** Wording with no instant — a row persisted before anchoring existed — is shown as it is. */
    @Test
    fun `wording alone is shown verbatim when there is no instant to age`() {
        assertEquals("2 days ago", mediaDateText("2 days ago", null))
    }

    /** Podcasts give an instant and no wording; they read like videos now, not as a calendar date. */
    @Test
    fun `an instant with no wording reads as a relative age on both pillars`() {
        val now = Instant.parse("2026-09-06T16:00:00Z")
        assertEquals("5 days ago", mediaDateText(null, now.minus(Duration.ofDays(5)), now))
    }

    @Test
    fun `no date at all is null rather than a placeholder`() {
        assertNull(mediaDateText(null, null))
    }

    // ---- the two sources of a view count read identically ---------------------------------------

    /**
     * A yt-dlp row gives a NUMBER and an InnerTube row gives TEXT, and both end up in the same list.
     * [formatViewCount] exists so they cannot look like different apps; this pins that they don't.
     */
    @Test
    fun `a counted and a quoted view figure render the same way`() {
        assertEquals(
            mediaItemFacts(video(viewsText = "1.2M views"), MediaKind.VIDEO),
            mediaItemFacts(video(viewsText = formatViewCount(1_234_567)), MediaKind.VIDEO),
        )
    }

    /** Truncating, never rounding up, so "1M views" is never a lie about a 1,999,999 video. */
    @Test
    fun `a view count never rounds up`() {
        assertEquals("1.9M views", formatViewCount(1_999_999))
        assertEquals("999 views", formatViewCount(999))
        assertEquals("1K views", formatViewCount(1_000))
    }

    // ---- the video page uses the same formatter --------------------------------------------------

    /**
     * The player omits the author because the artist line sits directly above it, and this is the
     * shape it asks for. Pinned here so the page and the row cannot drift apart: they are the same
     * call.
     */
    @Test
    fun `the video page gets the same facts, on their own lines, without the repeated author`() {
        assertEquals(
            listOf("👁️ 1.2M views", "📅 2 days ago"),
            mediaFacts(author = null, dateText = mediaDateText("2 days ago", null), viewsText = "1.2M views"),
        )
    }

    @Test
    fun `the video page shows nothing when the source said nothing`() {
        assertEquals(
            emptyList<String>(),
            mediaFacts(author = null, dateText = mediaDateText(null, null), viewsText = null),
        )
    }
}
