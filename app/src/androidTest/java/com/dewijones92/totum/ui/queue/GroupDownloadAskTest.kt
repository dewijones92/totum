package com.dewijones92.totum.ui.queue

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import com.dewijones92.totum.common.HttpUrl
import com.dewijones92.totum.data.queue.QueueEntry
import com.dewijones92.totum.data.queue.QueueGroup
import com.dewijones92.totum.data.queue.QueueSnapshot
import com.dewijones92.totum.di.fake.FakeAppContainer
import com.dewijones92.totum.domain.MediaItem
import com.dewijones92.totum.domain.MediaItemId
import com.dewijones92.totum.domain.PlayHandle
import com.dewijones92.totum.domain.PlayableItem
import com.dewijones92.totum.domain.SourceId
import com.dewijones92.totum.queue.GroupDownload
import com.dewijones92.totum.theme.TotumTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class GroupDownloadAskTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private val album = QueueGroup("album:a", "An album")

    private fun entry(id: String) = QueueEntry(
        PlayableItem(
            MediaItem(
                id = MediaItemId(id),
                sourceId = SourceId("s"),
                title = id,
                publishedAt = null,
                duration = null,
                mediaUrl = HttpUrl.of("https://example.com/$id.mp3"),
            ),
            PlayHandle.Podcast(),
        ),
        album,
    )

    private fun ask(): FakeAppContainer {
        val container = FakeAppContainer()
        container.playbackQueue.enqueueAll(listOf("t1", "t2").map { entry(it).item }, album)
        composeTestRule.waitUntil(5_000) { container.playbackQueue.state.value.entries.size == 2 }
        container.groupDownloads.track(QueueSnapshot(container.playbackQueue.state.value.entries))
        composeTestRule.setContent { TotumTheme { GroupDownloadAsk(container) } }
        return container
    }

    @Test
    fun aGroupJoiningTheQueueAsksAndDownloadMeansYes() {
        val container = ask()

        composeTestRule.onNodeWithTag(GROUP_DOWNLOAD_YES_TAG).performClick()

        assertEquals(GroupDownload.YES, container.groupDownloads.choices.value["album:a"])
        composeTestRule.onNodeWithTag(GROUP_DOWNLOAD_YES_TAG).assertDoesNotExist()
    }

    @Test
    fun notNowLeavesItStreaming() {
        val container = ask()

        composeTestRule.onNodeWithTag(GROUP_DOWNLOAD_NO_TAG).performClick()

        assertEquals(GroupDownload.NO, container.groupDownloads.choices.value["album:a"])
    }
}
