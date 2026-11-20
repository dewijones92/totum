package com.dewijones92.totum.data.shuffle

import com.dewijones92.totum.common.HttpUrl
import com.dewijones92.totum.common.Page
import com.dewijones92.totum.common.PageToken
import com.dewijones92.totum.domain.MediaItem
import com.dewijones92.totum.domain.MediaItemId
import com.dewijones92.totum.domain.MediaSource
import com.dewijones92.totum.domain.SourceId
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WholeSourceTest {

    private val channel = MediaSource.VideoChannel(
        SourceId("chan"),
        "A channel",
        HttpUrl.of("https://www.youtube.com/channel/UCaaa"),
    )
    private val feed = MediaSource.PodcastFeed(SourceId("pod"), "A podcast", HttpUrl.of("https://example.com/feed.xml"))

    private fun item(id: String, source: MediaSource = channel) =
        MediaItem(id = MediaItemId(id), sourceId = source.id, title = id, publishedAt = null, duration = null)

    private fun pagesOf(vararg pages: List<String>): Map<PageToken?, Page<MediaItem>> =
        pages.withIndex().associate { (index, ids) ->
            val token = if (index == 0) null else PageToken("p$index")
            val next = if (index == pages.lastIndex) null else PageToken("p${index + 1}")
            token to Page(ids.map { item(it) }, next)
        }

    private fun uploadsOnly(pages: SourcePages) = SourcePages { source, part, after ->
        if (part == SourcePart.UPLOADS) pages.page(source, part, after) else Page.empty()
    }

    @Test
    fun `walks every page of a channel to the end`() = runTest {
        val uploads = pagesOf(listOf("a", "b"), listOf("c", "d"), listOf("e"))
        var progress = 0
        val gathered = WholeSource(uploadsOnly { _, _, after -> uploads[after] })
            .everything(listOf(channel), includeShorts = false) { progress = it }

        assertEquals(listOf("a", "b", "c", "d", "e"), gathered.items.map { it.id.value })
        assertEquals(3, gathered.pagesByPart[SourcePart.UPLOADS])
        assertEquals(5, progress)
        assertTrue(gathered.unfinished.isEmpty())
    }

    @Test
    fun `shorts are walked only when asked for`() = runTest {
        val source = SourcePages { _, part, _ ->
            Page.last(listOf(item(if (part == SourcePart.SHORTS) "short" else "video")))
        }

        val without = WholeSource(source).everything(listOf(channel), includeShorts = false)
        val with = WholeSource(source).everything(listOf(channel), includeShorts = true)

        assertEquals(listOf("video"), without.items.map { it.id.value })
        assertEquals(setOf("video", "short"), with.items.map { it.id.value }.toSet())
    }

    @Test
    fun `a page that keeps failing keeps what came before it and says so`() = runTest {
        val uploads = pagesOf(listOf("a", "b"), listOf("c"))
        var asks = 0
        val gathered = WholeSource(
            uploadsOnly { _, _, after ->
                if (after == null) uploads[null] else null.also { asks++ }
            }
        ).everything(listOf(channel), includeShorts = false)

        assertEquals(listOf("a", "b"), gathered.items.map { it.id.value })
        assertEquals(3, asks)
        assertTrue(gathered.unfinished.single().contains("page 2 failed 3 times"))
    }

    @Test
    fun `a flaky page is retried and the walk carries on`() = runTest {
        val uploads = pagesOf(listOf("a"), listOf("b"))
        var failedOnce = false
        val gathered = WholeSource(
            uploadsOnly { _, _, after ->
                if (after != null && !failedOnce) null.also { failedOnce = true } else uploads[after]
            }
        ).everything(listOf(channel), includeShorts = false)

        assertEquals(listOf("a", "b"), gathered.items.map { it.id.value })
        assertTrue(gathered.unfinished.isEmpty())
    }

    @Test
    fun `a continuation that repeats cannot loop for ever`() = runTest {
        val gathered = WholeSource(uploadsOnly { _, _, _ -> Page(listOf(item("a")), PageToken("same")) })
            .everything(listOf(channel), includeShorts = false)

        assertEquals(listOf("a"), gathered.items.map { it.id.value })
        assertTrue(gathered.unfinished.single().contains("continuation repeated"))
    }

    @Test
    fun `a group's channels and podcasts come back together, each item once`() = runTest {
        val gathered = WholeSource({ source, _, _ ->
            when (source) {
                is MediaSource.VideoChannel -> Page.last(listOf(item("v1"), item("shared")))
                is MediaSource.PodcastFeed -> Page.last(listOf(item("e1", feed), item("shared")))
            }
        }).everything(listOf(channel, feed), includeShorts = false)

        assertEquals(setOf("v1", "e1", "shared"), gathered.items.map { it.id.value }.toSet())
        assertEquals(3, gathered.items.size)
    }

    @Test
    fun `live streams are walked without being asked for, a page at a time`() = runTest {
        val lives = pagesOf(listOf("l1", "l2"), listOf("l3"))
        val gathered = WholeSource({ _, part, after ->
            when (part) {
                SourcePart.UPLOADS -> Page.last(listOf(item("v1")))
                SourcePart.LIVE -> lives[after]
                SourcePart.SHORTS -> Page.last(listOf(item("s1")))
            }
        }).everything(listOf(channel), includeShorts = false)

        assertEquals(listOf("v1", "l1", "l2", "l3"), gathered.items.map { it.id.value })
        assertEquals(mapOf(SourcePart.UPLOADS to 1, SourcePart.LIVE to 2), gathered.pagesByPart)
        assertTrue(gathered.unfinished.isEmpty())
    }

    @Test
    fun `a channel's live walk starts only when its uploads walk is over`() = runTest {
        val order = mutableListOf<String>()
        WholeSource({ _, part, after ->
            order += "${part.name.lowercase()}:${after?.value ?: "first"}"
            when {
                part == SourcePart.UPLOADS && after == null -> Page(listOf(item("v1")), PageToken("more"))
                else -> Page.last(listOf(item("${part.name}-${after?.value}")))
            }
        }).everything(listOf(channel), includeShorts = false)

        assertEquals(listOf("uploads:first", "uploads:more", "live:first"), order)
    }

    @Test
    fun `a channel with nothing live costs one empty page and is not reported as unfinished`() = runTest {
        val gathered = WholeSource(uploadsOnly { _, _, _ -> Page.last(listOf(item("v1"))) })
            .everything(listOf(channel), includeShorts = false)

        assertEquals(listOf("v1"), gathered.items.map { it.id.value })
        assertEquals(2, gathered.pages)
        assertTrue(gathered.unfinished.isEmpty())
    }

    @Test
    fun `a live walk that keeps failing keeps the uploads and is reported`() = runTest {
        val gathered = WholeSource({ _, part, _ ->
            if (part == SourcePart.LIVE) null else Page.last(listOf(item("v1")))
        }).everything(listOf(channel), includeShorts = false)

        assertEquals(listOf("v1"), gathered.items.map { it.id.value })
        assertTrue(gathered.unfinished.single().contains("LIVE"))
    }
}
