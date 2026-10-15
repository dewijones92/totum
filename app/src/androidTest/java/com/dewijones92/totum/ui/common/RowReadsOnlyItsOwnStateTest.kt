package com.dewijones92.totum.ui.common

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.junit4.createComposeRule
import com.dewijones92.totum.domain.DownloadState
import com.dewijones92.totum.domain.MediaItemId
import com.dewijones92.totum.domain.PlayState
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class RowReadsOnlyItsOwnStateTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private val mine = MediaItemId("mine")
    private val other = MediaItemId("other")

    @Test
    fun aRowIsNotRedrawnWhenAnotherItemsProgressOrDownloadMoves() {
        val plays = mutableStateOf(mapOf(mine to PlayState.Unplayed, other to PlayState.InProgress(1, 100)))
        val downloads = mutableStateOf(mapOf<MediaItemId, DownloadState>(other to DownloadState.Downloading(1, 100)))
        var compositions = 0
        composeTestRule.setContent {
            CompositionLocalProvider(LocalPlayStates provides plays, LocalDownloadStates provides downloads) {
                playStateOf(mine)
                downloadStateOf(mine)
                SideEffect { compositions++ }
            }
        }
        composeTestRule.waitForIdle()
        val before = compositions

        repeat(5) { tick ->
            composeTestRule.runOnIdle {
                plays.value = plays.value + (other to PlayState.InProgress(tick + 2L, 100))
                downloads.value = downloads.value + (other to DownloadState.Downloading(tick + 2L, 100))
            }
            composeTestRule.waitForIdle()
        }
        assertEquals("redrawn for another item's ticks", before, compositions)

        composeTestRule.runOnIdle { plays.value = plays.value + (mine to PlayState.Played) }
        composeTestRule.waitForIdle()
        assertEquals("not redrawn for its own change", before + 1, compositions)
    }
}
