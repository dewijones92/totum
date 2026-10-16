package com.dewijones92.totum.ui.common

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredWidth
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.dp
import com.dewijones92.totum.theme.TotumTheme
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import kotlin.math.ceil

class AScreenTitleNeverBreaksAWordTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun aLongOneWordTitleKeepsItsWordWholeBesideFourActions() {
        val width = mutableIntStateOf(WIDTHS.first())
        composeTestRule.setContent {
            val w by width
            TotumTheme {
                Box(Modifier.requiredWidth(w.dp)) {
                    ScreenHeader(title = TITLE) {
                        repeat(
                            ACTIONS
                        ) { IconButton(onClick = {}) { Icon(Icons.Filled.Star, contentDescription = null) } }
                    }
                }
            }
        }
        for (dp in WIDTHS) {
            composeTestRule.runOnIdle { width.intValue = dp }
            composeTestRule.waitForIdle()
            val layouts = mutableListOf<TextLayoutResult>()
            composeTestRule.onNodeWithText(TITLE).fetchSemanticsNode()
                .config[SemanticsActions.GetTextLayoutResult].action?.invoke(layouts)
            val layout = layouts.single()
            val needs = ceil(layout.multiParagraph.intrinsics.minIntrinsicWidth).toInt()
            val given = layout.layoutInput.constraints.maxWidth
            assertTrue(
                "\"$TITLE\" needs ${needs}px for its longest word and was given ${given}px at ${dp}dp",
                given >= needs
            )
        }
    }

    private companion object {
        const val TITLE = "Subscriptions"
        const val ACTIONS = 4
        val WIDTHS = listOf(320, 360, 411, 600)
    }
}
