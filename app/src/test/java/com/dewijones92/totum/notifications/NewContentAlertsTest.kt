package com.dewijones92.totum.notifications

import com.dewijones92.totum.common.HttpUrl
import com.dewijones92.totum.data.content.SourceUpdate
import com.dewijones92.totum.domain.MediaItem
import com.dewijones92.totum.domain.MediaItemId
import com.dewijones92.totum.domain.MediaSource
import com.dewijones92.totum.domain.SourceId
import com.dewijones92.totum.settings.AppPreferences
import org.junit.Assert.assertEquals
import org.junit.Test

class NewContentAlertsTest {

    private val channel = MediaSource.VideoChannel(
        SourceId("yt"),
        "YouTube subscriptions",
        HttpUrl.of("https://www.youtube.com/feed")
    )
    private val feed = MediaSource.PodcastFeed(SourceId("pod"), "A show", HttpUrl.of("https://example.com/feed.xml"))
    private val updates = listOf(channel, feed).map { source ->
        SourceUpdate(
            source,
            listOf(
                MediaItem(MediaItemId("${source.id.value}-1"), source.id, "new", publishedAt = null, duration = null)
            )
        )
    }

    @Test
    fun `each switch silences only its own kind of source`() {
        val noYouTube = AppPreferences.Settings(youTubeUploadAlerts = false)
        val noPodcasts = AppPreferences.Settings(podcastEpisodeAlerts = false)
        assertEquals(listOf(feed), updates.wanted(noYouTube).map { it.source })
        assertEquals(listOf(channel), updates.wanted(noPodcasts).map { it.source })
        assertEquals(updates, updates.wanted(AppPreferences.Settings()))
    }
}
