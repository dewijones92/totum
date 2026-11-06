package com.dewijones92.totum.ui

import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.junit4.ComposeTestRule

fun ComposeTestRule.waitForNode(matcher: SemanticsMatcher, timeoutMs: Long) {
    waitUntil(timeoutMs) { runCatching { onAllNodes(matcher).fetchSemanticsNodes().isNotEmpty() }.getOrDefault(false) }
}
