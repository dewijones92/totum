package com.dewijones92.totum.ui.ask

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.dewijones92.totum.R
import com.dewijones92.totum.data.ask.AskAnswer
import com.dewijones92.totum.data.ask.AskOutcome
import com.dewijones92.totum.data.ask.AskRequest
import com.dewijones92.totum.data.ask.AskService
import com.dewijones92.totum.data.ask.InMemoryAskChats
import com.dewijones92.totum.data.ask.SavedChat
import com.dewijones92.totum.data.ask.SavedTurn
import com.dewijones92.totum.data.ask.VetAnswer
import com.dewijones92.totum.data.ask.VetRequest
import com.dewijones92.totum.data.search.fake.InMemorySearchHistoryStore
import com.dewijones92.totum.di.fake.FakeAppContainer
import com.dewijones92.totum.theme.TotumTheme
import com.dewijones92.totum.ui.common.LocalRecentSearches
import com.dewijones92.totum.ui.common.RECENT_SEARCH_TAG
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AskInputStaysVisibleTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    private val service = object : AskService {
        override suspend fun ask(request: AskRequest, progress: (Long) -> Unit): AskOutcome<AskAnswer> =
            AskOutcome.Failed("🧪 not asked in this test")

        override suspend fun vet(request: VetRequest, progress: (Long) -> Unit): AskOutcome<VetAnswer> =
            AskOutcome.Failed("🧪 not asked in this test")
    }

    private fun showOnASmallWindow(chats: Int, recents: Int) {
        val container = FakeAppContainer(askService = service)
        (container.askChats as InMemoryAskChats).save(
            (1..chats).map { n ->
                SavedChat("chat-$n", n * MINUTE_MS, listOf(SavedTurn("An earlier question number $n about films")))
            }
        )
        val history = InMemorySearchHistoryStore()
        runBlocking { (1..recents).forEach { history.record("a recent search with a few words $it") } }
        composeTestRule.setContent {
            TotumTheme {
                CompositionLocalProvider(LocalRecentSearches provides { history }) {
                    Box(Modifier.size(SMALL_WIDTH_DP.dp, SMALL_HEIGHT_DP.dp)) { AskScreen(container, onBack = {}) }
                }
            }
        }
        composeTestRule.waitForIdle()
        composeTestRule.onNode(hasText(context.getString(R.string.ask_start_over))).performClick()
        composeTestRule.waitForIdle()
    }

    @Test
    fun `a new chat with many earlier chats and many recent searches still shows the input`() {
        showOnASmallWindow(chats = MANY, recents = MANY)

        composeTestRule.onNodeWithTag(ASK_INPUT_TAG).assertIsDisplayed().assertHeightIsAtLeast(MIN_INPUT_DP.dp)
    }

    @Test
    fun `the chips are still shown above the input`() {
        showOnASmallWindow(chats = MANY, recents = MANY)

        composeTestRule.onAllNodesWithTag(RECENT_SEARCH_TAG, useUnmergedTree = true)[0].assertIsDisplayed()
        composeTestRule.onNodeWithTag(ASK_INPUT_TAG).assertIsDisplayed().assertHeightIsAtLeast(MIN_INPUT_DP.dp)
    }

    private companion object {
        const val SMALL_WIDTH_DP = 320
        const val SMALL_HEIGHT_DP = 560
        const val MIN_INPUT_DP = 48
        const val MANY = 30
        const val MINUTE_MS = 60_000L
    }
}
