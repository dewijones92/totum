package com.dewijones92.totum.cli.serve.cast

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

internal sealed interface CastEvent {
    data class Ready(val name: String?, val model: String?) : CastEvent
    data class Status(
        val state: String?,
        val idle: String?,
        val positionMs: Long,
        val durationMs: Long?,
        val contentId: String? = null,
    ) : CastEvent
    data class Volume(val percent: Int) : CastEvent
    data class Failure(val message: String) : CastEvent
}

internal object CastWire {

    fun load(url: String, contentType: String, title: String, startAtMs: Long): String = line(
        "load",
        "url" to JsonPrimitive(url),
        "type" to JsonPrimitive(contentType),
        "title" to JsonPrimitive(title),
        "startMs" to JsonPrimitive(startAtMs),
    )

    fun pause(paused: Boolean): String = line(if (paused) "pause" else "resume")

    fun seek(positionMs: Long): String = line("seek", "positionMs" to JsonPrimitive(positionMs))

    fun volume(percent: Int): String = line("volume", "percent" to JsonPrimitive(percent))

    fun stop(): String = line("stop")

    fun quit(): String = line("quit")

    fun parse(text: String): CastEvent? {
        val obj = runCatching { Json.parseToJsonElement(text).jsonObject }.getOrNull() ?: return null
        return when (obj.text("event")) {
            "ready" -> CastEvent.Ready(obj.text("name"), obj.text("model"))
            "status" -> CastEvent.Status(
                obj.text("state"),
                obj.text("idle"),
                obj["positionMs"]?.jsonPrimitive?.longOrNull ?: 0,
                obj["durationMs"]?.takeIf { it !is JsonNull }?.jsonPrimitive?.longOrNull,
                obj.text("contentId"),
            )
            "volume" -> obj["percent"]?.jsonPrimitive?.longOrNull?.let { CastEvent.Volume(it.toInt()) }
            "error" -> CastEvent.Failure(obj.text("message") ?: "unknown")
            else -> null
        }
    }

    private fun JsonObject.text(key: String): String? =
        this[key]?.takeIf { it !is JsonNull }?.jsonPrimitive?.contentOrNull

    private fun line(cmd: String, vararg more: Pair<String, JsonPrimitive>): String = buildJsonObject {
        put("cmd", JsonPrimitive(cmd))
        more.forEach { (key, value) -> put(key, value) }
    }.toString()
}

internal class CastReading(val positionMs: Long, val durationMs: Long?, val playing: Boolean, val atMs: Long) {
    fun positionAt(now: Long): Long = if (playing) positionMs + (now - atMs).coerceAtLeast(0) else positionMs
}
