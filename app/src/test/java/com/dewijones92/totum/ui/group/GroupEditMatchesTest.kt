package com.dewijones92.totum.ui.group

import com.dewijones92.totum.common.HttpUrl
import com.dewijones92.totum.domain.MediaSource
import com.dewijones92.totum.domain.SourceGroup
import com.dewijones92.totum.domain.SourceGroupId
import com.dewijones92.totum.domain.SourceId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GroupEditMatchesTest {

    private fun channel(title: String, id: String = title.replace(' ', '-')) = MediaSource.VideoChannel(
        id = SourceId("https://www.youtube.com/channel/$id"),
        title = title,
        channelUrl = HttpUrl.of("https://www.youtube.com/channel/$id"),
        artworkUrl = null,
    )

    private val subscribed =
        (1..42).map { channel("Tennis Channel $it") } +
            channel("Tennis TV", "UCbcxFkd6B9xUU54InHv4Tig") +
            (43..61).map { channel("Tennis Channel $it") } +
            listOf(channel("Fireship"), channel("Veritasium"))

    private val group = SourceGroup(SourceGroupId("g"), "tennis")

    @Test
    fun `a channel that is the forty-third of sixty-two matches is still offered`() {
        val titles = matches(subscribed, group, "tennis").map { it.title }

        assertEquals(62, titles.size)
        assertTrue("Tennis TV" in titles)
    }

    @Test
    fun `typing the whole name finds it`() {
        assertTrue(matches(subscribed, group, "tennis tv").any { it.title == "Tennis TV" })
    }

    @Test
    fun `a channel already in the group is not offered again, and a blank query offers nothing`() {
        val tennisTv = subscribed.first { it.title == "Tennis TV" }

        assertFalse(matches(subscribed, group.with(tennisTv), "tennis").any { it.title == "Tennis TV" })
        assertEquals(emptyList<MediaSource>(), matches(subscribed, group, "  "))
    }

    @Test
    fun `the match is fuzzy like every other list, so a small typo still finds it`() {
        assertTrue(matches(subscribed, group, "tenis tv").any { it.title == "Tennis TV" })
    }
}
