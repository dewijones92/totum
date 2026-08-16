package com.dewijones92.totum.innertube.feeds

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VideoTileParserTest {

    private fun fixture(): String =
        checkNotNull(javaClass.getResourceAsStream("/feed_tv_sample.json")) { "fixture missing" }
            .bufferedReader().readText()

    private fun parsed(): List<FeedVideo> =
        (VideoTileParser.parse(fixture()) as FeedResult.Success).page.items

    /**
     * The badge shape, captured live. This is the end of the chain that never worked: the
     * parser read metadata TEXT for a members marker, while YouTube puts it in a `badge`
     * node beside that text. Nothing failed — members-only just always reported false.
     */
    @Test
    fun `a members-only badge is read off the tile`() {
        val body = checkNotNull(javaClass.getResourceAsStream("/tile_badges_tv_sample.json")) { "missing" }
            .bufferedReader().readText()
        val videos = (VideoTileParser.parse(body) as FeedResult.Success).page.items

        assertEquals(listOf(false, true), videos.map { it.membersOnly })
        assertEquals(listOf("An ordinary upload", "Behind the membership"), videos.map { it.title })
    }

    /**
     * The channel id, which the app used to discover by running a **full yt-dlp extraction of
     * the video** — 12.5 seconds on Dewi's phone for a string YouTube sent with the tile.
     *
     * Matched by shape, not position: in the live response the "Go to channel" entry sat at
     * index 3, behind two other `menuNavigationItemRenderer`s that carry no `browseEndpoint`
     * at all. Reading a fixed index would have worked on that one feed and quietly broken on
     * the next.
     */
    @Test
    fun `reads the channel id out of the tile's own menu, wherever it sits`() {
        val body = checkNotNull(javaClass.getResourceAsStream("/tile_channel_menu_tv_sample.json")) { "missing" }
            .bufferedReader().readText()
        val videos = (VideoTileParser.parse(body) as FeedResult.Success).page.items

        assertEquals(
            listOf("UCaaaaaaaaaaaaaaaaaaaaaa", "UCbbbbbbbbbbbbbbbbbbbbbb", null),
            videos.map { it.channelId },
        )
    }

    @Test
    fun `collects videos in order, deduped, ignoring channel tiles`() {
        assertEquals(
            listOf("vid00000001", "vid00000002", "vid00000003", "vid00000004"),
            parsed().map { it.videoId },
        )
    }

    @Test
    fun `maps title, author, thumbnail and watch url`() {
        val first = parsed().first()
        assertEquals("First Video", first.title)
        assertEquals("Alpha Channel", first.author)
        assertEquals("https://www.youtube.com/watch?v=vid00000001", first.watchUrl.value)
        assertEquals("https://i.ytimg.com/vid00000001/hq.jpg", first.thumbnailUrl?.value)
    }

    @Test
    fun `parses m ss and h mm ss durations`() {
        val byId = parsed().associateBy { it.videoId }
        assertEquals(12L * 60 + 34, byId.getValue("vid00000001").durationSeconds)
        assertEquals(1L * 3600 + 2 * 60 + 3, byId.getValue("vid00000002").durationSeconds)
        assertEquals(45L, byId.getValue("vid00000004").durationSeconds)
    }

    @Test
    fun `a live item with no duration overlay yields a null duration`() {
        assertNull(parsed().first { it.videoId == "vid00000003" }.durationSeconds)
    }

    @Test
    fun `author can come from runs as well as simpleText`() {
        assertEquals("Delta Channel", parsed().first { it.videoId == "vid00000004" }.author)
    }

    @Test
    fun `unparseable json is a failure value`() {
        assertTrue(VideoTileParser.parse("not json") is FeedResult.Failure)
    }

    @Test
    fun `normal feed tiles are tagged VIDEO`() {
        assertTrue(parsed().all { it.kind == FeedVideo.Kind.VIDEO })
    }

    @Test
    fun `extracts the published relative time from tile metadata`() {
        val body = """
            {"contents":[{"tileRenderer":{
              "contentType":"TILE_CONTENT_TYPE_VIDEO",
              "onSelectCommand":{"watchEndpoint":{"videoId":"dated000001"}},
              "metadata":{"tileMetadataRenderer":{
                "title":{"simpleText":"Dated video"},
                "lines":[
                  {"lineRenderer":{"items":[{"lineItemRenderer":{"text":{"simpleText":"Some Channel"}}}]}},
                  {"lineRenderer":{"items":[
                    {"lineItemRenderer":{"text":{"simpleText":"12K views"}}},
                    {"lineItemRenderer":{"text":{"simpleText":"2 days ago"}}}
                  ]}}
                ]}}}}]}
        """.trimIndent()
        val video = (VideoTileParser.parse(body) as FeedResult.Success).page.items.single()
        assertEquals("2 days ago", video.publishedText)
        assertEquals("Some Channel", video.author)
    }

    @Test
    fun `a reel tile is tagged SHORT and its id read from the reel endpoint`() {
        val body = """
            {"contents":[{"tileRenderer":{
              "contentType":"TILE_CONTENT_TYPE_VIDEO",
              "onSelectCommand":{"reelWatchEndpoint":{"videoId":"short000001"}},
              "metadata":{"tileMetadataRenderer":{"title":{"simpleText":"A Short"}}}}}]}
        """.trimIndent()
        val video = (VideoTileParser.parse(body) as FeedResult.Success).page.items.single()
        assertEquals("short000001", video.videoId)
        assertEquals(FeedVideo.Kind.SHORT, video.kind)
    }

    @Test
    fun `a tile with a SHORTS time-status overlay is tagged SHORT`() {
        val body = """
            {"contents":[{"tileRenderer":{
              "contentType":"TILE_CONTENT_TYPE_VIDEO",
              "onSelectCommand":{"watchEndpoint":{"videoId":"short000002"}},
              "metadata":{"tileMetadataRenderer":{"title":{"simpleText":"Short two"}}},
              "header":{"tileHeaderRenderer":{"thumbnailOverlays":[
                {"thumbnailOverlayTimeStatusRenderer":{"style":"SHORTS"}}
              ]}}}}]}
        """.trimIndent()
        val video = (VideoTileParser.parse(body) as FeedResult.Success).page.items.single()
        assertEquals(FeedVideo.Kind.SHORT, video.kind)
    }

    @Test
    fun `a live tile is tagged LIVE`() {
        val body = """
            {"contents":[{"tileRenderer":{
              "contentType":"TILE_CONTENT_TYPE_VIDEO",
              "onSelectCommand":{"watchEndpoint":{"videoId":"live0000001"}},
              "metadata":{"tileMetadataRenderer":{"title":{"simpleText":"Live now"}}},
              "header":{"tileHeaderRenderer":{"thumbnailOverlays":[
                {"thumbnailOverlayTimeStatusRenderer":{"style":"LIVE","text":{"runs":[{"text":"LIVE"}]}}}
              ]}}}}]}
        """.trimIndent()
        val video = (VideoTileParser.parse(body) as FeedResult.Success).page.items.single()
        assertEquals(FeedVideo.Kind.LIVE, video.kind)
    }
    // ---- a Shorts shelf, when YouTube sends one (2026-08-16) ----------------------------------

    /**
     * SmartTube gets Shorts into a TV feed by reading a Shorts SHELF out of the very same
     * subscriptions response (`BrowseService2.getShortsTV`), because the tiles and the shelf are
     * different shapes in one body. This does the same.
     *
     * **The body here is hand-built, and that is worth stating.** Every real subscriptions
     * response captured from Dewi's account contains no shelf at all — 3.6 MB, 45 tiles, nothing —
     * which is SmartTube's own open bug #4278, so there is nothing to capture. The shape is taken
     * from the channel Shorts tab, where `shortsLockupViewModel` IS real and is covered against a
     * live fixture by `LockupParserTest`. This test pins the wiring: that the walk happens over a
     * TV feed body at all, and that a shelved Short lands in the same list as the tiles.
     */
    private val shelfBody = """
        {"contents":{"tvBrowseRenderer":{"content":{"tvSurfaceContentRenderer":{"content":{
          "sectionListRenderer":{"contents":[
            {"shelfRenderer":{"content":{"horizontalListRenderer":{"items":[
              {"shortsLockupViewModel":{
                "onTap":{"innertubeCommand":{"reelWatchEndpoint":{"videoId":"shortaaaaaa"}}},
                "overlayMetadata":{"primaryText":{"content":"A shelved Short"},
                                   "secondaryText":{"content":"12K views"}}}}
            ]}}}}
          ]}}}}}}}
    """.trimIndent()

    @Test
    fun `a shorts shelf in a TV feed is collected alongside the tiles`() {
        val items = (VideoTileParser.parse(shelfBody) as FeedResult.Success).page.items

        assertEquals(listOf("shortaaaaaa"), items.map { it.videoId })
        assertEquals(FeedVideo.Kind.SHORT, items.first().kind)
    }

    /** Tagged and readable: it arrives with the view count, like every other row in the feed. */
    @Test
    fun `a shelved short keeps its title and view count`() {
        val short = (VideoTileParser.parse(shelfBody) as FeedResult.Success).page.items.first()

        assertEquals("A shelved Short", short.title)
        assertEquals("12K views", short.viewsText)
    }

    /**
     * And the real feed is unchanged by looking. Today's responses have no shelf, so the walk must
     * cost the tile list nothing — a regression here would be Shorts support breaking the feed.
     */
    @Test
    fun `looking for a shelf does not disturb a feed that has none`() {
        assertTrue("the sample feed should still parse its tiles", parsed().isNotEmpty())
        assertTrue("no Shorts should be invented", parsed().none { it.kind == FeedVideo.Kind.SHORT })
    }
    // ---- the channel line is matched by shape, not by position (2026-08-16) -------------------

    /**
     * A tile whose FIRST metadata line is the view count.
     *
     * Hand-built, and worth saying so: 23 of 55 rows in Dewi's subscriptions feed showed this on
     * 2026-08-16, but the tiles on page ONE of that response all happen to lead with the channel,
     * so there was nothing to capture without paging deeper than a fixture wants to go. The shape
     * is a real `tileMetadataRenderer` with its lines in the order that breaks it.
     */
    private val viewsFirstBody = """
        {"contents":{"tvBrowseRenderer":{"content":{"tvSurfaceContentRenderer":{"content":{
          "sectionListRenderer":{"contents":[{"shelfRenderer":{"content":{"horizontalListRenderer":{"items":[
            {"tileRenderer":{
              "contentType":"TILE_CONTENT_TYPE_VIDEO",
              "onSelectCommand":{"watchEndpoint":{"videoId":"aaaaaaaaaaa"}},
              "metadata":{"tileMetadataRenderer":{
                "title":{"simpleText":"An older upload"},
                "lines":[
                  {"lineRenderer":{"items":[
                    {"lineItemRenderer":{"text":{"simpleText":"760K views"}}},
                    {"lineItemRenderer":{"text":{"simpleText":" · "}}}
                  ]}},
                  {"lineRenderer":{"items":[{"lineItemRenderer":{"text":{"simpleText":"A Real Channel"}}}]}},
                  {"lineRenderer":{"items":[{"lineItemRenderer":{"text":{"simpleText":"7 years ago"}}}]}}
                ]}}}}
          ]}}}}]}}}}}}}
    """.trimIndent()

    @Test
    fun `the channel is not the view count, even when the view count comes first`() {
        val item = (VideoTileParser.parse(viewsFirstBody) as FeedResult.Success).page.items.single()

        assertEquals("A Real Channel", item.author)
    }

    /** And the other two are still read correctly from the same tile. */
    @Test
    fun `views and date are unaffected by the reordering`() {
        val item = (VideoTileParser.parse(viewsFirstBody) as FeedResult.Success).page.items.single()

        assertEquals("760K views", item.viewsText)
        assertEquals("7 years ago", item.publishedText)
    }

    /** The ordinary order still works — the fix must not trade one arrangement for another. */
    @Test
    fun `a tile that leads with the channel still reads it`() {
        assertTrue("the sample feed should still name its channels", parsed().all { !it.author.isNullOrBlank() })
        assertTrue("and never as a view count", parsed().none { it.author == it.viewsText })
    }

    /**
     * A separator is not a channel name.
     *
     * The first version of the shape match excluded only view counts and dates, and the very next
     * run on Dewi's feed showed `📺 ·` — YouTube renders the "·" between metadata items as its own
     * line item. Caught by looking at the screen; the scan that had just reported "0 wrong" was
     * only ever looking for view counts, which is a lesson about checking the thing rather than
     * the previous failure.
     */
    @Test
    fun `a separator is never mistaken for the channel`() {
        val item = (VideoTileParser.parse(viewsFirstBody) as FeedResult.Success).page.items.single()

        assertEquals("A Real Channel", item.author)
    }

    /** Nothing usable at all is null, not a stray glyph — the row then shows no channel line. */
    @Test
    fun `a tile with only a view count and a separator has no author`() {
        val body = viewsFirstBody
            .replace(
                """{"lineRenderer":{"items":[{"lineItemRenderer":{"text":{"simpleText":"A Real Channel"}}}]}},""",
                ""
            )

        val item = (VideoTileParser.parse(body) as FeedResult.Success).page.items.single()

        assertNull(item.author)
    }
    // ---- watched positions, for the inbound half of progress sync (2026-08-16) ---------------

    private fun historyFixture(): String =
        checkNotNull(javaClass.getResourceAsStream("/history_tv_sample.json")) { "fixture missing" }
            .bufferedReader().readText()

    /**
     * Captured from Dewi's own account, and the numbers in it are the ones that proved the
     * outbound half works: the app reported `caVJh4jrOxE` at 789.873s, and this history came back
     * holding 13% of a 1:44:13 video — 789/6253 = 12.6%.
     */
    @Test
    fun `a watched position is read out of the real history response`() {
        val watched = VideoTileParser.watchedPositions(historyFixture())

        val partWatched = watched.getValue("caVJh4jrOxE")
        assertEquals("1:44:13 in millis", 6_253_000L, partWatched.durationMs)
        // 13% of 6,253,000ms. The app had reported 789,873ms, which is what YouTube rounded to 13.
        assertEquals(812_890L, partWatched.positionMs)
    }

    /** A finished video comes back at its full duration, not at some fraction of it. */
    @Test
    fun `a finished video reads as fully watched`() {
        val watched = VideoTileParser.watchedPositions(historyFixture())

        val finished = watched.getValue("Y3foPc3FvVM")
        assertEquals(finished.durationMs, finished.positionMs)
    }

    /** Several videos in one response, each with its own position — one request answers for all. */
    @Test
    fun `every watched video in the response is read`() {
        val watched = VideoTileParser.watchedPositions(historyFixture())

        assertEquals(
            setOf("caVJh4jrOxE", "XNRmWRA1dHg", "Y3foPc3FvVM", "62HSUsS0ypo"),
            watched.keys,
        )
    }

    /** An ordinary feed has no resume overlays, so it yields no positions rather than zeroes. */
    @Test
    fun `a feed with nothing watched yields no positions`() {
        assertEquals(emptyMap<String, AccountProgress>(), VideoTileParser.watchedPositions(fixture()))
    }

    @Test
    fun `unparseable json yields no positions rather than throwing`() {
        assertEquals(emptyMap<String, AccountProgress>(), VideoTileParser.watchedPositions("not json"))
    }
}
