package com.dewijones92.totum.cli.serve.ai

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

@Serializable
data class Completion(
    val caller: String,
    val what: String,
    val system: String,
    val message: String,
    val schema: String,
    val tools: String = "",
    val version: String? = null,
    val requestId: String? = null,
    val meta: Map<String, String> = emptyMap(),
    val files: Map<String, String> = emptyMap(),
)

fun callerProblem(caller: String, what: String): String? = when {
    !CALLER_TAG.matches(
        caller
    ) -> "caller must be a tag of 2 to 40 lowercase letters, digits or dashes, got \"$caller\""
    what.isBlank() -> "what must say what the call is for"
    else -> null
}

private val CALLER_TAG = Regex("[a-z0-9][a-z0-9-]{1,39}")

@Serializable
data class Completed(val structured: JsonObject, val toolCalls: Int = 0, val denied: Int = 0)

@Serializable
data class ProblemReport(
    val caller: String,
    val what: String,
    val problem: String,
    val version: String? = null,
    val requestId: String? = null,
)

internal interface Completer {
    suspend fun complete(completion: Completion): JsonObject

    suspend fun completeDetailed(completion: Completion): Completed = Completed(complete(completion))

    suspend fun report(problem: ProblemReport) = Unit
}

@Serializable
data class UsageRecord(
    val atMs: Long,
    val caller: String,
    val what: String,
    val ok: Boolean,
    val tookMs: Long,
    val turns: Long? = null,
    val costUsd: Double? = null,
    val inputTokens: Long? = null,
    val outputTokens: Long? = null,
    val error: String? = null,
    val version: String? = null,
    val requestId: String? = null,
    val meta: Map<String, String> = emptyMap(),
    val toolCalls: Long? = null,
)
