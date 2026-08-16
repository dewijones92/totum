package com.dewijones92.totum.ui

import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollToIndex
import com.dewijones92.totum.common.HttpUrl
import com.dewijones92.totum.domain.MediaItem
import com.dewijones92.totum.domain.MediaItemId
import com.dewijones92.totum.domain.MediaKind
import com.dewijones92.totum.domain.SourceId
import com.dewijones92.totum.domain.formatViewCount
import com.dewijones92.totum.theme.TotumTheme
import com.dewijones92.totum.ui.common.FactEmoji
import com.dewijones92.totum.ui.common.MediaItemRow
import com.dewijones92.totum.ui.common.mediaItemFacts
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import kotlin.time.Duration.Companion.seconds

/**
 * Views and dates still there a long way down a list.
 *
 * Dewi, 2026-08-06: *"test scrolling down also to see if they work for scrolled down of list of
 * videos"*.
 *
 * Honest about what this covers and what it does not. The data half — whether a row's facts survive
 * coming from a *continuation* rather than the first page — is where this can really break, and that
 * is covered on the JVM by `VideosPagingTest`. What this covers is the rendering half: a lazy list
 * disposes rows as they leave the viewport and recomposes them from scratch on the way back, keyed by
 * item, so a row built at index 60 is a genuinely different composition from the one at index 0.
 *
 * Worth having because a `key` mistake or a subtitle computed once outside the item scope would show
 * up exactly here and nowhere in a unit test — the feed screen has had a duplicate-key crash before
 * (`Key "PTdu0JlhGfw" was already used`, 31 Jul).
 */
class ScrolledRowMetadataTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private fun video(index: Int) = MediaItem(
        id = MediaItemId("video-$index"),
        sourceId = SourceId("test"),
        title = "Video number $index",
        publishedAt = null,
        publishedText = "$index days ago",
        duration = 90.seconds,
        author = "A Channel",
        thumbnailUrl = HttpUrl.of("https://example.test/thumb-$index.jpg"),
        viewsText = formatViewCount(index * VIEWS_PER_ITEM.toLong()),
    )

    private fun setUpList() {
        composeTestRule.setContent {
            TotumTheme {
                LazyColumn(modifier = Modifier.testTag(LIST)) {
                    items(List(ITEMS) { video(it) }, key = { it.id.value }) { item ->
                        MediaItemRow(
                            item = item,
                            subtitleLines = mediaItemFacts(item, MediaKind.VIDEO),
                            pillar = MediaKind.VIDEO,
                            onPlay = {},
                            onDownload = {},
                            onDeleteDownload = {},
                        )
                    }
                }
            }
        }
    }

    @Test
    fun `a row far down the list shows its view count and date`() {
        setUpList()

        composeTestRule.onNodeWithTag(LIST).performScrollToIndex(DEEP_INDEX)
        composeTestRule.waitForIdle()

        // Each fact is its own node now, so each is asserted on its own — which is a stronger
        // check than the joined string was: that one passed as long as the START of the line was
        // present, which is exactly the half that never went missing.
        composeTestRule.onNodeWithText(viewsOf(DEEP_INDEX)).assertIsDisplayed()
        composeTestRule.onNodeWithText(dateOf(DEEP_INDEX)).assertIsDisplayed()
    }

    /** The very last row too — the end of a list is where an off-by-one in keying shows up. */
    @Test
    fun `the last row shows its view count and date`() {
        setUpList()

        composeTestRule.onNodeWithTag(LIST).performScrollToIndex(ITEMS - 1)
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithText(viewsOf(ITEMS - 1)).assertIsDisplayed()
        composeTestRule.onNodeWithText(dateOf(ITEMS - 1)).assertIsDisplayed()
    }

    /**
     * And back up again, which is the recycling case rather than the scrolling one.
     *
     * A row returning into view is recomposed from nothing; anything cached against the wrong scope
     * would come back showing another item's numbers, which is worse than showing none.
     */
    @Test
    fun `scrolling back up shows the first rows facts again and not another rows`() {
        setUpList()

        composeTestRule.onNodeWithTag(LIST).performScrollToIndex(DEEP_INDEX)
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag(LIST).performScrollToIndex(0)
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithText(viewsOf(1)).assertIsDisplayed()
        composeTestRule.onNodeWithText(dateOf(1)).assertIsDisplayed()
        assertEquals(
            "a recycled row is showing a different item's numbers",
            0,
            composeTestRule.onAllNodesWithText(viewsOf(DEEP_INDEX)).fetchSemanticsNodes().size,
        )
    }

    /** The row's own view-count line — unique per row, so a recycled row cannot fake it. */
    private fun viewsOf(index: Int): String = "${FactEmoji.VIEWS} ${formatViewCount(index * VIEWS_PER_ITEM.toLong())}"

    /** And its date line, likewise unique per row. */
    private fun dateOf(index: Int): String = "${FactEmoji.PUBLISHED} $index days ago"

    private companion object {
        const val ITEMS = 80

        /** Well past any plausible viewport, so the row must have been composed during scrolling. */
        const val DEEP_INDEX = 60

        /** Distinct per row, so one row's numbers cannot be mistaken for another's. */
        const val VIEWS_PER_ITEM = 1_234
        const val LIST = "video-list"
    }
}
