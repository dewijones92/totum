package com.dewijones92.totum.cli.serve

import com.dewijones92.totum.common.Diag
import com.dewijones92.totum.data.ask.AskAnswer
import com.dewijones92.totum.data.ask.AskKind
import com.dewijones92.totum.data.ask.AskRequest
import com.dewijones92.totum.data.ask.AskSource
import com.dewijones92.totum.data.ask.AskTrace
import com.dewijones92.totum.data.ask.AskTurn
import com.dewijones92.totum.data.ask.Suggestion
import com.dewijones92.totum.data.ask.Taste
import com.dewijones92.totum.data.ask.tidied
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope

internal sealed interface AskPlan {
    data object Web : AskPlan
    data class App(val question: String, val note: String? = null) : AskPlan
    data class Both(val appQuestion: String, val webQuestion: String) : AskPlan
}

internal object AskRouting {

    const val DEPENDENT_NOTE =
        "ℹ️ The web part of that needs facts from your own data. I keep your data away from the web, so I " +
            "answered only the part about your app; ask the web part again with the details typed in."

    const val NO_SNAPSHOT_NOTE = "ℹ️ Your app's data was not available this time, so only the web part was answered."

    fun plan(decision: RouteDecision, hasState: Boolean, latest: String): AskPlan = when {
        decision.route == AskSource.WEB -> AskPlan.Web
        decision.route == AskSource.APP -> AskPlan.App(latest)
        decision.webNeedsAppData -> AskPlan.App(decision.appQuestion.ifBlank { latest }, DEPENDENT_NOTE)
        !hasState -> AskPlan.Web
        decision.appQuestion.isBlank() -> AskPlan.Web
        decision.webQuestion.isBlank() -> AskPlan.App(decision.appQuestion)
        else -> AskPlan.Both(decision.appQuestion, decision.webQuestion)
    }

    fun forWeb(earlier: List<AskTurn>): List<AskTurn> = earlier.map { turn ->
        if (turn.source == AskSource.WEB) turn else AskTurn(turn.request, source = turn.source)
    }

    fun forApp(earlier: List<AskTurn>): List<AskTurn> = earlier.map { turn ->
        if (turn.source == AskSource.APP) turn else AskTurn(turn.request, source = turn.source)
    }

    fun cardsOf(reply: AppReply, known: Set<String>): Pair<List<Suggestion>, Int> {
        var dropped = 0
        val suggestions = reply.cards.mapNotNull { card ->
            val ids = card.itemIds.distinct().filter { it in known }.take(MAX_IDS_PER_CARD)
            dropped += card.itemIds.distinct().size - ids.size
            ids.takeIf { it.isNotEmpty() }?.let { kept ->
                Suggestion(AskKind.APP, card.title.ifBlank { "🗂️" }, null, card.note, "", itemIds = kept)
            }
        }.take(MAX_CARDS)
        return suggestions to dropped
    }

    private const val MAX_IDS_PER_CARD = 30
    private const val MAX_CARDS = 5
}

internal class RoutedAsk(
    private val brain: Brain,
    private val states: AskStates,
    private val clock: () -> Long = System::currentTimeMillis,
) {

    suspend fun answer(request: AskRequest): AskAnswer {
        val stateId = request.stateId
        if (stateId == null) {
            Diag.log("ask", "no snapshot with this ask: answering from the web only, without routing")
            return brain.suggest(webRequest(request, request.turns.last().request, withTaste = true))
        }
        val began = clock()
        val state = states.get(stateId)
        val decision = brain.route(request.turns)
        val routeMs = clock() - began
        val plan = AskRouting.plan(decision, state != null, request.turns.last().request)
        Diag.log(
            "ask",
            "routed \"${request.turns.last().request.take(LOGGED)}\" to ${decision.route} in ${routeMs}ms " +
                "(plan ${plan::class.simpleName}, webNeedsAppData=${decision.webNeedsAppData}, " +
                "snapshot ${if (state != null) "${state.bytes} bytes" else "MISSING"}): ${decision.why}",
        )
        if (state == null && plan is AskPlan.App) {
            throw BrainFailure("The snapshot of your app's data has expired; ask again")
        }
        val trace = AskTrace(route = decision.route.name.lowercase(), routeMs = routeMs, stateBytes = state?.bytes)
        return when (plan) {
            AskPlan.Web -> webOnly(request, trace, noSnapshot = state == null && decision.route != AskSource.WEB)
            is AskPlan.App -> appOnly(request, plan, requireNotNull(state), trace)
            is AskPlan.Both -> both(request, plan, requireNotNull(state), trace)
        }
    }

    private suspend fun webOnly(request: AskRequest, trace: AskTrace, noSnapshot: Boolean): AskAnswer {
        val began = clock()
        val web = brain.suggest(webRequest(request, request.turns.last().request, withTaste = true))
        val note = if (noSnapshot) AskRouting.NO_SNAPSHOT_NOTE else null
        return web.copy(
            source = AskSource.WEB,
            reply = listOfNotNull(web.reply.ifBlank { null }, note).joinToString("\n\n"),
            trace = trace.copy(webMs = clock() - began, note = note),
        )
    }

    private suspend fun appOnly(
        request: AskRequest,
        plan: AskPlan.App,
        state: StoredState,
        trace: AskTrace,
    ): AskAnswer {
        val began = clock()
        val reply = brain.answerFromApp(plan.question, AskRouting.forApp(request.turns.dropLast(1)), state)
        val took = clock() - began
        val (cards, dropped) = AskRouting.cardsOf(reply, state.itemIds)
        Diag.log(
            "ask",
            "app answer in ${took}ms: ${reply.toolCalls} jq call(s), ${reply.denied} refused, ${cards.size} card(s), " +
                "$dropped unknown id(s) dropped, reply ${reply.reply.length} chars",
        )
        return AskAnswer(
            suggestions = cards,
            reply = listOfNotNull(reply.reply.ifBlank { null }, plan.note).joinToString("\n\n"),
            source = AskSource.APP,
            trace = trace.copy(
                appMs = took,
                jqCalls = reply.toolCalls,
                jqDenied = reply.denied,
                note = plan.note?.let { "web part not answered: it needed the app's data" },
            ),
        ).tidied(Taste())
    }

    private suspend fun both(request: AskRequest, plan: AskPlan.Both, state: StoredState, trace: AskTrace): AskAnswer =
        coroutineScope {
            val app = async { runCatching { timed { appOnly(request, AskPlan.App(plan.appQuestion), state, trace) } } }
            val web = async {
                runCatching { timed { brain.suggest(webRequest(request, plan.webQuestion, withTaste = false)) } }
            }
            val (appResult, webResult) = app.await() to web.await()
            val appAnswer = appResult.getOrNull()
            val webAnswer = webResult.getOrNull()
            if (appAnswer == null && webAnswer == null) throw BrainFailure(failureOf(appResult) ?: "no answer")
            val parts = listOfNotNull(
                appAnswer?.value?.reply?.let { "🗂️ $it" }
                    ?: "⚠️ The part about your app failed: ${failureOf(appResult)}",
                webAnswer?.value?.reply?.let { "🌐 $it" } ?: "⚠️ The web part failed: ${failureOf(webResult)}",
            )
            AskAnswer(
                suggestions = appAnswer?.value?.suggestions.orEmpty() + webAnswer?.value?.suggestions.orEmpty(),
                reply = parts.joinToString("\n\n"),
                source = AskSource.BOTH,
                trace = appAnswer?.value?.trace
                    ?.copy(webMs = webAnswer?.elapsed, note = "answered as two separate calls")
                    ?: trace.copy(webMs = webAnswer?.elapsed),
            )
        }

    private class Timed<T>(val value: T, val elapsed: Long)

    private suspend fun <T> timed(block: suspend () -> T): Timed<T> {
        val began = clock()
        return Timed(block(), clock() - began)
    }

    private fun failureOf(result: Result<*>): String? = result.exceptionOrNull()?.message

    private fun webRequest(request: AskRequest, question: String, withTaste: Boolean): AskRequest = AskRequest(
        turns = AskRouting.forWeb(request.turns.dropLast(1)) + AskTurn(question),
        have = if (withTaste) request.have else emptyList(),
        taste = if (withTaste) request.taste else Taste(),
    )

    private companion object {
        const val LOGGED = 120
    }
}
