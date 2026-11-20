package com.dewijones92.totum.cli.serve.power

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.util.Collections

internal class FakeSsapServer(
    val clientKey: String = "CLIENT-KEY-SECRET-0123456789",
    val pin: String = "123456",
) : AutoCloseable {

    @Volatile var pairingType: String = "PIN"

    @Volatile var refuseSigned: Boolean = false

    @Volatile var knowsStoredKey: Boolean = true

    @Volatile var powerState: String = "Active"

    @Volatile var turnOffAnswer: TurnOff = TurnOff.ANSWER

    @Volatile var turnOffErrorText: String? = null

    @Volatile var inputs: List<Triple<String, String, Boolean>> = listOf(
        Triple("HDMI_1", "Fire Stick", true),
        Triple("HDMI_2", "Games", false),
    )

    enum class TurnOff { ANSWER, CLOSE_WITHOUT_ANSWER, REFUSE }

    val requests: MutableList<JsonObject> = Collections.synchronizedList(mutableListOf())
    val registrations: MutableList<JsonObject> = Collections.synchronizedList(mutableListOf())
    val connections: MutableList<WebSocket> = Collections.synchronizedList(mutableListOf())

    private val server = MockWebServer()

    val url: String get() = "ws://${server.hostName}:${server.port}"

    val connectionCount: Int get() = connections.size

    fun uris(): List<String> = requests.mapNotNull { it["uri"]?.jsonPrimitive?.contentOrNull }

    override fun close() {
        server.close()
    }

    private fun upgrade() = MockResponse.Builder().webSocketUpgrade(listener).build()

    private val listener = object : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            connections += webSocket
        }

        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            webSocket.close(NORMAL_CLOSE, "")
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            val message = Json.parseToJsonElement(text).jsonObject
            when (message["type"]?.jsonPrimitive?.contentOrNull) {
                "hello" -> webSocket.send(frame("hello", "hello", buildJsonObject { put("protocolVersion", JsonPrimitive(1)) }))
                "register" -> register(webSocket, message)
                "request" -> request(webSocket, message)
            }
        }
    }

    init {
        repeat(CONNECTIONS) { server.enqueue(upgrade()) }
        server.start()
    }

    private fun frame(type: String, id: String, payload: JsonObject): String =
        buildJsonObject {
            put("type", JsonPrimitive(type))
            put("id", JsonPrimitive(id))
            put("payload", payload)
        }.toString()

    private fun register(webSocket: WebSocket, message: JsonObject) {
        registrations += message
        val payload = message["payload"]!!.jsonObject
        val manifest = payload["manifest"]!!.jsonObject
        if (refuseSigned && manifest.containsKey("signed")) {
            webSocket.send(
                buildJsonObject {
                    put("type", JsonPrimitive("error"))
                    put("id", JsonPrimitive("register_0"))
                    put("error", JsonPrimitive("403 insufficient permissions"))
                }.toString(),
            )
            return
        }
        val offered = payload["client-key"]?.jsonPrimitive?.contentOrNull
        if (offered == clientKey && knowsStoredKey) {
            webSocket.send(frame("registered", "register_0", buildJsonObject { put("client-key", JsonPrimitive(clientKey)) }))
            return
        }
        webSocket.send(
            frame("response", "register_0", buildJsonObject { put("pairingType", JsonPrimitive(pairingType)) }),
        )
        if (pairingType != "PIN") {
            webSocket.send(frame("registered", "register_0", buildJsonObject { put("client-key", JsonPrimitive(clientKey)) }))
        }
    }

    private fun request(webSocket: WebSocket, message: JsonObject) {
        requests += message
        val id = message["id"]!!.jsonPrimitive.content
        val body = message["payload"] as? JsonObject ?: JsonObject(emptyMap())
        when (message["uri"]?.jsonPrimitive?.contentOrNull) {
            "ssap://system/getSystemInfo" -> answer(webSocket, id, ok())
            "ssap://pairing/setPin" -> setPin(webSocket, id, body)
            "ssap://com.webos.service.tvpower/power/getPowerState" ->
                answer(webSocket, id, ok { put("state", JsonPrimitive(powerState)) })
            "ssap://system/turnOff" -> turnOff(webSocket, id)
            "ssap://tv/switchInput" -> answer(webSocket, id, ok())
            "ssap://tv/getExternalInputList" -> answer(webSocket, id, ok { put("devices", devices()) })
            else -> answer(webSocket, id, buildJsonObject {
                put("returnValue", JsonPrimitive(false))
                put("errorText", JsonPrimitive("no such service"))
            })
        }
    }

    private fun setPin(webSocket: WebSocket, id: String, body: JsonObject) {
        if (body["pin"]?.jsonPrimitive?.contentOrNull == pin) {
            answer(webSocket, id, ok())
            webSocket.send(frame("registered", "register_0", buildJsonObject { put("client-key", JsonPrimitive(clientKey)) }))
        } else {
            answer(webSocket, id, buildJsonObject { put("returnValue", JsonPrimitive(false)) })
        }
    }

    private fun turnOff(webSocket: WebSocket, id: String) {
        when (turnOffAnswer) {
            TurnOff.ANSWER -> {
                powerState = "Active Standby"
                answer(webSocket, id, ok())
            }
            TurnOff.CLOSE_WITHOUT_ANSWER -> {
                powerState = "Active Standby"
                webSocket.close(NORMAL_CLOSE, "going to standby")
            }
            TurnOff.REFUSE -> answer(
                webSocket,
                id,
                buildJsonObject {
                    put("returnValue", JsonPrimitive(false))
                    put("errorText", JsonPrimitive(turnOffErrorText ?: "not allowed"))
                },
            )
        }
    }

    private fun devices(): JsonArray = buildJsonArray {
        inputs.forEach { (id, label, connected) ->
            add(
                buildJsonObject {
                    put("id", JsonPrimitive(id))
                    put("label", JsonPrimitive(label))
                    put("connected", JsonPrimitive(connected))
                },
            )
        }
    }

    private fun answer(webSocket: WebSocket, id: String, payload: JsonObject) {
        webSocket.send(frame("response", id, payload))
    }

    private fun ok(extra: kotlinx.serialization.json.JsonObjectBuilder.() -> Unit = {}): JsonObject = buildJsonObject {
        put("returnValue", JsonPrimitive(true))
        extra()
    }

    private companion object {
        const val NORMAL_CLOSE = 1000
        const val CONNECTIONS = 12
    }
}
