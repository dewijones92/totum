package com.dewijones92.totum.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import com.dewijones92.totum.common.HttpUrl
import com.dewijones92.totum.common.PageToken
import com.dewijones92.totum.domain.MediaItem
import com.dewijones92.totum.domain.MediaItemId
import com.dewijones92.totum.domain.SourceId
import com.dewijones92.totum.theme.TotumTheme
import com.dewijones92.totum.ui.channel.ChannelContent
import com.dewijones92.totum.ui.channel.ChannelViewModel
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.time.Instant

class AChannelTabPagesPolitelyUnderAFilterTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private fun upload(id: String, publishedAt: Instant? = null) = MediaItem(
        id = MediaItemId(id),
        sourceId = SourceId("s"),
        title = "Upload $id",
        publishedAt = publishedAt,
        duration = null,
        mediaUrl = HttpUrl.of("https://www.youtube.com/watch?v=$id"),
    )

    @Test
    fun aFilterThatHidesMostRowsStopsPagingForTheOnesItHides() {
        var pagesAsked = 0
        var state by mutableStateOf(
            ChannelViewModel.UiState(
                title = "A channel",
                videos = ChannelViewModel.TabState(
                    loaded = true,
                    items = listOf(upload("today", Instant.now()), upload("a"), upload("b")),
                    next = PageToken("more"),
                ),
            ),
        )
        composeTestRule.setContent {
            TotumTheme {
                ChannelContent(
                    state = state,
                    onBack = {},
                    onOpenGroups = {},
                    onToggleSubscribed = {},
                    onSelectTab = {},
                    onSearch = {},
                    onPlay = {},
                    onDownload = {},
                    onDeleteDownload = {},
                    onAddToPlaylist = {},
                    onOpenPlaylist = {},
                    onLoadMore = {
                        if (pagesAsked < RUNAWAY) {
                            pagesAsked++
                            val more = state.videos.items + upload("p$pagesAsked")
                            state = state.copy(videos = state.videos.copy(items = more))
                        }
                    },
                    sourceKey = "A",
                )
            }
        }
        composeTestRule.waitForIdle()
        val beforeTheFilter = pagesAsked

        composeTestRule.onNodeWithContentDescription("Sort and filter").performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithText("Today").performScrollTo().performClick()
        composeTestRule.mainClock.advanceTimeBy(SETTLE_MS)
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithText("Upload a").assertDoesNotExist()
        composeTestRule.onNodeWithText("Upload today").assertExists()
        val asked = pagesAsked - beforeTheFilter
        println("dewidebug paging before the filter=$beforeTheFilter after=$asked")
        assertTrue("paged $asked times for rows the filter hid", asked <= 1)
    }

    private companion object {
        const val RUNAWAY = 30
        const val SETTLE_MS = 2_000L
    }
}
