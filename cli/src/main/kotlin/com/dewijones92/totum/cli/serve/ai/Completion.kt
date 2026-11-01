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
)

@Serializable
data class Completed(val structured: JsonObject)

internal interface Completer {
    suspend fun complete(completion: Completion): JsonObject
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
)
