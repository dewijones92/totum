package com.dewijones92.totum.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test
import java.time.Duration
import java.time.Instant

class PublishedAgeTest {

    private val now = Instant.parse("2026-09-06T16:00:00Z")

    @Test
    fun `reads YouTube's long wording`() {
        assertEquals(now.minus(Duration.ofHours(2)), PublishedAge.parse("2 hours ago", now))
        assertEquals(now.minus(Duration.ofDays(3)), PublishedAge.parse("Streamed 3 days ago", now))
        assertEquals(now.minus(Duration.ofDays(14)), PublishedAge.parse("Premiered 2 weeks ago", now))
        assertEquals(now.minus(Duration.ofDays(365)), PublishedAge.parse("1 year ago", now))
        assertEquals(now.minus(Duration.ofDays(150)), PublishedAge.parse("5 months ago", now))
    }

    @Test
    fun `reads the abbreviated wording the TV surfaces use`() {
        // Seen verbatim in a queue row on the emulator, 2026-09-06.
        assertEquals(now.minus(Duration.ofDays(11 * 365)), PublishedAge.parse("11y ago", now))
        assertEquals(now.minus(Duration.ofDays(3)), PublishedAge.parse("3d ago", now))
        assertEquals(now.minus(Duration.ofMinutes(45)), PublishedAge.parse("45m ago", now))
        assertEquals(now.minus(Duration.ofDays(150)), PublishedAge.parse("5mo ago", now))
    }

    @Test
    fun `wording it does not know is null, never a guess`() {
        assertNull(PublishedAge.parse("Scheduled for 9 Sep 2026", now))
        assertNull(PublishedAge.parse("LIVE", now))
        assertNull(PublishedAge.parse("", now))
    }

    @Test
    fun `writes the age in YouTube's words and units`() {
        assertEquals("just now", PublishedAge.text(now.minusSeconds(30), now))
        assertEquals("1 minute ago", PublishedAge.text(now.minus(Duration.ofMinutes(1)), now))
        assertEquals("2 hours ago", PublishedAge.text(now.minus(Duration.ofHours(2)), now))
        assertEquals("6 days ago", PublishedAge.text(now.minus(Duration.ofDays(6)), now))
        assertEquals("2 weeks ago", PublishedAge.text(now.minus(Duration.ofDays(14)), now))
        assertEquals("5 months ago", PublishedAge.text(now.minus(Duration.ofDays(150)), now))
        assertEquals("11 years ago", PublishedAge.text(now.minus(Duration.ofDays(11 * 365 + 100)), now))
    }

    @Test
    fun `the label grows as time passes — the whole point`() {
        val at = PublishedAge.parse("2 hours ago", now)!!
        assertEquals("2 hours ago", PublishedAge.text(at, now))
        assertEquals("3 hours ago", PublishedAge.text(at, now.plus(Duration.ofHours(1))))
        assertEquals("1 day ago", PublishedAge.text(at, now.plus(Duration.ofHours(23))))
    }

    @Test
    fun `a future instant reads as just now rather than a negative age`() {
        assertEquals("just now", PublishedAge.text(now.plusSeconds(90), now))
    }

    private val bare = MediaItem(
        id = MediaItemId("v1"),
        sourceId = SourceId("s"),
        title = placeholderTitleFor(MediaItemId("v1")),
        publishedAt = null,
        duration = null,
    )

    @Test
    fun `anchoring turns wording into an instant and leaves an existing instant alone`() {
        val worded = bare.copy(publishedText = "2 hours ago")
        assertEquals(now.minus(Duration.ofHours(2)), worded.anchoringPublishedAt(now).publishedAt)
        val dated = worded.copy(publishedAt = now.minus(Duration.ofDays(1)))
        assertEquals(now.minus(Duration.ofDays(1)), dated.anchoringPublishedAt(now).publishedAt)
        assertNull(bare.anchoringPublishedAt(now).publishedAt)
    }

    @Test
    fun `a queue row fills only the facts it lacks from what resolved`() {
        val resolved = bare.copy(
            title = "Big Buck Bunny",
            publishedAt = now.minus(Duration.ofDays(400)),
            author = "Blender",
            duration = kotlin.time.Duration.parse("10m"),
        )
        val learned = bare.fillingSilenceFrom(resolved)
        assertEquals("Big Buck Bunny", learned.title)
        assertEquals(resolved.publishedAt, learned.publishedAt)
        assertEquals("Blender", learned.author)
        assertEquals(resolved.duration, learned.duration)

        val knowing = bare.copy(title = "My own title", author = "Me")
        val kept = knowing.fillingSilenceFrom(resolved)
        assertEquals("My own title", kept.title)
        assertEquals("Me", kept.author)
        assertEquals(resolved.publishedAt, kept.publishedAt)
    }

    @Test
    fun `nothing to learn returns the same instance, so a caller can skip the write`() {
        val full = bare.copy(title = "T", author = "A", publishedAt = now, duration = kotlin.time.Duration.parse("1m"))
        assertSame(full, full.fillingSilenceFrom(bare))
    }
}
