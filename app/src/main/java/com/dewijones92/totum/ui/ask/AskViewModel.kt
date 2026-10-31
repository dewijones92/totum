package com.dewijones92.totum.ui.ask

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.dewijones92.totum.common.Diag
import com.dewijones92.totum.data.ask.AskCard
import com.dewijones92.totum.data.ask.AskCopies
import com.dewijones92.totum.data.ask.AskOutcome
import com.dewijones92.totum.data.ask.AskRequest
import com.dewijones92.totum.data.ask.AskService
import com.dewijones92.totum.data.ask.AskTurn
import com.dewijones92.totum.data.ask.vetRequest
import com.dewijones92.totum.di.AppContainer
import com.dewijones92.totum.operations.OperationEnd
import com.dewijones92.totum.operations.OperationKind
import com.dewijones92.totum.operations.Operations
import com.dewijones92.totum.stats.StatEvent
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class AskViewModel(
    private val service: AskService?,
    private val copies: AskCopies,
    private val have: suspend () -> List<String>,
    private val operations: Operations,
) : ViewModel() {

    enum class Stage { ASKING, SEARCHING, VETTING, DONE, FAILED }

    data class Turn(
        val request: String,
        val stage: Stage = Stage.ASKING,
        val cards: List<AskCard> = emptyList(),
        val error: String? = null,
        val tookMs: Long? = null,
    )

    data class UiState(val available: Boolean, val turns: List<Turn> = emptyList()) {
        val busy: Boolean get() = turns.lastOrNull()?.stage?.let { it != Stage.DONE && it != Stage.FAILED } == true
    }

    private val state = MutableStateFlow(UiState(available = service != null))
    val uiState: StateFlow<UiState> = state.asStateFlow()

    fun ask(text: String, from: String) {
        val request = text.trim()
        val askService = service
        if (request.isEmpty() || askService == null || state.value.busy) {
            Diag.log(
                "ask",
                "not asking \"$request\" from $from: service=${askService != null} busy=${state.value.busy}"
            )
            return
        }
        val earlier = state.value.turns.filter { it.stage == Stage.DONE }
        Diag.log("ask", "asking \"$request\" from $from after ${earlier.size} earlier turn(s)")
        state.update { it.copy(turns = it.turns + Turn(request)) }
        viewModelScope.launch {
            operations.track("ask:$request", OperationKind.ASK, request) { progress ->
                progress.report(step = 1, steps = STEPS)
                val turns = earlier.map { turn -> AskTurn(turn.request, turn.cards.map { it.suggestion.label }) } +
                    AskTurn(request)
                val owned = runCatching { have() }.getOrDefault(emptyList())
                when (val answer = askService.ask(AskRequest(turns, owned))) {
                    is AskOutcome.Failed -> {
                        updateLast { it.copy(stage = Stage.FAILED, error = answer.reason) }
                        return@track OperationEnd.Failed(answer.reason)
                    }
                    is AskOutcome.Answered -> {
                        val picks = answer.value.suggestions.joinToString("; ") { s ->
                            "${s.kind} ${s.label} [${s.searchWords}]"
                        }
                        Diag.log(
                            "ask",
                            "\"$request\" answered in ${answer.elapsedMs}ms, ${owned.size} owned title(s) sent: $picks",
                        )
                        updateLast {
                            it.copy(
                                stage = Stage.SEARCHING,
                                cards = answer.value.suggestions.map(::AskCard),
                                tookMs = answer.elapsedMs
                            )
                        }
                    }
                }
                progress.report(step = 2, steps = STEPS)
                search()
                progress.report(step = 3, steps = STEPS)
                vet(askService)
                val cards = state.value.turns.last().cards
                updateLast { it.copy(stage = Stage.DONE) }
                Diag.log(
                    "ask",
                    "\"$request\" done: " + cards.joinToString("; ") {
                        "${it.suggestion.label} -> ${it.best?.title ?: "nothing"}"
                    }
                )
                OperationEnd.Done(cards.count { it.best != null })
            }
        }
    }

    fun startOver() {
        if (state.value.busy) return
        Diag.log("ask", "started over after ${state.value.turns.size} turn(s)")
        state.update { it.copy(turns = emptyList()) }
    }

    private suspend fun search() {
        val cards = state.value.turns.last().cards
        kotlinx.coroutines.coroutineScope {
            cards.mapIndexed { index, card ->
                async {
                    val found = copies.find(card.suggestion)
                    updateCard(index) { it.copy(copies = found, searched = true) }
                }
            }.awaitAll()
        }
    }

    private suspend fun vet(askService: AskService) {
        updateLast { it.copy(stage = Stage.VETTING) }
        val cards = state.value.turns.last().cards
        val withCopies = cards.indices.filter { cards[it].copies.isNotEmpty() }
        if (withCopies.isEmpty()) {
            Diag.log("ask", "no copies found for any suggestion; nothing to check")
            return
        }
        when (val vetted = askService.vet(withCopies.map(cards::get).vetRequest())) {
            is AskOutcome.Failed -> Diag.warn(
                "ask",
                "copies not checked (${vetted.reason}); the app's own order stands"
            )
            is AskOutcome.Answered -> {
                Diag.log(
                    "ask",
                    "copies checked in ${vetted.elapsedMs}ms: ${vetted.value.verdicts.count { !it.ok }} rejected"
                )
                withCopies.forEachIndexed { item, index -> updateCard(index) { it.judged(vetted.value, item) } }
            }
        }
    }

    private fun updateLast(change: (Turn) -> Turn) =
        state.update { s -> s.copy(turns = s.turns.dropLast(1) + change(s.turns.last())) }

    private fun updateCard(index: Int, change: (AskCard) -> AskCard) = updateLast { turn ->
        turn.copy(cards = turn.cards.mapIndexed { i, card -> if (i == index) change(card) else card })
    }

    companion object {
        private const val STEPS = 3
        private const val OWNED_TITLES = 200

        fun factory(container: AppContainer): ViewModelProvider.Factory = viewModelFactory {
            initializer {
                AskViewModel(
                    service = container.askService,
                    copies = container.askCopies,
                    have = { ownedTitles(container) },
                    operations = container.operations,
                )
            }
        }

        private suspend fun ownedTitles(container: AppContainer): List<String> {
            val downloaded = container.downloadManager.observeDownloaded().first().map { it.playable.item.title }
            val played = container.statsLog.all().filterIsInstance<StatEvent.Listen>()
                .sortedByDescending { it.at }.map { it.title }
            return (downloaded + played).distinct().take(OWNED_TITLES)
        }
    }
}
