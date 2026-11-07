package com.dewijones92.totum.cli.serve

import com.dewijones92.totum.cli.serve.filing.FilePlan
import com.dewijones92.totum.cli.serve.filing.FileRequest
import com.dewijones92.totum.data.ask.AskAnswer
import com.dewijones92.totum.data.ask.AskRequest
import com.dewijones92.totum.data.ask.AskSource
import com.dewijones92.totum.data.ask.AskTurn
import com.dewijones92.totum.data.ask.VetAnswer
import com.dewijones92.totum.data.ask.VetRequest
import kotlinx.serialization.Serializable

internal interface Brain {
    suspend fun suggest(request: AskRequest): AskAnswer
    suspend fun vet(request: VetRequest): VetAnswer
    suspend fun file(request: FileRequest): FilePlan = throw BrainFailure("this brain does not file")
    suspend fun refused(what: String, why: String) = Unit
    suspend fun route(turns: List<AskTurn>): RouteDecision = throw BrainFailure("this brain does not route")
    suspend fun answerFromApp(question: String, earlier: List<AskTurn>, state: StoredState): AppReply =
        throw BrainFailure("this brain cannot read the app's data")
}

internal const val STATE_FILE = "state.json"

@Serializable
internal data class RouteAnswer(
    val route: AskSource,
    val appQuestion: String = "",
    val webQuestion: String = "",
    val webNeedsAppData: Boolean = false,
    val why: String = "",
)

internal data class RouteDecision(
    val route: AskSource,
    val appQuestion: String,
    val webQuestion: String,
    val webNeedsAppData: Boolean,
    val why: String,
)

@Serializable
internal data class AppCard(val title: String, val note: String = "", val itemIds: List<String> = emptyList())

@Serializable
internal data class StateAnswer(val reply: String, val cards: List<AppCard> = emptyList())

internal data class AppReply(val reply: String, val cards: List<AppCard>, val toolCalls: Int, val denied: Int)

internal open class BrainFailure(message: String, cause: Throwable? = null) : Exception(message, cause)

internal open class NotRetried(message: String) : BrainFailure(message)

internal class CallerRefused(message: String) : NotRetried(message)

internal class NotSignedIn : NotRetried("The home server isn't signed in to Claude yet")
