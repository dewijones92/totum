package com.dewijones92.totum.ui

import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.junit4.ComposeTestRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import com.dewijones92.totum.ui.common.FILTER_FIELD_TAG

private val FILTER_TOGGLE = hasContentDescription("Filter", substring = true) and hasClickAction()
private const val SHOWN_WAIT_MS = 15_000L

internal fun ComposeTestRule.typeInListFilter(text: String, settle: () -> Unit) {
    waitUntil(SHOWN_WAIT_MS) {
        onAllNodesWithTag(FILTER_FIELD_TAG).fetchSemanticsNodes().isNotEmpty() ||
            onAllNodes(FILTER_TOGGLE).fetchSemanticsNodes().isNotEmpty()
    }
    if (onAllNodesWithTag(FILTER_FIELD_TAG).fetchSemanticsNodes().isEmpty()) {
        onNode(FILTER_TOGGLE).performClick()
        settle()
    }
    onNodeWithTag(FILTER_FIELD_TAG).performTextInput(text)
    settle()
}
