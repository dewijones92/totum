package com.dewijones92.totum.ui.library

import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import com.dewijones92.totum.common.HttpUrl
import com.dewijones92.totum.domain.CleanupGroup
import com.dewijones92.totum.domain.CleanupSuggestion
import com.dewijones92.totum.domain.DownloadedMedia
import com.dewijones92.totum.domain.MediaItem
import com.dewijones92.totum.domain.MediaItemId
import com.dewijones92.totum.domain.SourceId
import com.dewijones92.totum.domain.StorageUsage
import com.dewijones92.totum.domain.asPlayable
import com.dewijones92.totum.theme.TotumTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class CleanupCardsTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private val mb = 1024L * 1024

    private fun entry(id: String, sizeMb: Long) = LibraryViewModel.Entry(
        DownloadedMedia(
            MediaItem(
                id = MediaItemId(id),
                sourceId = SourceId("s"),
                title = "Episode $id",
                publishedAt = null,
                duration = null,
                mediaUrl = HttpUrl.of("https://x.test/$id.mp3"),
            ).asPlayable(),
            "/fake/$id.media",
            audioOnly = true,
        ),
        sizeMb * mb,
    )

    private val entries = listOf(entry("a", 600), entry("b", 300), entry("c", 100))
    private val deleted = mutableListOf<Pair<CleanupGroup, List<String>>>()

    private fun show(vararg suggestions: CleanupSuggestion) {
        composeTestRule.setContent {
            TotumTheme {
                LibraryContent(
                    downloaded = entries,
                    inProgress = emptyList(),
                    failed = emptyList(),
                    storage = StorageUsage(itemCount = 3, usedBytes = 1000 * mb, freeBytes = null),
                    sort = DownloadSort.DEFAULT,
                    onOpenPlaylists = {},
                    onOpenHistory = {},
                    onOpenSubscriptions = {},
                    onOpenAccount = {},
                    onPlay = {},
                    onDelete = {},
                    onCancel = {},
                    onCancelAll = {},
                    onRetry = {},
                    onDismiss = {},
                    onAddToPlaylist = {},
                    onSetSort = {},
                    cleanup = Cleanup(suggestions.toList(), { group, ids -> deleted += group to ids.map { it.value } }),
                )
            }
        }
    }

    private fun suggestion(group: CleanupGroup, vararg ids: String) = CleanupSuggestion(
        group,
        ids.map(::MediaItemId),
        ids.sumOf { id -> entries.first { it.item.id.value == id }.sizeBytes },
    )

    private fun openCard(group: CleanupGroup) {
        val tag = "$CLEANUP_CARD_TAG-$group"
        composeTestRule.onNode(hasScrollAction()).performScrollToNode(hasTestTag(tag))
        composeTestRule.onNodeWithTag(tag).performClick()
    }

    @Test
    fun aCardSaysWhatItWouldFreeAndHowMany() {
        show(suggestion(CleanupGroup.NOT_OPENED, "a", "b"), suggestion(CleanupGroup.FINISHED, "c"))

        composeTestRule.onNode(hasScrollAction()).performScrollToNode(hasTestTag("$CLEANUP_CARD_TAG-FINISHED"))
        composeTestRule.onNodeWithText("🧹 Free 900 MB").assertExists()
        composeTestRule.onNodeWithText("2 not opened in 30 days").assertExists()
        composeTestRule.onNodeWithText("1 finished").assertExists()
    }

    @Test
    fun reviewingStartsWithEverythingTickedAndDeletesOnlyWhatStaysTicked() {
        show(suggestion(CleanupGroup.BIG, "a", "b", "c"))
        openCard(CleanupGroup.BIG)

        composeTestRule.onNodeWithText("Delete 3 · 1000 MB").assertExists()
        composeTestRule.onNodeWithTag("$CLEANUP_ROW_TAG-b").performClick()
        composeTestRule.onNodeWithText("Delete 2 · 700 MB").assertExists()
        composeTestRule.onNodeWithTag(CLEANUP_DELETE_TAG).performClick()

        assertEquals(listOf(CleanupGroup.BIG to listOf("a", "c")), deleted)
    }

    @Test
    fun withNothingTickedThereIsNothingToDelete() {
        show(suggestion(CleanupGroup.NOT_QUEUED, "c"))
        openCard(CleanupGroup.NOT_QUEUED)

        composeTestRule.onNodeWithTag("$CLEANUP_ROW_TAG-c").performClick()

        composeTestRule.onNodeWithTag(CLEANUP_DELETE_TAG).assertIsNotEnabled()
        assertEquals(emptyList<Pair<CleanupGroup, List<String>>>(), deleted)
    }

    @Test
    fun noSuggestionsMeansNoCards() {
        show()

        composeTestRule.onNodeWithText("Suggested clean-up").assertDoesNotExist()
    }
}
