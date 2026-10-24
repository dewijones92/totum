package com.dewijones92.totum.ui.common

import androidx.activity.ComponentActivity
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Text
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollToIndex
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class ListThenDetailTest {

    @get:Rule val composeTestRule = createAndroidComposeRule<ComponentActivity>()

    private val open = mutableStateOf<String?>(null)
    private var listState: LazyListState? = null

    private fun show() = composeTestRule.setContent {
        ListThenDetail(
            open.value,
            listKey = "results",
            onBack = { open.value = null },
            list = {
                val state = rememberLazyListState().also { listState = it }
                LazyColumn(Modifier.testTag("list"), state = state) {
                    items(100) { Text("Row $it") }
                }
            },
        ) { name -> Text("Page $name") }
    }

    @Test
    fun `going into a page and back returns to the same place in the list`() {
        show()
        composeTestRule.onNodeWithTag("list").performScrollToIndex(40)
        composeTestRule.waitForIdle()
        val before = listState!!.firstVisibleItemIndex

        open.value = "a channel"
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithText("Page a channel").assertExists()
        open.value = null
        composeTestRule.waitForIdle()

        assertEquals(40, before)
        assertEquals(before, listState!!.firstVisibleItemIndex)
        composeTestRule.onNodeWithText("Row 40").assertExists()
    }

    @Test
    fun `system back closes the page and keeps the place in the list`() {
        show()
        composeTestRule.onNodeWithTag("list").performScrollToIndex(25)
        composeTestRule.waitForIdle()
        open.value = "a channel"
        composeTestRule.waitForIdle()

        composeTestRule.runOnUiThread { composeTestRule.activity.onBackPressedDispatcher.onBackPressed() }
        composeTestRule.waitForIdle()

        assertEquals(null, open.value)
        assertEquals(25, listState!!.firstVisibleItemIndex)
    }
}
