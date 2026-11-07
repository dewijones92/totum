package com.dewijones92.totum.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.platform.app.InstrumentationRegistry
import com.dewijones92.totum.R
import com.dewijones92.totum.data.backup.BackupItem
import com.dewijones92.totum.data.home.HomeAnswer
import com.dewijones92.totum.data.home.TargetsApi
import com.dewijones92.totum.data.targets.Requester
import com.dewijones92.totum.data.targets.Target
import com.dewijones92.totum.data.targets.TargetControl
import com.dewijones92.totum.data.targets.TargetEntry
import com.dewijones92.totum.data.targets.TargetKind
import com.dewijones92.totum.data.targets.TargetQueueRequest
import com.dewijones92.totum.data.targets.TargetState
import com.dewijones92.totum.data.targets.TargetsAnswer
import com.dewijones92.totum.targets.InMemoryTargetColours
import com.dewijones92.totum.targets.TargetsRepository
import com.dewijones92.totum.theme.TotumTheme
import com.dewijones92.totum.ui.queue.TargetChips
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test

class TargetChipsTest {

    @get:Rule
    val compose = createComposeRule()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @After
    fun stop() = scope.cancel()

    private fun text(id: Int): String = InstrumentationRegistry.getInstrumentation().targetContext.getString(id)

    private class Pi(var targets: List<TargetState>) : TargetsApi {
        override suspend fun targets() = HomeAnswer.Ok(TargetsAnswer(targets))
        override suspend fun queue(id: String, request: TargetQueueRequest): HomeAnswer<TargetState> =
            HomeAnswer.No("unused")
        override suspend fun control(id: String, control: TargetControl): HomeAnswer<TargetState> =
            HomeAnswer.No("unused")
    }

    private val queued = BackupItem("a", "feed", "Bluey", contentKind = "STANDARD", playbackType = "AUDIO")
    private val tv = TargetState(Target("lg-tv", "LG TV", TargetKind.TV), entries = listOf(TargetEntry(queued)))
    private val soundbar = TargetState(Target("bar", "Soundbar", TargetKind.SPEAKER))

    private fun repository(vararg states: TargetState) = TargetsRepository(
        Pi(states.toList()),
        { Requester("me", "My phone") },
        InMemoryTargetColours(),
        scope,
    )

    private fun show(targets: TargetsRepository) {
        compose.setContent { TotumTheme { TargetChips(targets) } }
        compose.waitUntil(WAIT_MS) { targets.targets.value.isNotEmpty() }
        compose.waitForIdle()
    }

    @Test
    fun aTargetWithAQueueGetsAChipAndTheIdleOnesWaitUnderMore() {
        show(repository(tv, soundbar))

        compose.onNodeWithText(text(R.string.target_chip_phone)).assertIsDisplayed()
        compose.onNodeWithText("📺 LG TV").assertIsDisplayed()
        compose.onNodeWithText(text(R.string.target_chip_more)).assertIsDisplayed()
        assertEquals(0, compose.onAllNodesWithText("🔈 Soundbar").fetchSemanticsNodes().size)
    }

    @Test
    fun theChipsStartOnThePhoneAndATapMovesTheSelection() {
        val repo = repository(tv, soundbar)
        show(repo)
        compose.onNodeWithText(text(R.string.target_chip_phone)).assertIsSelected()

        compose.onNodeWithText("📺 LG TV").performClick()
        compose.waitForIdle()

        assertEquals("lg-tv", repo.selected.value)
        compose.onNodeWithText("📺 LG TV").assertIsSelected()
        compose.onNodeWithText(text(R.string.target_chip_phone)).performClick()
        compose.waitForIdle()
        assertNull(repo.selected.value)
    }

    @Test
    fun anIdleTargetIsPickedFromMoreAndThenHasItsOwnChip() {
        val repo = repository(tv, soundbar)
        show(repo)

        compose.onNodeWithText(text(R.string.target_chip_more)).performClick()
        compose.onNodeWithText("🔈 Soundbar").performClick()
        compose.waitForIdle()

        assertEquals("bar", repo.selected.value)
        compose.onNodeWithText("🔈 Soundbar").assertIsSelected()
    }

    @Test
    fun withNoTargetsThereAreNoChipsAtAll() {
        val repo = repository()
        compose.setContent { TotumTheme { TargetChips(repo) } }
        compose.waitForIdle()
        Thread.sleep(SETTLE_MS)

        assertEquals(0, compose.onAllNodesWithText(text(R.string.target_chip_phone)).fetchSemanticsNodes().size)
    }

    private companion object {
        const val WAIT_MS = 10_000L
        const val SETTLE_MS = 500L
    }
}
