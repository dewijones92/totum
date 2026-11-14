package com.dewijones92.totum.ui.common

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import com.dewijones92.totum.playback.ActiveMode
import com.dewijones92.totum.theme.TotumTheme
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class ActiveModesRowTest {

    @get:Rule
    val compose = createComposeRule()

    private val modes = MutableStateFlow<List<ActiveMode>>(emptyList())
    private var opened = 0

    private fun show(provided: MutableStateFlow<List<ActiveMode>>? = modes) {
        compose.setContent {
            TotumTheme {
                CompositionLocalProvider(LocalActiveModes provides provided) {
                    ActiveModesRow(from = "test", onOpenPlayer = { opened++ })
                }
            }
        }
        compose.waitForIdle()
    }

    private fun chip(mode: ActiveMode) = compose.onNodeWithTag("$ACTIVE_MODE_TAG:${mode.diagName}")

    @Test
    fun nothingOnMeansNoRowAtAll() {
        show()

        compose.onNodeWithTag(ACTIVE_MODES_TAG).assertDoesNotExist()
    }

    @Test
    fun withNoModesSourceThereIsNoRowEither() {
        show(provided = null)

        compose.onNodeWithTag(ACTIVE_MODES_TAG).assertDoesNotExist()
    }

    @Test
    fun everyModeThatIsOnIsAChipWithItsWords() {
        modes.value = listOf(ActiveMode.RepeatOne, ActiveMode.Explore, ActiveMode.Speed(1.5f), ActiveMode.SleepTimer)
        show()

        compose.onNodeWithTag(ACTIVE_MODES_TAG).assertIsDisplayed()
        chip(ActiveMode.RepeatOne).assertIsDisplayed()
        chip(ActiveMode.Explore).assertIsDisplayed()
        chip(ActiveMode.Speed(1.5f)).assertIsDisplayed()
        chip(ActiveMode.SleepTimer).assertIsDisplayed()
        chip(ActiveMode.Shuffle).assertDoesNotExist()
    }

    @Test
    fun theRowFollowsTheModesAsTheyComeAndGo() {
        modes.value = listOf(ActiveMode.RepeatOne)
        show()
        chip(ActiveMode.RepeatOne).assertIsDisplayed()

        modes.value = listOf(ActiveMode.RepeatGroup, ActiveMode.Shuffle)
        compose.waitForIdle()
        chip(ActiveMode.RepeatOne).assertDoesNotExist()
        chip(ActiveMode.Shuffle).assertIsDisplayed()

        modes.value = emptyList()
        compose.waitForIdle()
        compose.onNodeWithTag(ACTIVE_MODES_TAG).assertDoesNotExist()
    }

    @Test
    fun tappingAChipOpensThePlayer() {
        modes.value = listOf(ActiveMode.RepeatOne, ActiveMode.KidLock)
        show()

        chip(ActiveMode.RepeatOne).performClick()
        chip(ActiveMode.KidLock).performClick()

        assertEquals(2, opened)
    }
}
