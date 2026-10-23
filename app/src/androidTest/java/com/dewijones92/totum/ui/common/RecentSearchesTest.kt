package com.dewijones92.totum.ui.common

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import com.dewijones92.totum.data.search.SearchHistoryStore
import com.dewijones92.totum.data.search.fake.InMemorySearchHistoryStore
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class RecentSearchesTest {

    @get:Rule val composeTestRule = createComposeRule()

    private val stores = mutableMapOf<String, SearchHistoryStore>()
    private val place = mutableStateOf("history")

    private fun show() = composeTestRule.setContent {
        CompositionLocalProvider(
            LocalRecentSearches provides { scope -> stores.getOrPut(scope) { InMemorySearchHistoryStore() } }
        ) {
            FilterableList(place.value, listOf("alpha", "beta"), { listOf(it) }, key = place.value) { shown, _ ->
                Column { shown.forEach { Text(it) } }
            }
        }
    }

    private fun recentChips() = composeTestRule.onAllNodesWithTag(RECENT_SEARCH_TAG, useUnmergedTree = true)

    @Test
    fun `a filter left in place is kept and offered again on that kind of list`() {
        show()
        composeTestRule.mainClock.autoAdvance = false
        composeTestRule.onNodeWithTag(FILTER_FIELD_TAG).performTextInput("alph")
        composeTestRule.mainClock.advanceTimeBy(RECENT_SETTLE_MS + 500)
        composeTestRule.mainClock.autoAdvance = true
        composeTestRule.onNodeWithTag(FILTER_FIELD_TAG).performTextReplacement("")
        composeTestRule.waitForIdle()

        assertEquals(listOf("alph"), runBlocking { stores.getValue("filter:history").recent().first() })
        recentChips().assertCountEquals(1)
        recentChips()[0].performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag(FILTER_FIELD_TAG).assertTextContains("alph")
    }

    @Test
    fun `half-typed words are not kept and another kind of list has its own`() {
        show()
        composeTestRule.mainClock.autoAdvance = false
        composeTestRule.onNodeWithTag(FILTER_FIELD_TAG).performTextInput("al")
        composeTestRule.mainClock.advanceTimeBy(RECENT_SETTLE_MS / 2)
        composeTestRule.onNodeWithTag(FILTER_FIELD_TAG).performTextInput("pha")
        composeTestRule.mainClock.advanceTimeBy(RECENT_SETTLE_MS + 500)
        composeTestRule.mainClock.autoAdvance = true
        composeTestRule.waitForIdle()
        assertEquals(listOf("alpha"), runBlocking { stores.getValue("filter:history").recent().first() })

        place.value = "downloads"
        composeTestRule.waitForIdle()
        recentChips().assertCountEquals(0)
    }
}

private fun androidx.compose.ui.test.SemanticsNodeInteractionCollection.assertCountEquals(n: Int) =
    assertEquals(n, fetchSemanticsNodes().size)
