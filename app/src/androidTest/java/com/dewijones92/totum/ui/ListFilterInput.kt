package com.dewijones92.totum.ui

import androidx.compose.ui.test.SemanticsNodeInteractionsProvider
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import com.dewijones92.totum.ui.common.FILTER_FIELD_TAG

internal fun SemanticsNodeInteractionsProvider.typeInListFilter(text: String, settle: () -> Unit) {
    if (onAllNodesWithTag(FILTER_FIELD_TAG).fetchSemanticsNodes().isEmpty()) {
        onNode(hasContentDescription("Filter", substring = true) and hasClickAction()).performClick()
        settle()
    }
    onNodeWithTag(FILTER_FIELD_TAG).performTextInput(text)
    settle()
}
