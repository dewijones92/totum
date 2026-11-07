package com.dewijones92.totum.ui.ask

import com.dewijones92.totum.data.ask.AskAnswer
import com.dewijones92.totum.data.ask.AskCopies
import com.dewijones92.totum.data.ask.AskKind
import com.dewijones92.totum.data.ask.AskOutcome
import com.dewijones92.totum.data.ask.AskRequest
import com.dewijones92.totum.data.ask.AskService
import com.dewijones92.totum.data.ask.Suggestion
import com.dewijones92.totum.data.ask.VetAnswer
import com.dewijones92.totum.data.ask.VetRequest
import com.dewijones92.totum.data.notforme.NotForMe
import com.dewijones92.totum.domain.MediaItem
import com.dewijones92.totum.domain.MediaItemId
import com.dewijones92.totum.domain.SourceId
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
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class AskNotForMeTest {

    private val dispatcher = StandardTestDispatcher()

    @Before fun setMainDispatcher() = Dispatchers.setMain(dispatcher)

    @After fun resetMainDispatcher() = Dispatchers.resetMain()

    private val zog = Suggestion(AskKind.FILM, "Zog", 2018, "dragons", "Zog 2018")
    private val stickMan = Suggestion(AskKind.FILM, "Stick Man", 2015, "sticks", "Stick Man 2015")

    private class Service(val answer: AskAnswer) : AskService {
        val asked = mutableListOf<AskRequest>()

        override suspend fun ask(request: AskRequest, progress: (Long) -> Unit): AskOutcome<AskAnswer> {
            asked += request
            return AskOutcome.Answered(answer, 5)
        }

        override suspend fun vet(request: VetRequest, progress: (Long) -> Unit): AskOutcome<VetAnswer> =
            AskOutcome.Answered(VetAnswer(emptyList()), 1)
    }

    private fun suppressed(title: String) =
        MediaItem(MediaItemId("id-$title"), SourceId("ch"), title, publishedAt = null, duration = null)

    @Test
    fun `a suggestion the person marked not for me never reaches the cards, and the others stay`() =
        runTest(dispatcher) {
            val notForMe = NotForMe()
            notForMe.suppress(suppressed("Zog (2018)"), "test")
            val service = Service(AskAnswer(listOf(zog, stickMan)))
            val vm =
                AskViewModel(service, AskCopies({ emptyList() }), { emptyList() }, Operations(), notForMe = notForMe)

            vm.ask("a Gruffalo film", "typed")
            advanceUntilIdle()

            val cards = vm.uiState.value.turns.single().cards
            assertEquals(listOf("Stick Man"), cards.map { it.suggestion.title })
        }

    @Test
    fun `what is not for me is told to the model as already seen so it picks something else`() =
        runTest(dispatcher) {
            val notForMe = NotForMe()
            notForMe.suppress(suppressed("Zog (2018)"), "test")
            val service = Service(AskAnswer(listOf(stickMan)))
            val vm = AskViewModel(
                service,
                AskCopies({ emptyList() }),
                { listOf("The Gruffalo") },
                Operations(),
                notForMe = notForMe,
            )

            vm.ask("a Gruffalo film", "typed")
            advanceUntilIdle()

            assertEquals(listOf("Zog (2018)", "The Gruffalo"), service.asked.single().have)
        }

    @Test
    fun `nothing is dropped when nothing is marked`() = runTest(dispatcher) {
        val service = Service(AskAnswer(listOf(zog, stickMan)))
        val vm = AskViewModel(service, AskCopies({ emptyList() }), { emptyList() }, Operations())

        vm.ask("a Gruffalo film", "typed")
        advanceUntilIdle()

        assertTrue(vm.uiState.value.turns.single().cards.size == 2)
    }
}
