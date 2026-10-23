package com.dewijones92.totum.ui.common

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.platform.app.InstrumentationRegistry
import com.dewijones92.totum.R
import com.dewijones92.totum.common.HttpUrl
import com.dewijones92.totum.domain.MediaItem
import com.dewijones92.totum.domain.MediaItemId
import com.dewijones92.totum.domain.PlayHandle
import com.dewijones92.totum.domain.PlayableItem
import com.dewijones92.totum.domain.SourceId
import com.dewijones92.totum.queue.PictureOffer
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class PictureOfferStripTest {

    @get:Rule val composeTestRule = createComposeRule()

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val offered = PlayableItem(
        MediaItem(
            id = MediaItemId("torrent:abc:1"),
            sourceId = SourceId("torrent"),
            title = "S01E01",
            publishedAt = null,
            duration = null,
            mediaUrl = HttpUrl.of("https://home.test/ts/stream/S01E01.mkv"),
        ),
        PlayHandle.Podcast(),
    )
    private val dismissed = mutableListOf<String>()
    private val watched = mutableListOf<String>()

    private val actions = object : ItemActions {
        override fun queue(items: List<MediaItem>, next: Boolean) = Unit
        override fun addToPlaylist(items: List<MediaItem>) = Unit
        override fun peek(item: MediaItem) = Unit
        override fun download(item: MediaItem, audioOnly: Boolean) = Unit
        override fun deleteDownload(id: MediaItemId) = Unit
        override fun setPlayed(id: MediaItemId, played: Boolean) = Unit
        override fun sourceLink(item: MediaItem): (() -> Unit)? = null
        override val audioMode: Boolean = true
        override fun switchMode(item: MediaItem) = Unit
        override fun watch(item: MediaItem) {
            watched += item.id.value
        }
    }

    private fun show(queuePlaying: MediaItemId) = composeTestRule.setContent {
        CompositionLocalProvider(
            LocalItemActions provides actions,
            LocalPictureOffer provides PictureOfferUi(PictureOffer(offered, 0L), queuePlaying) { dismissed += it },
        ) {
            PictureOfferStrip()
        }
    }

    @Test
    fun `the offer stands while the queue is on its item whatever the player still reports`() {
        show(queuePlaying = offered.item.id)

        composeTestRule.onNodeWithTag(PICTURE_OFFER_TAG).assertExists()
        assertEquals(emptyList<String>(), dismissed)
    }

    @Test
    fun `the offer closes once the queue has moved to another item`() {
        show(queuePlaying = MediaItemId("the next item"))

        composeTestRule.onNodeWithTag(PICTURE_OFFER_TAG).assertDoesNotExist()
        assertEquals(1, dismissed.size)
    }

    @Test
    fun `watch switches to the picture and closes the strip`() {
        show(queuePlaying = offered.item.id)

        composeTestRule.onNodeWithText(context.getString(R.string.picture_offer_watch)).performClick()
        composeTestRule.waitForIdle()

        assertEquals(listOf(offered.item.id.value), watched)
        assertEquals(listOf("Watch tapped"), dismissed)
    }
}
