package com.dewijones92.totum.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.platform.app.InstrumentationRegistry
import com.dewijones92.totum.R
import com.dewijones92.totum.data.backup.BackupItem
import com.dewijones92.totum.data.home.HomeAnswer
import com.dewijones92.totum.data.home.TargetsApi
import com.dewijones92.totum.data.targets.Requester
import com.dewijones92.totum.data.targets.Target
import com.dewijones92.totum.data.targets.TargetAction
import com.dewijones92.totum.data.targets.TargetControl
import com.dewijones92.totum.data.targets.TargetEntry
import com.dewijones92.totum.data.targets.TargetKind
import com.dewijones92.totum.data.targets.TargetQueueRequest
import com.dewijones92.totum.data.targets.TargetState
import com.dewijones92.totum.data.targets.TargetTrouble
import com.dewijones92.totum.data.targets.TargetsAnswer
import com.dewijones92.totum.data.targets.TroubleKind
import com.dewijones92.totum.targets.ContextTargetWords
import com.dewijones92.totum.targets.InMemoryTargetColours
import com.dewijones92.totum.targets.TargetsRepository
import com.dewijones92.totum.theme.TotumTheme
import com.dewijones92.totum.ui.queue.RemoteQueue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList

class RemoteQueueTest {

    @get:Rule
    val compose = createComposeRule()

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @After
    fun stop() = scope.cancel()

    private class Pi(var targets: List<TargetState>) : TargetsApi {
        val controls = CopyOnWriteArrayList<TargetControl>()
        override suspend fun targets() = HomeAnswer.Ok(TargetsAnswer(targets))
        override suspend fun queue(id: String, request: TargetQueueRequest): HomeAnswer<TargetState> =
            HomeAnswer.No("unused")
        override suspend fun control(id: String, control: TargetControl): HomeAnswer<TargetState> {
            controls += control
            return HomeAnswer.No("unused")
        }
    }

    private fun item(id: String, title: String) =
        BackupItem(id, "feed", title, contentKind = "STANDARD", playbackType = "AUDIO")

    private val tv = TargetState(
        Target("lg-tv", "LG TV", TargetKind.TV),
        entries = listOf(TargetEntry(item("a", "Episode one")), TargetEntry(item("b", "Episode two"))),
        at = 0,
        playing = true,
        positionMs = 125_000,
        durationMs = 600_000,
    )

    private fun show(state: TargetState): Pi {
        val pi = Pi(listOf(state))
        val repo = TargetsRepository(
            pi,
            { Requester("me", "My phone") },
            InMemoryTargetColours(),
            scope,
            words = ContextTargetWords(context),
        )
        compose.setContent { TotumTheme { RemoteQueue(repo, "lg-tv") } }
        runBlocking { repo.refresh("test") }
        compose.waitUntil(WAIT_MS) { repo.targets.value.isNotEmpty() }
        compose.waitForIdle()
        hold()
        return pi
    }

    private fun hold() {
        val ms = InstrumentationRegistry.getArguments().getString("holdMs")?.toLongOrNull() ?: 0
        if (ms > 0) Thread.sleep(ms)
    }

    @Test
    fun theSeekRowSendsASeekFromWhereTheTargetIs() {
        val pi = show(tv)

        compose.onNodeWithContentDescription(context.getString(R.string.target_seek_back)).performClick()
        compose.onNodeWithContentDescription(context.getString(R.string.target_seek_forward)).performClick()
        compose.waitUntil(WAIT_MS) { pi.controls.size == 2 }

        assertEquals(listOf(TargetAction.SEEK, TargetAction.SEEK), pi.controls.map { it.action })
        assertEquals(listOf(115_000L, 155_000L), pi.controls.map { it.positionMs })
    }

    @Test
    fun tappingThePlayingRowDoesNotRestartItAndAnotherRowJumpsByItsId() {
        val pi = show(tv)

        compose.onNodeWithText("Episode one").performClick()
        Thread.sleep(SETTLE_MS)
        assertTrue("the playing row sent ${pi.controls}", pi.controls.isEmpty())

        compose.onNodeWithText("Episode two").performClick()
        compose.waitUntil(WAIT_MS) { pi.controls.isNotEmpty() }

        assertEquals(TargetAction.JUMP, pi.controls.single().action)
        assertEquals("b", pi.controls.single().itemId)
        assertEquals(1, pi.controls.single().index)
    }

    @Test
    fun aPausedDeckPlaysWhenItsCurrentRowIsTapped() {
        val pi = show(tv.copy(playing = false))

        compose.onNodeWithText("Episode one").performClick()
        compose.waitUntil(WAIT_MS) { pi.controls.isNotEmpty() }

        assertEquals(TargetAction.PLAY, pi.controls.single().action)
    }

    @Test
    fun theProblemIsInThePhonesOwnWords() {
        show(tv.copy(playing = false, trouble = TargetTrouble(TroubleKind.NOT_CONNECTED), problem = "plain Pi text"))

        compose.onNodeWithText("📶 Couldn't reach LG TV: it isn't connected (off or out of range)").assertIsDisplayed()
    }

    private companion object {
        const val WAIT_MS = 10_000L
        const val SETTLE_MS = 600L
    }
}
