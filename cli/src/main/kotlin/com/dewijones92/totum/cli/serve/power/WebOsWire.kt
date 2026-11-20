package com.dewijones92.totum.cli.serve.power

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import java.io.IOException

internal class WebOsKey(val clientKey: String, val cert: String?) {
    override fun toString(): String = "WebOsKey(clientKey=…, cert=${cert?.take(CERT_SHOWN)?.let { "$it…" }})"

    private companion object {
        const val CERT_SHOWN = 8
    }
}

internal class WebOsKeys(private val file: File?) {
    val isPaired: Boolean get() = read() != null

    fun read(): WebOsKey? {
        val found = file?.takeIf { it.isFile } ?: return null
        val text = runCatching { found.readText() }.getOrNull() ?: return null
        val json = runCatching { Json.parseToJsonElement(text).jsonObject }.getOrNull() ?: return null
        val key = json[KEY]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotEmpty() } ?: return null
        return WebOsKey(key, json[CERT]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotEmpty() })
    }

    fun write(key: WebOsKey) {
        val target = file ?: throw IOException("no place is configured to keep the TV's key")
        val body = buildJsonObject {
            put(KEY, JsonPrimitive(key.clientKey))
            key.cert?.let { put(CERT, JsonPrimitive(it)) }
        }.toString()
        writeOwnerOnly(target, body)
    }

    companion object {
        const val KEY = "client-key"
        const val CERT = "cert"
    }
}

internal object WebOsWire {
    const val PAIRING_PIN = "PIN"
    const val PAIRING_PROMPT = "PROMPT"
    const val POWER_STATE = "ssap://com.webos.service.tvpower/power/getPowerState"
    const val POWER_OFF = "ssap://system/turnOff"
    const val SWITCH_INPUT = "ssap://tv/switchInput"
    const val EXTERNAL_INPUTS = "ssap://tv/getExternalInputList"
    const val SET_PIN = "ssap://pairing/setPin"
    const val SYSTEM_INFO = "ssap://system/getSystemInfo"
    const val ACTIVE = "Active"

    fun hello(): JsonObject = buildJsonObject {
        put("id", JsonPrimitive("hello"))
        put("type", JsonPrimitive("hello"))
        put("payload", JsonObject(emptyMap()))
    }

    fun request(id: String, uri: String, payload: JsonObject = JsonObject(emptyMap())): JsonObject = buildJsonObject {
        put("id", JsonPrimitive(id))
        put("type", JsonPrimitive("request"))
        put("uri", JsonPrimitive(uri))
        put("payload", payload)
    }

    fun register(clientKey: String?, pairingType: String, signed: Boolean): JsonObject = buildJsonObject {
        put("id", JsonPrimitive(REGISTER_ID))
        put("type", JsonPrimitive("register"))
        put(
            "payload",
            buildJsonObject {
                put("forcePairing", JsonPrimitive(false))
                put("pairingType", JsonPrimitive(pairingType))
                clientKey?.let { put("client-key", JsonPrimitive(it)) }
                put("manifest", manifest(signed))
            },
        )
    }

    const val REGISTER_ID = "register_0"

    fun type(message: JsonObject): String? = message["type"]?.jsonPrimitive?.contentOrNull

    fun id(message: JsonObject): String? = message["id"]?.jsonPrimitive?.contentOrNull

    fun payload(message: JsonObject): JsonObject = (message["payload"] as? JsonObject) ?: JsonObject(emptyMap())

    fun error(message: JsonObject): String? = message["error"]?.takeIf { it !is JsonNull }?.jsonPrimitive?.contentOrNull

    fun returnValue(payload: JsonObject): Boolean? = payload["returnValue"]?.jsonPrimitive?.booleanOrNull

    fun textOf(element: JsonElement?): String? = (element as? JsonPrimitive)?.contentOrNull

    private fun manifest(signed: Boolean): JsonObject = buildJsonObject {
        put("manifestVersion", JsonPrimitive(1))
        put("appVersion", JsonPrimitive("1.1"))
        if (signed) {
            put("permissions", names(PERMISSIONS))
            put(
                "signatures",
                buildJsonArray {
                    add(
                        buildJsonObject {
                            put("signature", JsonPrimitive(SIGNATURE))
                            put("signatureVersion", JsonPrimitive(1))
                        },
                    )
                },
            )
            put(
                "signed",
                buildJsonObject {
                    put("appId", JsonPrimitive("com.lge.test"))
                    put("created", JsonPrimitive("20140509"))
                    put(
                        "localizedAppNames",
                        buildJsonObject {
                            put("", JsonPrimitive("LG Remote App"))
                            put("ko-KR", JsonPrimitive("리모컨 앱"))
                            put("zxx-XX", JsonPrimitive("ЛГ Rэмotэ AПП"))
                        },
                    )
                    put("localizedVendorNames", buildJsonObject { put("", JsonPrimitive("LG Electronics")) })
                    put("permissions", names(SIGNED_PERMISSIONS))
                    put("serial", JsonPrimitive("2f930e2d2cfe083771f68e4fe7bb07"))
                    put("vendorId", JsonPrimitive("com.lge"))
                },
            )
        } else {
            put("permissions", names((PERMISSIONS + SIGNED_PERMISSIONS).toSortedSet().toList()))
        }
    }

    private fun names(list: List<String>): JsonArray = buildJsonArray { list.forEach { add(JsonPrimitive(it)) } }

    private val PERMISSIONS = listOf(
        "LAUNCH", "LAUNCH_WEBAPP", "APP_TO_APP", "CLOSE", "TEST_OPEN", "TEST_PROTECTED", "CONTROL_AUDIO",
        "CONTROL_DISPLAY", "CONTROL_INPUT_JOYSTICK", "CONTROL_INPUT_MEDIA_RECORDING",
        "CONTROL_INPUT_MEDIA_PLAYBACK", "CONTROL_INPUT_TV", "CONTROL_POWER", "CONTROL_TV_SCREEN",
        "READ_APP_STATUS", "READ_CURRENT_CHANNEL", "READ_INPUT_DEVICE_LIST", "READ_NETWORK_STATE",
        "READ_RUNNING_APPS", "READ_TV_CHANNEL_LIST", "WRITE_NOTIFICATION_TOAST", "READ_POWER_STATE",
        "READ_COUNTRY_INFO", "CONTROL_INPUT_TEXT", "CONTROL_MOUSE_AND_KEYBOARD", "READ_INSTALLED_APPS",
        "READ_SETTINGS",
    )

    private val SIGNED_PERMISSIONS = listOf(
        "TEST_SECURE", "CONTROL_INPUT_TEXT", "CONTROL_MOUSE_AND_KEYBOARD", "READ_INSTALLED_APPS", "READ_LGE_SDX",
        "READ_NOTIFICATIONS", "SEARCH", "WRITE_SETTINGS", "WRITE_NOTIFICATION_ALERT", "CONTROL_POWER",
        "READ_CURRENT_CHANNEL", "READ_RUNNING_APPS", "READ_UPDATE_INFO", "UPDATE_FROM_REMOTE_APP",
        "READ_LGE_TV_INPUT_EVENTS", "READ_TV_CURRENT_TIME",
    )

    private const val SIGNATURE =
        "eyJhbGdvcml0aG0iOiJSU0EtU0hBMjU2Iiwia2V5SWQiOiJ0ZXN0LXNpZ25pbmctY2VydCIsInNpZ25hdHVyZVZlcnNpb24iOjF9" +
        ".hrVRgjCwXVvE2OOSpDZ58hR+59aFNwYDyjQgKk3auukd7pcegmE2CzPCa0bJ0ZsRAcKkCTJrWo5iDzNhMBWRyaMOv5zWSrthlf7" +
        "G128qvIlpMT0YNY+n/FaOHE73uLrS/g7swl3/qH/BGFG2Hu4RlL48eb3lLKqTt2xKHdCs6Cd4RMfJPYnzgvI4BNrFUKsjkcu+WD4" +
        "OO2A27Pq1n50cMchmcaXadJhGrOqH5YmHdOCj5NSHzJYrsW0HPlpuAx/ECMeIZYDh6RMqaFM2DXzdKX9NmmyqzJ3o/0lkk/N97gf" +
        "VRLW5hA29yeAwaCViZNCP8iC9aO0q9fQojoa7NQnAtw=="
}
