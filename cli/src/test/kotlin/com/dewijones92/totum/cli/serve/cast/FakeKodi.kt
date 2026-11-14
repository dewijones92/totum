package com.dewijones92.totum.cli.serve.cast

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.io.File
import java.net.InetSocketAddress
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermissions
import java.util.Base64

internal class FakeKodi(
    private val user: String = "kodi-user",
    private val password: String = "kodi-pass-SECRET",
) : AutoCloseable {

    class Call(val method: String, val params: JsonObject)

    val calls = mutableListOf<Call>()
    val requestLines = mutableListOf<String>()
    val authorizations = mutableListOf<String?>()

    @Volatile var adaptive = false

    @Volatile var volume = 40

    @Volatile var playing = false

    @Volatile var speed = 0

    @Volatile var timeMs = 0L

    @Volatile var totalMs = 0L

    @Volatile var refuse: Set<String> = emptySet()

    @Volatile var down = false

    private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
        createContext("/") { exchange -> answer(exchange) }
        start()
    }

    val port: Int get() = server.address.port

    val address: String get() = "127.0.0.1:$port"

    val login: String get() = "$user:$password"

    val expectedAuthorization: String get() = "Basic " + Base64.getEncoder().encodeToString(login.toByteArray())

    fun credentialsFile(
        user: String = this.user,
        password: String = this.password,
        dir: File = Files.createTempDirectory("kodi-creds").toFile(),
    ): File = File(dir, "kodi.txt").also {
        it.writeText("user=$user\npassword=$password\nhost=127.0.0.1:$port\n")
        Files.setPosixFilePermissions(it.toPath(), PosixFilePermissions.fromString("rw-------"))
    }

    fun methods(): List<String> = synchronized(calls) { calls.map { it.method } }

    fun count(method: String): Int = synchronized(calls) { calls.count { it.method == method } }

    fun last(method: String): JsonObject = synchronized(calls) { calls.last { it.method == method }.params }

    private fun time(ms: Long) = buildJsonObject {
        put("hours", ms / MS / MINUTE / MINUTE)
        put("minutes", ms / MS / MINUTE % MINUTE)
        put("seconds", ms / MS % MINUTE)
        put("milliseconds", ms % MS)
    }

    private fun versionOf(major: Int, minor: Int) = buildJsonObject {
        put("major", major)
        put("minor", minor)
        put("patch", 0)
    }

    private fun activePlayer() = buildJsonObject {
        put("playerid", 1)
        put("playertype", "internal")
        put("type", "video")
    }

    private fun result(method: String, params: JsonObject): JsonElement =
        if (method.startsWith("Player.")) playerResult(method, params) else otherResult(method, params)

    private fun otherResult(method: String, params: JsonObject): JsonElement = when (method) {
        "JSONRPC.Version" -> buildJsonObject { put("version", versionOf(13, 5)) }
        "Addons.GetAddons" -> buildJsonObject {
            put(
                "addons",
                buildJsonArray {
                    add(buildJsonObject { put("addonid", "plugin.video.other") })
                    if (adaptive) add(buildJsonObject { put("addonid", "inputstream.adaptive") })
                },
            )
        }
        "Application.GetProperties" -> buildJsonObject {
            put("volume", volume)
            put("version", versionOf(21, 3))
        }
        "Application.SetVolume" -> JsonPrimitive(params["volume"]!!.jsonPrimitive.content.toInt().also { volume = it })
        else -> JsonPrimitive("OK")
    }

    private fun playerResult(method: String, params: JsonObject): JsonElement = when (method) {
        "Player.GetActivePlayers" -> buildJsonArray {
            if (playing) add(activePlayer())
        }
        "Player.GetProperties" -> buildJsonObject {
            put("time", time(timeMs))
            put("totaltime", time(totalMs))
            put("speed", speed)
        }
        "Player.Open" -> JsonPrimitive("OK").also {
            playing = true
            speed = 1
        }
        "Player.PlayPause" -> buildJsonObject {
            speed = if (params["play"]!!.jsonPrimitive.content == "true") 1 else 0
            put("speed", speed)
        }
        "Player.Stop" -> JsonPrimitive("OK").also {
            playing = false
            speed = 0
        }
        else -> JsonPrimitive("OK")
    }

    private fun answer(exchange: HttpExchange) {
        val body = exchange.requestBody.readBytes().toString(Charsets.UTF_8)
        synchronized(calls) {
            val host = exchange.requestHeaders.getFirst("Host")
            requestLines += "${exchange.requestMethod} ${exchange.requestURI} $host"
            authorizations += exchange.requestHeaders.getFirst("Authorization")
        }
        if (exchange.requestHeaders.getFirst("Authorization") != expectedAuthorization) {
            exchange.sendResponseHeaders(UNAUTHORIZED, -1)
            exchange.close()
            return
        }
        val request = Json.parseToJsonElement(body).jsonObject
        val method = request.getValue("method").jsonPrimitive.content
        val params = request["params"] as? JsonObject ?: JsonObject(emptyMap())
        synchronized(calls) { calls += Call(method, params) }
        val reply = when {
            down -> null
            method in refuse -> buildJsonObject {
                put("jsonrpc", "2.0")
                put("id", request["id"]!!)
                put(
                    "error",
                    buildJsonObject {
                        put("code", -32602)
                        put("message", "Invalid params")
                    }
                )
            }
            else -> buildJsonObject {
                put("jsonrpc", "2.0")
                put("id", request["id"]!!)
                put("result", result(method, params))
            }
        }
        if (reply == null) {
            exchange.sendResponseHeaders(UNAVAILABLE, -1)
        } else {
            val bytes = reply.toString().toByteArray()
            exchange.responseHeaders.add("Content-Type", "application/json")
            exchange.sendResponseHeaders(OK, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        exchange.close()
    }

    override fun close() = server.stop(0)

    private companion object {
        const val OK = 200
        const val UNAUTHORIZED = 401
        const val UNAVAILABLE = 503
        const val MS = 1000L
        const val MINUTE = 60L
    }
}
