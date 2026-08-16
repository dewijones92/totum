package com.dewijones92.totum.innertube.feeds

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Parses real captured channel-tab responses (WEB browse, 2026-07-24). */
class LockupParserTest {

    private fun fixture(name: String): String =
        checkNotNull(javaClass.getResourceAsStream("/$name")) { "fixture $name missing" }
            .bufferedReader().readText()

    @Test
    fun `channel Videos tab parses with published dates`() {
        val videos = LockupParser.videos(fixture("channel_videos_web_sample.json")).items
        assertTrue("expected videos", videos.size > 10)
        val first = videos.first()
        assertEquals("8Hx2yvWSgs0", first.videoId)
        assertTrue("title present", first.title.isNotBlank())
        assertEquals("https://www.youtube.com/watch?v=8Hx2yvWSgs0", first.watchUrl.value)
        // The date — the whole point. Most uploads carry "x ago" text.
        assertTrue("published dates present", videos.count { it.publishedText != null } > videos.size / 2)
        assertTrue("a duration parsed", videos.any { it.durationSeconds != null })
    }

    @Test
    fun `a channel tab carries view counts`() {
        val videos = LockupParser.videos(fixture("channel_videos_web_sample.json")).items
        assertTrue("view counts present", videos.count { it.viewsText != null } > videos.size / 2)
        assertTrue("and they say views", videos.mapNotNull { it.viewsText }.all { it.looksLikeViews() })
    }

    @Test
    fun `the author line is never the view count`() {
        // A channel's own tiles omit the channel name — you are already on the channel — so the
        // first metadata part is the view count. Reading it positionally made every row say
        // "6.2K views · 6.2K views · 10 hours ago" (seen on Novara Media, 2026-07-30).
        val videos = LockupParser.videos(fixture("channel_videos_web_sample.json")).items
        val authorsThatAreReallyViews = videos.mapNotNull { it.author }.filter { it.looksLikeViews() }
        assertEquals(emptyList<String>(), authorsThatAreReallyViews)
    }

    /**
     * The gap this feature had until now: a REAL "Members only" badge, from a live channel
     * tab (The Rest Is Politics, captured signed out 2026-07-30). Everything before this was
     * tested against a shape I had assumed.
     */
    @Test
    fun `members-only videos are flagged from a real channel tab`() {
        val videos = LockupParser.videos(fixture("channel_members_only_web_sample.json")).items
        val members = videos.filter { it.membersOnly }

        // Exact, because the fixture is frozen: 18 of the 30 videos on this tab are member cuts.
        // (That members OUTNUMBER public videos here is real — it is how the channel posts —
        // and an assertion that assumed otherwise is what caught my own wrong guess.)
        assertEquals(18, members.size)
        assertEquals(12, videos.count { !it.membersOnly })

        // Two of the three videos that sat failing to download in a real queue for days,
        // logged only as "Join this channel to get access". They were members-only all
        // along, and nothing in the app said so. Now they arrive flagged.
        val flagged = members.map { it.title }
        assertTrue(
            "expected the queue's stuck downloads among them: $flagged",
            flagged.any { it.startsWith("AD FREE | Education, Education") } &&
                flagged.any { it.startsWith("Britain's Ticking Time Bomb") },
        )
    }

    @Test
    fun `a duration badge is not mistaken for a membership`() {
        // thumbnailBadgeViewModel carries the length and the LIVE marker; reading it as a
        // badge would flag every video on the channel.
        val videos = LockupParser.videos(fixture("channel_videos_web_sample.json")).items

        assertEquals(emptyList<String>(), videos.filter { it.membersOnly }.map { it.title })
    }

    @Test
    fun `channel Shorts tab parses and tags SHORT`() {
        val shorts = LockupParser.shorts(fixture("channel_shorts_web_sample.json")).items
        assertTrue("expected shorts", shorts.size > 5)
        assertTrue("all SHORT", shorts.all { it.kind == FeedVideo.Kind.SHORT })
        assertTrue("ids present", shorts.all { it.videoId.isNotBlank() })
        assertTrue("titles present", shorts.all { it.title.isNotBlank() })
        assertEquals("https://www.youtube.com/watch?v=${shorts.first().videoId}", shorts.first().watchUrl.value)
    }

    @Test
    fun `channel Playlists tab parses to VL browse ids`() {
        val playlists = LockupParser.playlists(fixture("channel_playlists_web_sample.json")).items
        assertTrue("expected playlists", playlists.size > 5)
        assertTrue("VL-prefixed browse ids", playlists.all { it.browseId.startsWith("VL") })
        assertTrue("titles present", playlists.all { it.title.isNotBlank() })
    }

    /**
     * The view count a Shorts tile does carry, in `overlayMetadata.secondaryText`.
     *
     * Added 2026-08-16 so a Short listed among videos reads like every other row. Without it a
     * Short has no author, no date and no views, so the whole fact block under its title is empty
     * and the row looks broken rather than brief.
     */
    @Test
    fun `a channel Shorts tile carries its view count`() {
        val shorts = LockupParser.shorts(fixture("channel_shorts_web_sample.json")).items

        val withViews = shorts.filter { !it.viewsText.isNullOrBlank() }
        assertTrue("no Short carried a view count, got: ${shorts.take(3).map { it.viewsText }}", withViews.isNotEmpty())
        assertTrue(
            "a view count should read like YouTube writes it, got: ${withViews.first().viewsText}",
            withViews.first().viewsText!!.contains("view", ignoreCase = true),
        )
    }
}
