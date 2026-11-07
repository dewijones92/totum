package com.dewijones92.totum.ui.ask

import com.dewijones92.totum.data.ask.AskAnswer
import com.dewijones92.totum.data.ask.AskCopies
import com.dewijones92.totum.data.ask.AskKind
import com.dewijones92.totum.data.ask.AskOutcome
import com.dewijones92.totum.data.ask.AskRequest
import com.dewijones92.totum.data.ask.AskService
import com.dewijones92.totum.data.ask.AskSource
import com.dewijones92.totum.data.ask.InMemoryAskChats
import com.dewijones92.totum.data.ask.Suggestion
import com.dewijones92.totum.data.ask.VetAnswer
import com.dewijones92.totum.data.ask.VetRequest
import com.dewijones92.totum.data.ask.state.StateReceipt
import com.dewijones92.totum.data.backup.Backup
import com.dewijones92.totum.data.queue.QueueEntry
import com.dewijones92.totum.data.queue.QueueSnapshot
import com.dewijones92.totum.domain.MediaItem
import com.dewijones92.totum.domain.MediaItemId
import com.dewijones92.totum.domain.SourceId
import com.dewijones92.totum.domain.asPlayable
import com.dewijones92.totum.operations.Operations
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.time.ZoneId

@OptIn(ExperimentalCoroutinesApi::class)
class AskAppStateViewModelTest {

    private val dispatcher = StandardTestDispatcher()

    @Before fun setMainDispatcher() = Dispatchers.setMain(dispatcher)

    @After fun resetMainDispatcher() = Dispatchers.resetMain()

    private val video = MediaItem(MediaItemId("v1"), SourceId("c"), "A video", publishedAt = null, duration = null)
        .asPlayable()

    private class FakeService : AskService {
        val asked = mutableListOf<AskRequest>()
        val uploads = mutableListOf<Pair<String, Int>>()
        var uploadFails = false
        var answer: AskOutcome<AskAnswer> = AskOutcome.Failed("unset")

        override suspend fun ask(request: AskRequest, progress: (Long) -> Unit): AskOutcome<AskAnswer> {
            asked += request
            return answer
        }

        override suspend fun vet(request: VetRequest, progress: (Long) -> Unit): AskOutcome<VetAnswer> =
            AskOutcome.Answered(VetAnswer(emptyList()), 1)

        override suspend fun uploadState(id: String, packed: ByteArray): AskOutcome<StateReceipt> {
            uploads += id to packed.size
            return if (uploadFails) {
                AskOutcome.Failed("❌ no")
            } else {
                AskOutcome.Answered(StateReceipt(true, 10, 600_000), 5)
            }
        }
    }

    private val service = FakeService()

    private val source = AskStateSource(
        AskStatePorts(
            backup = { Backup() },
            queue = { QueueSnapshot(listOf(QueueEntry(video))) },
            downloaded = { emptyList() },
            records = { emptyList() },
            locked = { emptySet() },
            stats = { emptyList() },
            trail = { emptyList<com.dewijones92.totum.domain.PlayableItem>() to -1 },
            channels = { emptyList() },
            appVersion = "test",
            starredName = "⭐ Starred",
        ),
        zone = { ZoneId.of("UTC") },
        work = dispatcher,
    )

    private fun viewModel(chats: InMemoryAskChats = InMemoryAskChats()) = AskViewModel(
        service,
        AskCopies({ emptyList() }),
        { emptyList() },
        Operations(),
        chats = chats,
        appState = source,
    )

    private val appAnswer = AskAnswer(
        listOf(Suggestion(AskKind.APP, "Unplayed", null, "one of them", "", itemIds = listOf("v1", "ghost"))),
        reply = "You have 1.",
        source = AskSource.APP,
    )

    @Test
    fun `a fresh snapshot is sent before every ask and its id travels with the question`() = runTest(dispatcher) {
        service.answer = AskOutcome.Answered(appAnswer, 5)
        val vm = viewModel()
        vm.ask("how many are unplayed?", "typed")
        advanceUntilIdle()
        vm.ask("and the longest?", "typed")
        advanceUntilIdle()

        assertEquals(2, service.uploads.size)
        assertEquals(service.uploads.map { it.first }, service.asked.map { it.stateId })
        assertTrue("each ask gets its own snapshot", service.uploads[0].first != service.uploads[1].first)
        assertTrue(service.uploads.all { it.second > 0 })
    }

    @Test
    fun `an app answer shows its source and a card made of the phone's own items, unknown ids left out`() =
        runTest(dispatcher) {
            service.answer = AskOutcome.Answered(appAnswer, 5)
            val vm = viewModel()
            vm.ask("how many are unplayed?", "typed")
            advanceUntilIdle()
            val turn = vm.uiState.value.turns.single()
            assertEquals(AskViewModel.Stage.DONE, turn.stage)
            assertEquals(AskSource.APP, turn.source)
            assertEquals("You have 1.", turn.reply)
            assertEquals(listOf(video), turn.cards.single().items)
            assertTrue(turn.cards.single().searched)
        }

    @Test
    fun `the source of earlier turns goes back to the home server so it can keep the halves apart`() =
        runTest(dispatcher) {
            service.answer = AskOutcome.Answered(appAnswer, 5)
            val vm = viewModel()
            vm.ask("how many are unplayed?", "typed")
            advanceUntilIdle()
            vm.ask("and the longest?", "typed")
            advanceUntilIdle()
            assertEquals(listOf(AskSource.APP, AskSource.WEB), service.asked.last().turns.map { it.source })
        }

    @Test
    fun `when the snapshot cannot be sent the ask goes on without it and the reply says so`() = runTest(dispatcher) {
        service.uploadFails = true
        service.answer = AskOutcome.Answered(AskAnswer(emptyList(), reply = "From the web"), 5)
        val vm = viewModel()
        vm.ask("how many are unplayed?", "typed")
        advanceUntilIdle()
        assertNull(service.asked.single().stateId)
        val turn = vm.uiState.value.turns.single()
        assertEquals(AskViewModel.Stage.DONE, turn.stage)
        assertTrue(turn.reply.startsWith("From the web"))
        assertTrue(turn.reply.endsWith(AskViewModel.NO_DATA_NOTE))
    }

    @Test
    fun `a saved app answer comes back with its cards found again on this phone`() = runTest(dispatcher) {
        val chats = InMemoryAskChats()
        service.answer = AskOutcome.Answered(appAnswer, 5)
        viewModel(chats).ask("how many are unplayed?", "typed")
        advanceUntilIdle()
        assertEquals(AskSource.APP, chats.load().single().turns.single().source)

        val reopened = viewModel(chats)
        advanceUntilIdle()
        val turn = reopened.uiState.value.turns.single()
        assertEquals(AskSource.APP, turn.source)
        assertNotNull(turn.cards.single().items.firstOrNull())
        assertEquals(listOf(video), turn.cards.single().items)
    }
}
