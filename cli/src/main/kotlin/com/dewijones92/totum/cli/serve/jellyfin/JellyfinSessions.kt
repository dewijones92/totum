package com.dewijones92.totum.cli.serve.jellyfin

import com.dewijones92.totum.cli.serve.filing.Jellyfin
import com.dewijones92.totum.cli.serve.filing.JellyfinReply
import com.dewijones92.totum.common.Diag
import com.dewijones92.totum.data.jellyfin.JELLYFIN_ID
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import java.io.IOException

@Suppress("LongParameterList")
internal class JellyfinSession(
    val id: String,
    val deviceId: String,
    val deviceName: String,
    val client: String,
    val controllable: Boolean,
    val nowPlayingId: String?,
    val nowPlayingName: String?,
    val positionMs: Long,
    val durationMs: Long?,
    val paused: Boolean,
    val volume: Int?,
    val lastActivity: String?,
) {
    override fun toString(): String =
        "session $id of $deviceName ($client) controllable=$controllable nowPlaying=${nowPlayingId ?: "nothing"} " +
            "paused=$paused at=${positionMs}ms volume=${volume ?: "unknown"}"
}

internal object JellyfinSessionParser {

    private const val TICKS_PER_MS = 10_000L

    fun parse(body: String): List<JellyfinSession>? {
        val array = runCatching { Json.parseToJsonElement(body) }.getOrNull() as? JsonArray ?: return null
        return array.mapNotNull { (it as? JsonObject)?.let(::sessionOf) }
    }

    private fun sessionOf(obj: JsonObject): JellyfinSession? {
        val id = obj.text("Id") ?: return null
        val state = obj["PlayState"] as? JsonObject
        val playing = obj["NowPlayingItem"] as? JsonObject
        return JellyfinSession(
            id = id,
            deviceId = obj.text("DeviceId").orEmpty(),
            deviceName = obj.text("DeviceName").orEmpty(),
            client = obj.text("Client").orEmpty(),
            controllable = obj.flag("SupportsMediaControl"),
            nowPlayingId = playing?.text("Id"),
            nowPlayingName = playing?.text("Name"),
            positionMs = (state?.number("PositionTicks") ?: 0L) / TICKS_PER_MS,
            durationMs = playing?.number("RunTimeTicks")?.div(TICKS_PER_MS)?.takeIf { it > 0 },
            paused = state?.flag("IsPaused") == true,
            volume = state?.number("VolumeLevel")?.toInt(),
            lastActivity = obj.text("LastActivityDate"),
        )
    }

    private fun JsonObject.text(key: String): String? =
        (this[key] as? JsonElement)?.takeIf { it !is JsonNull }?.jsonPrimitive?.contentOrNull?.ifBlank { null }

    private fun JsonObject.flag(key: String): Boolean =
        this[key]?.takeIf { it !is JsonNull }?.jsonPrimitive?.booleanOrNull == true

    private fun JsonObject.number(key: String): Long? =
        this[key]?.takeIf { it !is JsonNull }?.jsonPrimitive?.let { it.longOrNull ?: it.intOrNull?.toLong() }
}

internal class JellyfinSessionApi(private val jellyfin: Jellyfin) {

    fun sessions(): List<JellyfinSession> {
        val body = jellyfin.get("/Sessions") ?: throw IOException("Jellyfin did not answer GET /Sessions")
        return JellyfinSessionParser.parse(body)
            ?: throw IOException("Jellyfin's answer to GET /Sessions was not a session list: ${body.take(LOGGED)}")
    }

    fun play(sessionId: String, itemId: String, startAtMs: Long): JellyfinReply? {
        require(JELLYFIN_ID.matches(itemId)) { "not a Jellyfin item id" }
        return send(
            "play $itemId from ${startAtMs}ms",
            "/Sessions/$sessionId/Playing?playCommand=PlayNow&itemIds=$itemId" +
                "&startPositionTicks=${startAtMs.coerceAtLeast(0) * TICKS_PER_MS}",
        )
    }

    fun playstate(sessionId: String, command: String, seekToMs: Long? = null): JellyfinReply? = send(
        "$command${seekToMs?.let { " to ${it}ms" }.orEmpty()}",
        "/Sessions/$sessionId/Playing/$command" +
            (seekToMs?.let { "?seekPositionTicks=${it.coerceAtLeast(0) * TICKS_PER_MS}" }.orEmpty()),
    )

    fun setVolume(sessionId: String, percent: Int): JellyfinReply? {
        val level = percent.coerceIn(0, MAX_PERCENT)
        return send(
            "volume to $level%",
            "/Sessions/$sessionId/Command",
            """{"Name":"SetVolume","Arguments":{"Volume":"$level"}}""",
        )
    }

    private fun send(what: String, path: String, json: String = ""): JellyfinReply? {
        val reply = jellyfin.call(path, json, TAG)
        Diag.log(
            TAG,
            "Jellyfin $what -> ${reply?.code ?: "no answer"}" +
                if (reply != null && !reply.ok) ": ${reply.body.take(LOGGED)}" else "",
        )
        return reply
    }

    private companion object {
        const val TAG = "jellyfin-client"
        const val TICKS_PER_MS = 10_000L
        const val MAX_PERCENT = 100
        const val LOGGED = 200
    }
}

internal class JellyfinClient(val label: String, private val match: String) {

    val id: String = "jellyfin-" + label.lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-')

    fun matches(session: JellyfinSession): Boolean =
        session.deviceId == match || session.deviceName.contains(match, ignoreCase = true)

    fun best(sessions: List<JellyfinSession>): JellyfinSession? = sessions.filter(::matches)
        .sortedWith(compareByDescending<JellyfinSession> { it.controllable }.thenByDescending { it.lastActivity })
        .firstOrNull()

    override fun toString(): String = "$label (matches \"$match\")"

    companion object {
        fun fromSpec(spec: String?): List<JellyfinClient> = spec.orEmpty().split(';').mapNotNull { pair ->
            val parts = pair.split('=', limit = 2)
            val label = parts.first().trim()
            val match = (parts.getOrNull(1) ?: label).trim()
            if (label.isEmpty() || match.isEmpty()) null else JellyfinClient(label, match)
        }
    }
}

internal class SessionBoard(
    private val api: JellyfinSessionApi,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val refreshing = Any()

    @Volatile private var readAt = Long.MIN_VALUE

    @Volatile private var last: List<JellyfinSession> = emptyList()

    fun sessions(maxAgeMs: Long = FRESH_MS): List<JellyfinSession> {
        if (isFresh(maxAgeMs)) return last
        synchronized(refreshing) {
            if (isFresh(maxAgeMs)) return last
            last = api.sessions()
            readAt = clock()
            return last
        }
    }

    fun cached(): List<JellyfinSession> = last

    private fun isFresh(maxAgeMs: Long): Boolean = readAt != Long.MIN_VALUE && clock() - readAt <= maxAgeMs

    private companion object {
        const val FRESH_MS = 800L
    }
}
