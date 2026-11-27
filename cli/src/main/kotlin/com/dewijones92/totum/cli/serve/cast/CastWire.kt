package com.dewijones92.totum.cli.serve.cast

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

internal sealed interface CastEvent {
    data class Ready(val name: String?, val model: String?, val video: Boolean? = null) : CastEvent
    data class Status(
        val state: String?,
        val idle: String?,
        val positionMs: Long,
        val durationMs: Long?,
        val contentId: String? = null,
    ) : CastEvent
    data class Volume(val percent: Int) : CastEvent
    data class Failure(val message: String) : CastEvent
    data class Handback(val quit: Boolean, val why: String) : CastEvent
    data class Note(val message: String) : CastEvent
    data class Connection(val state: String) : CastEvent
    data class Tracks(val tracks: List<CastTrack>, val active: List<Int>) : CastEvent
}

internal data class CastTrack(val id: Int, val language: String?, val name: String?)

@Suppress("TooManyFunctions")
internal object CastWire {

    const val STREAM_TYPE = "BUFFERED"

    fun load(url: String, contentType: String, title: String, startAtMs: Long): String = line(
        "load",
        "url" to JsonPrimitive(url),
        "type" to JsonPrimitive(contentType),
        "title" to JsonPrimitive(title),
        "startMs" to JsonPrimitive(startAtMs),
        "streamType" to JsonPrimitive(STREAM_TYPE),
    )

    fun pause(paused: Boolean): String = line(if (paused) "pause" else "resume")

    fun seek(positionMs: Long): String = line("seek", "positionMs" to JsonPrimitive(positionMs))

    fun volume(percent: Int): String = line("volume", "percent" to JsonPrimitive(percent))

    fun stop(): String = line("stop")

    fun captions(trackId: Int?): String =
        line("captions", "trackId" to (trackId?.let { JsonPrimitive(it) } ?: JsonNull))

    fun quit(): String = line("quit")

    fun release(): String = line("release")

    @Suppress("CyclomaticComplexMethod")
    fun parse(text: String): CastEvent? {
        val obj = runCatching { Json.parseToJsonElement(text).jsonObject }.getOrNull() ?: return null
        return when (obj.text("event")) {
            "ready" -> CastEvent.Ready(obj.text("name"), obj.text("model"), obj.text("video")?.toBooleanStrictOrNull())
            "status" -> CastEvent.Status(
                obj.text("state"),
                obj.text("idle"),
                obj["positionMs"]?.jsonPrimitive?.longOrNull ?: 0,
                obj["durationMs"]?.takeIf { it !is JsonNull }?.jsonPrimitive?.longOrNull,
                obj.text("contentId"),
            )
            "volume" -> obj["percent"]?.jsonPrimitive?.longOrNull?.let { CastEvent.Volume(it.toInt()) }
            "error" -> CastEvent.Failure(obj.text("message") ?: "unknown")
            "handback" -> CastEvent.Handback(obj.text("quit") == "true", obj.text("why") ?: "no reason given")
            "note" -> CastEvent.Note(obj.text("message") ?: "")
            "connection" -> CastEvent.Connection(obj.text("state") ?: "unknown")
            "tracks" -> CastEvent.Tracks(tracksOf(obj["tracks"]), activeOf(obj["active"]))
            else -> null
        }
    }

    private fun tracksOf(element: kotlinx.serialization.json.JsonElement?): List<CastTrack> =
        (element as? JsonArray).orEmpty().mapNotNull { entry ->
            val track = entry as? JsonObject ?: return@mapNotNull null
            val id = track["id"]?.jsonPrimitive?.longOrNull?.toInt() ?: return@mapNotNull null
            CastTrack(id, track.text("language"), track.text("name"))
        }

    private fun activeOf(element: kotlinx.serialization.json.JsonElement?): List<Int> =
        (element as? JsonArray).orEmpty().mapNotNull { it.jsonPrimitive.longOrNull?.toInt() }

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
