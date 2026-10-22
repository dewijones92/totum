package com.dewijones92.totum.shuffle

import com.dewijones92.totum.common.HttpUrl
import com.dewijones92.totum.domain.MediaSource
import com.dewijones92.totum.domain.SourceId
import org.junit.Assert.assertEquals
import org.junit.Test

class ShuffleTargetOfTest {

    private fun feed(id: String, title: String) = MediaSource.PodcastFeed(
        id = SourceId(id),
        title = title,
        feedUrl = HttpUrl.of("https://feed.test/$id.xml"),
    )

    @Test
    fun `several sources make one target named after them all, the same whatever order they were ticked in`() {
        val a = feed("a", "Show A")
        val b = feed("b", "Show B")

        val target = shuffleTargetOf(listOf(a, b))

        assertEquals("Show A + Show B", target.title)
        assertEquals(listOf(a, b), target.sources)
        assertEquals(target.key, shuffleTargetOf(listOf(b, a)).key)
        assertEquals(
            "one source keeps its own page's key, so it is one pin",
            "podcast:a",
            shuffleTargetOf(listOf(a)).key
        )
    }
}
