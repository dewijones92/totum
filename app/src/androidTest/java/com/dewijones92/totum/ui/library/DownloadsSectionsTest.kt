package com.dewijones92.totum.ui.library

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.dewijones92.totum.data.download.RetryPlan
import com.dewijones92.totum.domain.MediaItem
import com.dewijones92.totum.domain.MediaItemId
import com.dewijones92.totum.domain.SourceId
import com.dewijones92.totum.domain.StorageUsage
import com.dewijones92.totum.downloads.DownloadsSection
import com.dewijones92.totum.theme.TotumTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class DownloadsSectionsTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private fun item(id: String) = MediaItem(
        id = MediaItemId(id),
        sourceId = SourceId("s"),
        title = "Public Domain Film $id",
        publishedAt = null,
        duration = null,
    )

    private var retriedAll = 0

    private fun show(section: DownloadsSection? = null) = composeTestRule.setContent {
        TotumTheme {
            DownloadsContent(
                downloaded = emptyList(),
                inProgress = emptyList(),
                failed = listOf(
                    LibraryViewModel.Failed(
                        item("a"),
                        "timeout",
                        RetryPlan(attempt = 2, of = 4, atMs = Long.MAX_VALUE)
                    ),
                    LibraryViewModel.Failed(item("b"), "no space"),
                ),
                storage = StorageUsage(itemCount = 0, usedBytes = 0, freeBytes = null),
                sort = DownloadSort.DEFAULT,
                onBack = {},
                onPlay = {},
                onDelete = {},
                onCancel = {},
                onCancelAll = {},
                onRetry = {},
                onDismiss = {},
                onAddToPlaylist = {},
                onSetSort = {},
                sections = Sections(current = section, onRetryAll = { retriedAll++ }),
            )
        }
    }

    @Test
    fun aRetryingDownloadIsInProgressAndAStoppedOneIsUnderFailed() {
        show()
        composeTestRule.onNodeWithText("⏳ In progress (1)").assertExists()
        composeTestRule.onNodeWithText("Public Domain Film a").assertExists()
        composeTestRule.onNodeWithText("Public Domain Film b").assertDoesNotExist()

        composeTestRule.onNodeWithText("❌ Failed (1)").performClick()
        composeTestRule.onNodeWithText("Public Domain Film b").assertExists()
        composeTestRule.onNodeWithTag(DOWNLOADS_RETRY_ALL_TAG).performClick()
        assertEquals(1, retriedAll)
    }

    @Test
    fun aNotificationCanOpenStraightAtTheFailures() {
        show(DownloadsSection.FAILED)
        composeTestRule.onNodeWithText("Public Domain Film b").assertExists()
        composeTestRule.onNodeWithText("Public Domain Film a").assertDoesNotExist()
    }
}
