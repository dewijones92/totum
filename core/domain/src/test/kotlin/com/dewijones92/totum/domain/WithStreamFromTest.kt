package com.dewijones92.totum.domain

import com.dewijones92.totum.common.HttpUrl
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.Instant
import kotlin.time.Duration.Companion.seconds

/**
 * What a resolution is allowed to change about an item, and what it is not.
 *
 * Dewi, 2026-08-06: *"videoviews, datestuff, datepublished ... this additional detail must appear
 * within video page also"*. It could not, and no amount of UI work would have fixed it: resolving a
 * video builds a **fresh** [MediaItem] from what the extractor says, in three separate places, and
 * the extractor says nothing about view counts and nothing about publication dates —
 * `publishedAt = null` in all three. So a row reading "1.2M views · 2 days ago" reached the player
 * with both facts gone.
 *
 * The rule is one function because otherwise it is three call sites, and the fourth resolver path
 * would forget one of them.
 */
class WithStreamFromTest {

    private val listing = MediaItem(
        id = MediaItemId("abc123"),
        sourceId = SourceId("ytfeed:SUBSCRIPTIONS"),
        title = "What Happened In Ceuta",
        publishedAt = Instant.parse("2026-08-01T09:00:00Z"),
        publishedText = "5 days ago",
        duration = 600.seconds,
        author = "Novara Media",
        thumbnailUrl = HttpUrl.of("https://i.ytimg.com/vi/abc123/hq.jpg"),
        viewsText = "1.2M views",
        membersOnly = true,
        contentKind = MediaContentKind.STANDARD,
        sourceUrl = HttpUrl.of("https://www.youtube.com/channel/UCnovara"),
    )

    /** What a resolver actually produces: a stream, a description, and nulls for the rest. */
    private val stream = MediaItem(
        id = MediaItemId("abc123"),
        sourceId = SourceId("ytfeed:SUBSCRIPTIONS"),
        title = "What Happened In Ceuta & How Do We Fix It",
        publishedAt = null,
        duration = 601.seconds,
        author = "Novara Media ",
        description = "Aaron Bastani speaks to…",
        mediaUrl = HttpUrl.of("https://rr1---sn-x.googlevideo.com/videoplayback?itag=399"),
        chapters = listOf(Chapter(0.seconds, "Intro")),
    )

    // ---- the facts a resolution has nothing to say about ---------------------------------------

    @Test
    fun `the view count survives resolution`() {
        assertEquals("1.2M views", listing.withStreamFrom(stream).viewsText)
    }

    @Test
    fun `both forms of the publication date survive resolution`() {
        val played = listing.withStreamFrom(stream)
        assertEquals("5 days ago", played.publishedText)
        assertEquals(Instant.parse("2026-08-01T09:00:00Z"), played.publishedAt)
    }

    /**
     * The one that had a user-visible consequence of its own: a members-only badge disappearing at
     * play time is how three items sat in a real download queue with no explanation.
     */
    @Test
    fun `the members-only flag survives resolution`() {
        assertEquals(true, listing.withStreamFrom(stream).membersOnly)
    }

    @Test
    fun `a resolution that knows nothing cannot erase what the listing knew`() {
        val silent = MediaItem(
            id = MediaItemId("abc123"),
            sourceId = SourceId("s"),
            title = "abc123",
            publishedAt = null,
            duration = null,
        )
        val played = listing.withStreamFrom(silent)

        assertEquals("1.2M views", played.viewsText)
        assertEquals("5 days ago", played.publishedText)
        assertEquals(600.seconds, played.duration)
        assertEquals("Novara Media", played.author)
        assertEquals(listing.thumbnailUrl, played.thumbnailUrl)
        assertEquals(listing.sourceUrl, played.sourceUrl)
    }

    @Test
    fun `a resolution fills a publication date the listing never had`() {
        // A shared link is queued knowing nothing but its id; yt-dlp knows when it was uploaded.
        // Reported 2026-09-06: queue rows with no date at all.
        val bare = listing.copy(publishedAt = null, publishedText = null)
        val dated = stream.copy(publishedAt = Instant.parse("2025-03-04T00:00:00Z"))
        assertEquals(Instant.parse("2025-03-04T00:00:00Z"), bare.withStreamFrom(dated).publishedAt)
    }

    @Test
    fun `a resolution never overrides a publication date the listing had`() {
        val dated = stream.copy(publishedAt = Instant.parse("2025-03-04T00:00:00Z"))
        assertEquals(Instant.parse("2026-08-01T09:00:00Z"), listing.withStreamFrom(dated).publishedAt)
    }

    // ---- the facts it is the authority on -------------------------------------------------------

    @Test
    fun `the stream url comes from the resolution`() {
        assertEquals(stream.mediaUrl, listing.withStreamFrom(stream).mediaUrl)
    }

    @Test
    fun `the description and chapters come from the resolution`() {
        val played = listing.withStreamFrom(stream)
        assertEquals("Aaron Bastani speaks to…", played.description)
        assertEquals(listOf(Chapter(0.seconds, "Intro")), played.chapters)
    }

    /** The extractor measured the file; a feed tile's duration is rounded display text. */
    @Test
    fun `the duration comes from the resolution when it has one`() {
        assertEquals(601.seconds, listing.withStreamFrom(stream).duration)
    }

    /**
     * The resolved title wins, which is what shipped and what stays.
     *
     * An earlier version of this rule preferred the LISTING's title on the reasoning that it is what
     * the person read before tapping. That is arguable, but it changed shipped behaviour nobody had
     * asked to change, and `SearchViewModelTest` failed on it immediately. The rule is now the
     * smallest one that fixes the actual loss: the resolution wins wherever it says anything, and the
     * listing fills the silence.
     */
    @Test
    fun `the resolved title wins, as it always did`() {
        assertEquals("What Happened In Ceuta & How Do We Fix It", listing.withStreamFrom(stream).title)
    }

    @Test
    fun `a blank resolved title falls back to the listing`() {
        assertEquals("What Happened In Ceuta", listing.withStreamFrom(stream.copy(title = " ")).title)
    }

    @Test
    fun `the resolved author wins, as it always did`() {
        assertEquals("Novara Media ", listing.withStreamFrom(stream).author)
    }

    @Test
    fun `identity is never taken from the resolution`() {
        val other = stream.copy(id = MediaItemId("different"), sourceId = SourceId("elsewhere"))
        val played = listing.withStreamFrom(other)

        assertEquals(MediaItemId("abc123"), played.id)
        assertEquals(SourceId("ytfeed:SUBSCRIPTIONS"), played.sourceId)
    }

    /** A listing with nothing to contribute must not invent it either. */
    @Test
    fun `a bare listing gains only what the resolution actually has`() {
        val bare = MediaItem(
            id = MediaItemId("abc123"),
            sourceId = SourceId("share"),
            title = "abc123",
            publishedAt = null,
            duration = null,
        )
        val played = bare.withStreamFrom(stream)

        assertNull("nothing said the view count, so nothing must claim one", played.viewsText)
        assertNull(played.publishedText)
        assertEquals(stream.mediaUrl, played.mediaUrl)
        assertEquals("Novara Media ", played.author)
    }
}
