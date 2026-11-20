package com.dewijones92.totum.ui

import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.dewijones92.totum.data.home.HomeAnswer
import com.dewijones92.totum.data.home.TargetsApi
import com.dewijones92.totum.data.targets.Requester
import com.dewijones92.totum.data.targets.Target
import com.dewijones92.totum.data.targets.TargetControl
import com.dewijones92.totum.data.targets.TargetKind
import com.dewijones92.totum.data.targets.TargetQueueRequest
import com.dewijones92.totum.data.targets.TargetState
import com.dewijones92.totum.data.targets.TargetsAnswer
import com.dewijones92.totum.targets.InMemoryPlayOn
import com.dewijones92.totum.targets.InMemoryTargetColours
import com.dewijones92.totum.targets.TargetsRepository
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.util.concurrent.ConcurrentLinkedQueue

class StateReachesComposeOnTheMainThreadTest {

    @get:Rule
    val compose = createComposeRule()

    private val scope = mainThreadScope()

    @After
    fun stop() = scope.cancel()

    private class Pi : TargetsApi {
        @Volatile var down = false
        override suspend fun targets(): HomeAnswer<TargetsAnswer> = if (down) {
            HomeAnswer.No("down")
        } else {
            HomeAnswer.Ok(TargetsAnswer(listOf(TargetState(Target("bar", "Soundbar", TargetKind.SPEAKER))), null))
        }
        override suspend fun queue(id: String, request: TargetQueueRequest): HomeAnswer<TargetState> =
            HomeAnswer.No("unused")
        override suspend fun control(id: String, control: TargetControl): HomeAnswer<TargetState> =
            HomeAnswer.No("unused")
    }

    @Test
    fun aRepositoryFlowIsCollectedAndComposedOnTheMainThreadWhateverThreadWritesIt() {
        val pi = Pi()
        val repo = TargetsRepository(
            pi,
            { Requester("me", "My phone") },
            InMemoryTargetColours(),
            scope,
            playOn = InMemoryPlayOn(),
            unmetered = { true },
        )
        val collectedOn = ConcurrentLinkedQueue<String>()
        val composedOn = ConcurrentLinkedQueue<String>()
        compose.setContent {
            val watched = remember(repo) {
                repo.homeUnreachable.onEach { collectedOn.add(Thread.currentThread().name) }
            }
            val unreachable by watched.collectAsStateWithLifecycle(false)
            composedOn.add(Thread.currentThread().name)
            Text(if (unreachable) "unreachable" else "reachable")
        }
        compose.waitForIdle()
        runBlocking { repo.refresh("test: seed") }
        pi.down = true
        runBlocking {
            repo.refresh("test: first failure")
            repo.refresh("test: second failure")
        }
        compose.waitUntil(WAIT_MS) { compose.showsUnreachable() }

        assertTrue("the collector never ran", collectedOn.isNotEmpty())
        assertEquals("threads the collector ran on", emptyList<String>(), collectedOn.filter { it != MAIN })
        assertEquals("threads the screen was composed on", emptyList<String>(), composedOn.filter { it != MAIN })
    }

    private fun androidx.compose.ui.test.junit4.ComposeContentTestRule.showsUnreachable(): Boolean =
        runCatching { onNodeWithText("unreachable").assertExists() }.isSuccess

    private companion object {
        const val MAIN = "main"
        const val WAIT_MS = 5_000L
    }
}
