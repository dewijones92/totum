package com.dewijones92.totum.ui.ask

import com.dewijones92.totum.common.Page
import com.dewijones92.totum.data.ask.AskAnswer
import com.dewijones92.totum.data.ask.AskCopies
import com.dewijones92.totum.data.ask.AskKind
import com.dewijones92.totum.data.ask.AskOutcome
import com.dewijones92.totum.data.ask.AskRequest
import com.dewijones92.totum.data.ask.AskService
import com.dewijones92.totum.data.ask.InMemoryAskChats
import com.dewijones92.totum.data.ask.Suggestion
import com.dewijones92.totum.data.ask.Taste
import com.dewijones92.totum.data.ask.Verdict
import com.dewijones92.totum.data.ask.VetAnswer
import com.dewijones92.totum.data.ask.VetRequest
import com.dewijones92.totum.data.search.SearchHit
import com.dewijones92.totum.data.search.SearchOutcome
import com.dewijones92.totum.data.search.SearchSource
import com.dewijones92.totum.data.torrent.TorrentLink
import com.dewijones92.totum.domain.MediaItem
import com.dewijones92.totum.domain.MediaItemId
import com.dewijones92.totum.domain.SourceId
import com.dewijones92.totum.domain.asPlayable
import com.dewijones92.totum.operations.OperationEnd
import com.dewijones92.totum.operations.Operations
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class AskViewModelTest {

    private val dispatcher = StandardTestDispatcher()

    @Before fun setMainDispatcher() = Dispatchers.setMain(dispatcher)

    @After fun resetMainDispatcher() = Dispatchers.resetMain()

    private val zog = Suggestion(AskKind.FILM, "Zog", 2018, "dragons", "Zog 2018")
    private val stickMan = Suggestion(AskKind.FILM, "Stick Man", 2015, "sticks", "Stick Man 2015")

    private fun torrent(title: String) =
        SearchHit.Torrent(title, null, null, TorrentLink.Magnet("magnet:?xt=urn:btih:$title"), 9, 1, "TPB")

    private val torrents = SearchSource { query, _, _ ->
        SearchOutcome.Success(
            Page.last(
                if (query.value.startsWith(
                        "Zog"
                    )
                ) {
                    listOf(torrent("Zog 2 2020"), torrent("Zog 2018 1080p"))
                } else {
                    emptyList()
                }
            ),
        )
    }

    private class FakeService(var vetFails: Boolean = false) : AskService {
        val asked = mutableListOf<AskRequest>()
        val vetted = mutableListOf<VetRequest>()
        var answer: AskOutcome<AskAnswer> = AskOutcome.Failed("unset")

        override suspend fun ask(request: AskRequest, progress: (Long) -> Unit): AskOutcome<AskAnswer> {
            asked += request
            return answer
        }

        override suspend fun vet(request: VetRequest, progress: (Long) -> Unit): AskOutcome<VetAnswer> {
            vetted += request
            if (vetFails) return AskOutcome.Failed("❌ busy")
            return AskOutcome.Answered(VetAnswer(listOf(Verdict(0, 0, ok = false, reason = "the sequel"))), 10)
        }
    }

    private val service = FakeService()
    private val operations = Operations()

    private fun viewModel(owned: List<String> = emptyList()) =
        AskViewModel(service, AskCopies({ listOf(torrents) }), { owned }, operations)

    @Test
    fun `an ask is answered, searched, checked and its best copy picked`() = runTest(dispatcher) {
        service.answer = AskOutcome.Answered(AskAnswer(listOf(zog, stickMan)), 7_000)
        val vm = viewModel(owned = listOf("The Gruffalo"))
        val ended = mutableListOf<OperationEnd>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { operations.ended.collect { ended += it.end } }

        vm.ask("a Gruffalo film", "typed")
        advanceUntilIdle()

        val turn = vm.uiState.value.turns.single()
        assertEquals(AskViewModel.Stage.DONE, turn.stage)
        assertEquals(listOf("The Gruffalo"), service.asked.single().have)
        assertEquals("Zog 2018 1080p", turn.cards[0].best?.title)
        assertEquals(listOf("Zog 2 2020" to "the sequel"), turn.cards[0].rejected.map { it.first.title to it.second })
        assertEquals(null, turn.cards[1].best)
        assertEquals("only suggestions with copies are checked", 1, service.vetted.single().items.size)
        assertEquals(listOf(OperationEnd.Done(1)), ended)
    }

    @Test
    fun `a refinement carries the earlier turn and what it suggested`() = runTest(dispatcher) {
        service.answer = AskOutcome.Answered(AskAnswer(listOf(zog)), 1)
        val vm = viewModel()
        vm.ask("a Gruffalo film", "typed")
        advanceUntilIdle()
        vm.ask("something newer", "speech")
        advanceUntilIdle()

        val turns = service.asked.last().turns
        assertEquals(listOf("a Gruffalo film", "something newer"), turns.map { it.request })
        assertEquals(listOf("Zog (2018)"), turns.first().suggested)
    }

    @Test
    fun `the taste goes with the ask, the reply is kept and carried, latest is looked up and music is not vetted`() =
        runTest(dispatcher) {
            val radio = Suggestion(AskKind.RADIO, "Happy funk", null, "upbeat", "Zog seed; Zog other")
            val latest = Suggestion(AskKind.LATEST, "Bald and Bankrupt", null, "you follow him", "Bald and Bankrupt")
            service.answer = AskOutcome.Answered(AskAnswer(listOf(radio, latest), reply = "Funk and a vlog"), 1)
            val video = MediaItem(MediaItemId("v1"), SourceId("c"), "A vlog", publishedAt = null, duration = null)
                .asPlayable()
            val looked = mutableListOf<String>()
            val vm = AskViewModel(
                service,
                AskCopies({ listOf(torrents) }),
                { emptyList() },
                operations,
                taste = { Taste(channels = listOf("Bald and Bankrupt")) },
                latest = { name ->
                    looked += name
                    listOf(video)
                },
            )
            vm.ask("funk and my vlogs", "typed")
            advanceUntilIdle()
            val turn = vm.uiState.value.turns.single()
            assertEquals(listOf("Bald and Bankrupt"), service.asked.single().taste.channels)
            assertEquals("Funk and a vlog", turn.reply)
            assertEquals(listOf("Bald and Bankrupt"), looked)
            assertEquals(listOf(video), turn.cards[1].items)
            assertEquals(2, turn.cards[0].copies.size)
            assertTrue("music and latest are never sent to be vetted", service.vetted.isEmpty())

            vm.ask("more 70s", "typed")
            advanceUntilIdle()
            assertEquals("Funk and a vlog", service.asked.last().turns.first().reply)
        }

    @Test
    fun `a chat is kept, comes back when Ask opens again, and a new chat leaves it among the recent ones`() =
        runTest(dispatcher) {
            val chats = InMemoryAskChats()
            service.answer = AskOutcome.Answered(AskAnswer(listOf(zog), reply = "Dragons"), 1)
            val first =
                AskViewModel(service, AskCopies({ listOf(torrents) }), { emptyList() }, operations, chats = chats)
            first.ask("a dragon film", "typed")
            advanceUntilIdle()
            assertEquals(listOf("a dragon film"), chats.load().single().turns.map { it.request })

            val reopened =
                AskViewModel(service, AskCopies({ listOf(torrents) }), { emptyList() }, operations, chats = chats)
            advanceUntilIdle()
            val turn = reopened.uiState.value.turns.single()
            assertEquals("Dragons", turn.reply)
            assertEquals("its copies are looked up again", "Zog 2 2020", turn.cards.single().best?.title)

            reopened.startOver()
            assertTrue(reopened.uiState.value.turns.isEmpty())
            assertEquals(listOf("a dragon film"), reopened.uiState.value.recent.map { it.title })
            reopened.open(reopened.uiState.value.recent.single().id)
            advanceUntilIdle()
            assertEquals("a dragon film", reopened.uiState.value.turns.single().request)
        }

    @Test
    fun `a failed ask says why and a failed check keeps the app's order`() = runTest(dispatcher) {
        service.answer = AskOutcome.Failed("❌ The home server took too long")
        val vm = viewModel()
        vm.ask("anything", "typed")
        advanceUntilIdle()
        assertEquals(AskViewModel.Stage.FAILED, vm.uiState.value.turns.single().stage)
        assertEquals("❌ The home server took too long", vm.uiState.value.turns.single().error)

        service.answer = AskOutcome.Answered(AskAnswer(listOf(zog)), 1)
        service.vetFails = true
        vm.startOver()
        vm.ask("a dragon film", "typed")
        advanceUntilIdle()
        assertEquals("Zog 2 2020", vm.uiState.value.turns.single().cards.single().best?.title)
    }

    @Test
    fun `nothing is asked while an ask is running or without a home server`() = runTest(dispatcher) {
        service.answer = AskOutcome.Answered(AskAnswer(listOf(zog)), 1)
        val vm = viewModel()
        vm.ask("one", "typed")
        vm.ask("two", "typed")
        advanceUntilIdle()
        assertEquals(1, service.asked.size)

        val none = AskViewModel(null, AskCopies({ emptyList() }), { emptyList() }, operations)
        none.ask("three", "typed")
        assertTrue(none.uiState.value.turns.isEmpty())
        assertTrue(!none.uiState.value.available)
    }

    @Test
    fun `cancelling from the strip ends the ask, so the next one is not refused`() = runTest(dispatcher) {
        val gate = kotlinx.coroutines.CompletableDeferred<Unit>()
        val slow = object : AskService by service {
            override suspend fun ask(request: AskRequest, progress: (Long) -> Unit): AskOutcome<AskAnswer> {
                gate.await()
                return AskOutcome.Answered(AskAnswer(listOf(zog)), 1)
            }
        }
        val vm = AskViewModel(slow, AskCopies({ listOf(torrents) }), { emptyList() }, operations)
        vm.ask("first", "typed")
        advanceUntilIdle()
        operations.cancel(operations.running.value.single().id)
        advanceUntilIdle()
        assertEquals(AskViewModel.Stage.FAILED, vm.uiState.value.turns.single().stage)
        assertTrue(!vm.uiState.value.busy)
        vm.startOver()
        assertTrue(vm.uiState.value.turns.isEmpty())
    }
}
