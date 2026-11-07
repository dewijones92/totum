package com.dewijones92.totum.cli.serve.ai

import com.dewijones92.totum.data.ask.AskJson
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

internal data class ClaudeRun(
    val result: JsonObject,
    val toolCalls: Int = 0,
    val denied: Int = 0,
    val commands: List<String> = emptyList(),
)

internal object ClaudeStream {

    fun parse(stdout: String): ClaudeRun? {
        runCatching { AskJson.parseToJsonElement(stdout).jsonObject }.getOrNull()?.let { return ClaudeRun(it) }
        var result: JsonObject? = null
        var calls = 0
        var denied = 0
        val commands = mutableListOf<String>()
        stdout.lineSequence().filter { it.isNotBlank() }.forEach { line ->
            val event = runCatching { AskJson.parseToJsonElement(line).jsonObject }.getOrNull() ?: return@forEach
            when (event["type"]?.jsonPrimitive?.content) {
                "result" -> result = event
                "assistant" -> toolUses(event).forEach { use ->
                    calls++
                    commands += (use["input"] as? JsonObject)?.get("command")?.jsonPrimitive?.content.orEmpty()
                }
                "system" -> if (event["subtype"]?.jsonPrimitive?.content == "permission_denied") denied++
            }
        }
        return result?.let { ClaudeRun(it, calls, denied, commands) }
    }

    private fun toolUses(event: JsonObject): List<JsonObject> {
        val content = (event["message"] as? JsonObject)?.get("content") as? JsonArray ?: return emptyList()
        return content.mapNotNull { block ->
            (block as? JsonObject)?.takeIf {
                it["type"]?.jsonPrimitive?.content == "tool_use" &&
                    it["name"]?.jsonPrimitive?.content != STRUCTURED_OUTPUT
            }
        }
    }

    private const val STRUCTURED_OUTPUT = "StructuredOutput"
}
