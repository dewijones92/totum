package com.dewijones92.totum.ui.common

import com.dewijones92.totum.common.HttpUrl
import com.dewijones92.totum.domain.FollowedSources
import com.dewijones92.totum.domain.Following
import com.dewijones92.totum.domain.MediaSource
import com.dewijones92.totum.domain.SourceId
import org.junit.Assert.assertEquals
import org.junit.Test

class ShowHitFollowMarkTest {
    private val rows = RowFollowing(FollowedSources(emptyList(), emptyList(), signedIn = true), null)
    private val show = MediaSource.PodcastFeed(SourceId("feed"), "More or Less", HttpUrl.of("https://feed.test/rss"))

    @Test
    fun `a show in search wears the same follow mark as a row`() {
        assertEquals(Following.Subscribed(show), rows.markForSource(show, subscribed = true).following)
        assertEquals(Following.NotSubscribed(show), rows.markForSource(show, subscribed = false).following)
    }
}
