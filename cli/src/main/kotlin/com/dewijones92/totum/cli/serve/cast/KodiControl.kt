package com.dewijones92.totum.cli.serve.cast

import com.dewijones92.totum.common.Diag
import com.dewijones92.totum.domain.CastScreen
import com.dewijones92.totum.domain.VideoCodecFamily
import com.dewijones92.totum.playback.cast.CastFormat
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

@Suppress("TooManyFunctions", "LongParameterList")
internal class KodiControl(
    private val address: String,
    private val label: String,
    private val rpc: KodiRpc,
    private val clock: () -> Long = System::currentTimeMillis,
    private val pollMs: Long = POLL_MS,
    private val polls: Boolean = true,
    private val dashWhenAble: Boolean = false,
) : CastControl {

    override var onEnded: (failed: Boolean) -> Unit
        get() = watch.onEnded
        set(value) {
            watch.onEnded = value
        }

    override var onLost: (reason: String) -> Unit
        get() = watch.onLost
        set(value) {
            watch.onLost = value
        }

    override val keepsAddressesPrivate: Boolean get() = true

    @Volatile override var format: CastFormat = CastFormat.HLS
        private set

    @Volatile override var model: String? = null
        private set

    @Volatile override var screen: CastScreen? = null
        private set

    @Volatile private var connected = false

    @Volatile private var deviceVolume: Int? = null

    @Volatile private var playerId: Int? = null

    private val watch = RendererWatch(
        label,
        clock,
        pollMs,
        polls,
        look = ::look,
        seekNow = ::seekNow,
        extras = ::readVolume,
    )

    override suspend fun reachable(): Boolean {
        if (connected && !watch.isLoaded) return alive()
        if (connected) return true
        return try {
            val version = rpc.call("JSONRPC.Version")
            Diag.log("cast-target", "$label: Kodi JSON-RPC answered at ${kodiHostPort(address)} (api $version)")
            readFacts()
            connected = true
            true
        } catch (e: KodiRefusedLogin) {
            Diag.warn("cast-target", "$label: ${e.message}; check the file named by TOTUM_KODI_CREDENTIALS")
            false
        } catch (e: java.io.IOException) {
            Diag.warn("cast-target", "$label: Kodi JSON-RPC did not answer at ${kodiHostPort(address)}", e)
            false
        }
    }

    private fun alive(): Boolean = try {
        rpc.call("JSONRPC.Version")
        true
    } catch (e: java.io.IOException) {
        Diag.warn("cast-target", "$label: Kodi did not answer at ${kodiHostPort(address)}: ${e.message}")
        connected = false
        false
    }

    private fun readFacts() {
        val adaptive = try {
            rpc.call("Addons.GetAddons", buildJsonObject { put("enabled", true) })
                .objectOrEmpty()["addons"].arrayOrEmpty()
                .any { it.objectOrEmpty()["addonid"]?.jsonPrimitive?.contentOrNull == ADAPTIVE }
        } catch (e: java.io.IOException) {
            Diag.warn("cast-target", "$label: could not list Kodi's add-ons (${e.message}); HLS it is", e)
            false
        }
        val version = try {
            rpc.call(
                "Application.GetProperties",
                buildJsonObject {
                    put(
                        "properties",
                        JsonArray(listOf(JsonPrimitive("version"), JsonPrimitive("volume")))
                    )
                },
            ).objectOrEmpty().also { deviceVolume = it["volume"]?.jsonPrimitive?.intOrNull }["version"].objectOrEmpty()
        } catch (e: java.io.IOException) {
            Diag.warn("cast-target", "$label: could not read Kodi's version (${e.message})", e)
            JsonObject(emptyMap())
        }
        val named = listOfNotNull(version["major"], version["minor"]).joinToString(".") { it.jsonPrimitive.content }
        model = "Kodi${if (named.isEmpty()) "" else " $named"}"
        format = if (dashWhenAble && adaptive) CastFormat.DASH else CastFormat.HLS
        screen = CastScreen(
            model.orEmpty(),
            setOf(VideoCodecFamily.AVC, VideoCodecFamily.HEVC, VideoCodecFamily.VP9),
            UHD,
            known = false,
            codecHeights = mapOf(VideoCodecFamily.AVC to FULL_HD),
        )
        Diag.log(
            "cast-target",
            "$label: $model, inputstream.adaptive ${if (adaptive) "present" else "absent"}, " +
                "volume ${deviceVolume ?: "unreadable"}%; plays ${format.name}" +
                if (adaptive && !dashWhenAble) " (DASH is possible but HLS is what Kodi's own player proves)" else "",
        )
    }

    override fun load(url: String, contentType: String, title: String, startAtMs: Long) {
        watch.beforeLoad()
        try {
            rpc.call(
                "Player.Open",
                buildJsonObject {
                    put("item", buildJsonObject { put("file", url) })
                    put("options", buildJsonObject { put("resume", false) })
                },
            )
        } catch (e: java.io.IOException) {
            Diag.warn("cast-target", "$label: Player.Open of \"$title\" was refused: ${e.message}")
            watch.refused()
            return
        }
        watch.loaded(startAtMs)
        Diag.log("cast-target", "$label: Kodi Player.Open sent as ${format.name} from=${startAtMs}ms \"$title\"")
    }

    fun poll(volumeToo: Boolean = false) = watch.poll(volumeToo)

    private fun look(): RendererSnapshot {
        val players = rpc.call("Player.GetActivePlayers").arrayOrEmpty()
        val player = players.firstOrNull { it.objectOrEmpty()["type"]?.jsonPrimitive?.contentOrNull == "video" }
            ?: players.firstOrNull()
        val id = player?.objectOrEmpty()?.get("playerid")?.jsonPrimitive?.intOrNull
        playerId = id
        if (id == null) {
            return RendererSnapshot(
                RendererState.OVER,
                watch.reading?.positionMs ?: 0,
                null,
                detail = "no active player"
            )
        }
        val properties = rpc.call(
            "Player.GetProperties",
            buildJsonObject {
                put("playerid", id)
                put("properties", JsonArray(listOf("time", "totaltime", "speed").map(::JsonPrimitive)))
            },
        ).objectOrEmpty()
        val speed = properties["speed"]?.jsonPrimitive?.doubleOrNull ?: 0.0
        return RendererSnapshot(
            state = if (speed != 0.0) RendererState.PLAYING else RendererState.PAUSED,
            positionMs = millisOf(properties["time"]) ?: 0,
            durationMs = millisOf(properties["totaltime"])?.takeIf { it > 0 },
            detail = "playing at speed $speed",
        )
    }

    private fun activePlayer(): Int? = playerId ?: try {
        rpc.call("Player.GetActivePlayers").arrayOrEmpty().firstOrNull()?.objectOrEmpty()
            ?.get("playerid")?.jsonPrimitive?.intOrNull
    } catch (e: java.io.IOException) {
        Diag.warn("cast-target", "$label: could not find Kodi's player: ${e.message}")
        null
    }

    private fun act(what: String, method: String, params: (Int) -> JsonObject) {
        val id = activePlayer()
        if (id == null) {
            Diag.log("cast-target", "$label: $what not sent, Kodi has no active player")
            return
        }
        try {
            rpc.call(method, params(id))
        } catch (e: java.io.IOException) {
            Diag.warn("cast-target", "$label: $what refused: ${e.message}")
        }
    }

    private fun seekNow(positionMs: Long) {
        act("seek to ${positionMs}ms", "Player.Seek") { id ->
            buildJsonObject {
                put("playerid", id)
                put("value", buildJsonObject { put("time", timeOf(positionMs)) })
            }
        }
    }

    override fun pause(paused: Boolean) {
        act(if (paused) "pause" else "resume", "Player.PlayPause") { id ->
            buildJsonObject {
                put("playerid", id)
                put("play", !paused)
            }
        }
    }

    override fun seek(positionMs: Long) {
        watch.moved(positionMs)
        seekNow(positionMs)
    }

    override fun volume(percent: Int) {
        try {
            rpc.call("Application.SetVolume", buildJsonObject { put("volume", percent.coerceIn(0, MAX_PERCENT)) })
            deviceVolume = percent
            Diag.log("cast-target", "$label: Kodi volume set to $percent%")
        } catch (e: java.io.IOException) {
            Diag.warn("cast-target", "$label: volume $percent% refused: ${e.message}")
        }
    }

    override fun volumePercent(): Int? = deviceVolume

    private fun readVolume() {
        try {
            deviceVolume = rpc.call(
                "Application.GetProperties",
                buildJsonObject { put("properties", JsonArray(listOf(JsonPrimitive("volume")))) },
            ).objectOrEmpty()["volume"]?.jsonPrimitive?.intOrNull
        } catch (e: java.io.IOException) {
            Diag.log("cast-target", "$label: Kodi's volume could not be read (${e.message})")
        }
    }

    override fun stop() {
        if (!watch.stopped()) {
            Diag.log("cast-target", "$label: stop not sent, nothing is loaded from Totum")
            return
        }
        act("stop", "Player.Stop") { id -> buildJsonObject { put("playerid", id) } }
        playerId = null
    }

    override fun release() {
        Diag.log("cast-target", "$label: release needs nothing for Kodi")
    }

    override fun reading(): CastReading? = watch.reading

    private fun millisOf(time: JsonElement?): Long? {
        val t = time?.takeIf { it !is JsonNull }?.jsonObject ?: return null
        fun part(key: String) = t[key]?.jsonPrimitive?.longOrNull ?: 0L
        return ((part("hours") * MINUTES + part("minutes")) * SECONDS + part("seconds")) * MILLIS + part("milliseconds")
    }

    private fun timeOf(ms: Long): JsonObject {
        val total = ms.coerceAtLeast(0)
        return buildJsonObject {
            put("hours", total / MILLIS / SECONDS / MINUTES)
            put("minutes", total / MILLIS / SECONDS % MINUTES)
            put("seconds", total / MILLIS % SECONDS)
            put("milliseconds", total % MILLIS)
        }
    }

    private fun JsonElement?.objectOrEmpty(): JsonObject = (this as? JsonObject) ?: JsonObject(emptyMap())

    private fun JsonElement?.arrayOrEmpty(): JsonArray = (this as? JsonArray) ?: JsonArray(emptyList())

    companion object {
        private const val POLL_MS = 1_000L
        private const val ADAPTIVE = "inputstream.adaptive"
        private const val UHD = 2160
        private const val FULL_HD = 1080
        private const val MAX_PERCENT = 100
        private const val MILLIS = 1000L
        private const val SECONDS = 60L
        private const val MINUTES = 60L
    }
}
