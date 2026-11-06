package com.dewijones92.totum.ui.ask

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.dewijones92.totum.common.Diag
import com.dewijones92.totum.data.ask.AskCard
import com.dewijones92.totum.data.ask.AskChatStore
import com.dewijones92.totum.data.ask.AskCopies
import com.dewijones92.totum.data.ask.AskKind
import com.dewijones92.totum.data.ask.AskOutcome
import com.dewijones92.totum.data.ask.AskRequest
import com.dewijones92.totum.data.ask.AskService
import com.dewijones92.totum.data.ask.AskTurn
import com.dewijones92.totum.data.ask.InMemoryAskChats
import com.dewijones92.totum.data.ask.SavedChat
import com.dewijones92.totum.data.ask.SavedTurn
import com.dewijones92.totum.data.ask.Taste
import com.dewijones92.totum.data.ask.keeping
import com.dewijones92.totum.data.ask.vetRequest
import com.dewijones92.totum.data.podcast.subscribedSources
import com.dewijones92.totum.di.AppContainer
import com.dewijones92.totum.domain.PlayableItem
import com.dewijones92.totum.operations.OperationEnd
import com.dewijones92.totum.operations.OperationKind
import com.dewijones92.totum.operations.Operations
import com.dewijones92.totum.stats.StatEvent
import kotlinx.coroutines.Job
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
    private val taste: suspend () -> Taste = { Taste() },
    private val latest: suspend (String) -> List<PlayableItem> = { emptyList() },
    private val chats: AskChatStore = InMemoryAskChats(),
    private val now: () -> Long = System::currentTimeMillis,
) : ViewModel() {

    enum class Stage { ASKING, SEARCHING, VETTING, DONE, FAILED }

    data class Turn(
        val request: String,
        val stage: Stage = Stage.ASKING,
        val reply: String = "",
        val cards: List<AskCard> = emptyList(),
        val error: String? = null,
        val tookMs: Long? = null,
    )

    data class UiState(
        val available: Boolean,
        val turns: List<Turn> = emptyList(),
        val recent: List<SavedChat> = emptyList(),
    ) {
        val busy: Boolean get() = turns.lastOrNull()?.stage?.let { it != Stage.DONE && it != Stage.FAILED } == true
    }

    private val state = MutableStateFlow(UiState(available = service != null))
    val uiState: StateFlow<UiState> = state.asStateFlow()
    private var chatId = newChatId()
    private var restoreJob: Job? = null

    init {
        val saved = chats.load()
        saved.firstOrNull()?.let { latest -> restore(latest, saved) } ?: state.update { it.copy(recent = saved) }
    }

    fun open(id: String) {
        if (state.value.busy) return
        val saved = chats.load()
        val chat = saved.firstOrNull { it.id == id } ?: return
        restore(chat, saved)
    }

    private fun restore(chat: SavedChat, saved: List<SavedChat>) {
        chatId = chat.id
        Diag.log("ask", "chat \"${chat.title}\" reopened with ${chat.turns.size} turn(s)")
        state.update {
            it.copy(
                turns = chat.turns.map { t -> Turn(t.request, Stage.DONE, t.reply, t.suggestions.map(::AskCard)) },
                recent = saved.filterNot { other -> other.id == chat.id },
            )
        }
        restoreJob?.cancel()
        val turnCount = chat.turns.size
        restoreJob = viewModelScope.launch {
            Diag.log("ask", "chat ${chat.id}: searching again for $turnCount restored turn(s)")
            repeat(turnCount) { search(it) }
            Diag.log("ask", "chat ${chat.id}: restored turns searched")
        }
    }

    fun forget(id: String): SavedChat? {
        val all = chats.load()
        val gone = all.firstOrNull { it.id == id } ?: return null
        val left = all.filterNot { it.id == id }
        chats.save(left)
        Diag.log("ask", "chat $id forgotten; ${left.size} kept")
        state.update { it.copy(recent = left.filterNot { chat -> chat.id == chatId }) }
        return gone
    }

    fun unforget(chat: SavedChat) {
        val all = chats.load().keeping(chat)
        chats.save(all)
        Diag.log("ask", "chat ${chat.id} \"${chat.title}\" put back by undo; ${all.size} kept")
        state.update { it.copy(recent = all.filterNot { other -> other.id == chatId }) }
    }

    private fun keep() {
        val turns = state.value.turns.filter { it.stage == Stage.DONE }
        if (turns.isEmpty()) return
        val chat =
            SavedChat(chatId, now(), turns.map { SavedTurn(it.request, it.reply, it.cards.map { c -> c.suggestion }) })
        chats.save(chats.load().keeping(chat))
    }

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
            try {
                val end = askAndFind(askService, request, earlier)
                if (state.value.busy) {
                    val why = (end as? OperationEnd.Failed)?.why ?: "No answer"
                    updateLast { it.copy(stage = Stage.FAILED, error = "❌ $why") }
                }
            } finally {
                if (state.value.busy) {
                    Diag.log("ask", "\"$request\" stopped before it finished")
                    updateLast { it.copy(stage = Stage.FAILED, error = "⏹️ Stopped") }
                }
            }
        }
    }

    private suspend fun askAndFind(askService: AskService, request: String, earlier: List<Turn>): OperationEnd =
        operations.track("ask:$request", OperationKind.ASK, request) { progress ->
            progress.report(step = 1, steps = STEPS)
            val turns = earlier.map { turn ->
                AskTurn(turn.request, turn.cards.map { it.suggestion.label }, turn.reply)
            } + AskTurn(request)
            val owned = runCatching { have() }.getOrDefault(emptyList())
            val known = runCatching { taste() }.getOrElse {
                Diag.warn("ask", "taste not gathered, asking without it: $it")
                Taste()
            }
            Diag.log(
                "ask",
                "taste sent: ${known.channels.size} channels, ${known.shows.size} shows, " +
                    "${known.artists.size} artists, ${known.albums.size} albums, " +
                    "${known.songs.size} songs, ${known.played.size} played",
            )
            when (val answer = askService.ask(AskRequest(turns, owned, known))) {
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
                    Diag.log("ask", "reply: \"${answer.value.reply}\"")
                    updateLast {
                        it.copy(
                            stage = Stage.SEARCHING,
                            reply = answer.value.reply,
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
            keep()
            Diag.log(
                "ask",
                "\"$request\" done: " + cards.joinToString("; ") {
                    "${it.suggestion.label} -> ${it.best?.title ?: "nothing"}"
                }
            )
            OperationEnd.Done(cards.count { it.best != null })
        }

    fun startOver() {
        if (state.value.busy) return
        Diag.log(
            "ask",
            "new chat after ${state.value.turns.size} turn(s); restore search running=${restoreJob?.isActive == true}"
        )
        restoreJob?.cancel()
        restoreJob = null
        chatId = newChatId()
        state.update { it.copy(turns = emptyList(), recent = chats.load()) }
    }

    private fun newChatId(): String = "chat-${now()}-${(0..CHAT_ID_RANGE).random()}"

    private suspend fun search(turn: Int = state.value.turns.lastIndex) {
        val cards = state.value.turns.getOrNull(turn)?.cards ?: run {
            Diag.warn("ask", "turn $turn is gone (${state.value.turns.size} turn(s) now); nothing to search")
            return
        }
        kotlinx.coroutines.coroutineScope {
            cards.mapIndexed { index, card ->
                async {
                    if (card.suggestion.kind == AskKind.LATEST) {
                        val items = latest(card.suggestion.searchWords.ifBlank { card.suggestion.title })
                        updateCard(turn, index) { it.copy(items = items, searched = true) }
                    } else {
                        val found = copies.find(card.suggestion)
                        updateCard(turn, index) { it.copy(copies = found, searched = true) }
                    }
                }
            }.awaitAll()
        }
    }

    private suspend fun vet(askService: AskService) {
        updateLast { it.copy(stage = Stage.VETTING) }
        val cards = state.value.turns.last().cards
        val withCopies = cards.indices.filter { cards[it].copies.isNotEmpty() && cards[it].suggestion.kind.vetted }
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
                val last = state.value.turns.lastIndex
                withCopies.forEachIndexed { item, index -> updateCard(last, index) { it.judged(vetted.value, item) } }
            }
        }
    }

    private fun updateLast(change: (Turn) -> Turn) =
        state.update { s -> s.copy(turns = s.turns.dropLast(1) + change(s.turns.last())) }

    private fun updateCard(turn: Int, index: Int, change: (AskCard) -> AskCard) = state.update { s ->
        s.copy(
            turns = s.turns.mapIndexed { t, shown ->
                if (t != turn) {
                    shown
                } else {
                    shown.copy(
                        cards = shown.cards.mapIndexed { i, card -> if (i == index) change(card) else card }
                    )
                }
            },
        )
    }

    companion object {
        private const val STEPS = 3
        private const val CHAT_ID_RANGE = 1_000_000
        private const val OWNED_TITLES = 200

        fun factory(container: AppContainer): ViewModelProvider.Factory = viewModelFactory {
            initializer {
                AskViewModel(
                    service = container.askService,
                    copies = container.askCopies,
                    have = { ownedTitles(container) },
                    operations = container.operations,
                    taste = { tasteFor(container) },
                    latest = container.askLatest::find,
                    chats = container.askChats,
                )
            }
        }

        private suspend fun tasteFor(container: AppContainer): Taste {
            container.musicFavourites.loadOnce()
            val shelf = container.musicFavourites.shelf.value
            return tasteOf(
                sources = subscribedSources(
                    container.podcastRepository,
                    container.accountSubscriptions.channels
                ).first(),
                artists = shelf.artists.map { it.name },
                albums = shelf.albums.map { listOfNotNull(it.title, it.artist).joinToString(" — ") },
                listens = container.statsLog.all().filterIsInstance<StatEvent.Listen>(),
                now = System.currentTimeMillis(),
            )
        }

        private suspend fun ownedTitles(container: AppContainer): List<String> {
            val downloaded = container.downloadManager.observeDownloaded().first().map { it.playable.item.title }
            val played = container.statsLog.all().filterIsInstance<StatEvent.Listen>()
                .sortedByDescending { it.at }.map { it.title }
            return (downloaded + played).distinct().take(OWNED_TITLES)
        }
    }
}
