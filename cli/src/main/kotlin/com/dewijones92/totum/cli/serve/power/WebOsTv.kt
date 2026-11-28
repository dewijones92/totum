package com.dewijones92.totum.cli.serve.power

import com.dewijones92.totum.common.Diag
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import java.io.IOException

internal class WebOsFault(message: String) : IOException(message)

internal sealed interface Registration {
    class Registered(val clientKey: String?) : Registration

    class NeedsPairing(val pairingType: String) : Registration

    class Refused(val why: String) : Registration
}

internal class WebOsSession(
    private val line: WebOsLine,
    private val responseMs: Long = RESPONSE_MS,
) {
    private var counter = 0

    fun register(clientKey: String?, pairingType: String): Registration {
        line.send(WebOsWire.hello())
        await { WebOsReply.type(it) == "hello" }
        line.send(WebOsWire.request(SYSTEM_INFO_ID, WebOsWire.SYSTEM_INFO))
        await { WebOsReply.id(it) == SYSTEM_INFO_ID }
        var reply = sendRegister(clientKey, pairingType, signed = true)
        if (WebOsReply.type(reply) == "error") {
            Diag.log("webos", "the TV refused the signed manifest (${WebOsReply.error(reply)}); trying it unsigned")
            reply = sendRegister(clientKey, pairingType, signed = false)
        }
        return registrationOf(reply)
    }

    fun submitPin(pin: String): Registration {
        line.send(WebOsWire.request(PIN_ID, WebOsWire.SET_PIN, buildJsonObject { put("pin", JsonPrimitive(pin)) }))
        return awaitRegistered { message ->
            if (WebOsReply.type(message) == "response" && WebOsReply.id(message) == PIN_ID &&
                WebOsReply.returnValue(WebOsReply.payload(message)) == false
            ) {
                Registration.Refused("the TV did not accept that PIN")
            } else {
                null
            }
        }
    }

    fun awaitRegistered(early: (JsonObject) -> Registration? = { null }): Registration {
        while (true) {
            val message = next()
            early(message)?.let { return it }
            when (WebOsReply.type(message)) {
                "registered" -> return registrationOf(message)
                "error" -> return Registration.Refused(WebOsReply.error(message) ?: "the TV refused it")
            }
        }
    }

    fun request(
        uri: String,
        payload: JsonObject = JsonObject(emptyMap()),
        closeMeansDone: Boolean = false,
    ): JsonObject {
        val id = "totum_${++counter}"
        line.send(WebOsWire.request(id, uri, payload))
        val answer = try {
            await { WebOsReply.id(it) == id }
        } catch (e: IOException) {
            return endedWithoutAnswer(uri, closeMeansDone, e)
        }
        return bodyOf(answer)
    }

    private fun endedWithoutAnswer(uri: String, closeMeansDone: Boolean, cause: IOException): JsonObject {
        if (!closeMeansDone) throw cause
        Diag.log("webos", "the TV closed the connection after $uri without an answer: taken as done (${cause.message})")
        return JsonObject(emptyMap())
    }

    private fun bodyOf(answer: JsonObject): JsonObject {
        if (WebOsReply.type(answer) == "error") throw WebOsFault(WebOsReply.error(answer) ?: "an error with no text")
        val body = WebOsReply.payload(answer)
        if (WebOsReply.returnValue(body) == false) {
            throw WebOsFault(WebOsReply.textOf(body["errorText"]) ?: "the TV said no, with no reason")
        }
        return body
    }

    private fun sendRegister(clientKey: String?, pairingType: String, signed: Boolean): JsonObject {
        line.send(WebOsWire.register(clientKey, pairingType, signed))
        return await { WebOsReply.id(it) == WebOsWire.REGISTER_ID }
    }

    private fun registrationOf(message: JsonObject): Registration = when (WebOsReply.type(message)) {
        "registered" -> Registration.Registered(WebOsReply.textOf(WebOsReply.payload(message)["client-key"]))
        "response" -> WebOsReply.textOf(WebOsReply.payload(message)["pairingType"])
            ?.let { Registration.NeedsPairing(it) }
            ?: Registration.Refused("the TV answered the registration with no pairing type")
        "error" -> Registration.Refused(WebOsReply.error(message) ?: "an error with no text")
        else -> Registration.Refused("an unexpected ${WebOsReply.type(message)} answer to the registration")
    }

    private fun await(wanted: (JsonObject) -> Boolean): JsonObject {
        while (true) {
            val message = next()
            if (wanted(message)) return message
        }
    }

    private fun next(): JsonObject =
        line.receive(responseMs) ?: throw IOException("the TV did not answer in ${responseMs}ms")

    companion object {
        const val RESPONSE_MS = 8_000L
        const val SYSTEM_INFO_ID = "get_sys_info"
        const val PIN_ID = "set_pin"
    }
}

internal class WebOsInput(val id: String, val label: String, val connected: Boolean)

internal class PanelReading(val on: Boolean?, val said: String)

internal class WebOsTv(
    private val label: String,
    private val dial: WebOsDial,
    private val keys: WebOsKeys,
    private val now: () -> Long = System::currentTimeMillis,
) {
    @Volatile private var switchedOffAtMs: Long? = null

    @Volatile private var refusedKey: String? = null

    @Volatile private var lastUnavailable: String? = null

    @Volatile private var lastState: String? = null

    val paired: Boolean get() = keys.isPaired

    suspend fun panelOn(): Boolean? = panelReading().on

    suspend fun panelReading(): PanelReading = when (val outcome = ask(this::readPanelState)) {
        is Outcome.Value -> PanelReading(outcome.value, "power state ${lastState ?: "unreadable"}")
        is Outcome.Unavailable -> PanelReading(null, "unavailable (${outcome.why})")
    }

    private fun readPanelState(session: WebOsSession): Boolean {
        val state = WebOsReply.textOf(session.request(WebOsWire.POWER_STATE)["state"])
        if (state != lastState) {
            Diag.log("webos", "$label: power state ${lastState ?: "unknown"} -> ${state ?: "unreadable"}")
        }
        lastState = state
        return state == WebOsWire.ACTIVE
    }

    suspend fun powerOff(): String? {
        val failure = ask { it.request(WebOsWire.POWER_OFF, closeMeansDone = true) }.reason()
        if (failure == null) switchedOffAtMs = now()
        return failure
    }

    suspend fun switchInput(inputId: String): String? {
        val failure = ask { session ->
            session.request(WebOsWire.SWITCH_INPUT, buildJsonObject { put("inputId", JsonPrimitive(inputId)) })
        }.reason()
        Diag.log("webos", "$label: switch to input $inputId ${failure?.let { "failed: $it" } ?: "sent"}")
        return failure
    }

    suspend fun inputs(): List<WebOsInput> = (
        ask { session ->
            (session.request(WebOsWire.EXTERNAL_INPUTS)["devices"] as? JsonArray).orEmpty().mapNotNull { element ->
                val device = element as? JsonObject ?: return@mapNotNull null
                val id = WebOsReply.textOf(device["id"]) ?: return@mapNotNull null
                WebOsInput(
                    id,
                    WebOsReply.textOf(device["label"]) ?: id,
                    (device["connected"] as? JsonPrimitive)?.booleanOrNull == true,
                )
            }
        } as? Outcome.Value
        )?.value.orEmpty()

    private sealed interface Outcome<out T> {
        class Value<T>(val value: T) : Outcome<T>

        class Unavailable(val why: String) : Outcome<Nothing>
    }

    private fun Outcome<*>.reason(): String? = (this as? Outcome.Unavailable)?.why

    @Suppress("TooGenericExceptionCaught")
    private suspend fun <T> ask(block: (WebOsSession) -> T): Outcome<T> = withContext(Dispatchers.IO) {
        val key = keys.read() ?: return@withContext unavailable("it has not been paired: run totum webos-pair")
        if (key.clientKey == refusedKey) {
            return@withContext unavailable("it refused the stored key earlier; run totum webos-pair again")
        }
        try {
            dial.open(key.cert).use { line ->
                val session = WebOsSession(line)
                when (val registered = session.register(key.clientKey, WebOsWire.PAIRING_PROMPT)) {
                    is Registration.Registered -> {
                        lastUnavailable = null
                        Outcome.Value(block(session))
                    }
                    is Registration.NeedsPairing -> {
                        refusedKey = key.clientKey
                        unavailable("it asked to pair again (${registered.pairingType}), so the stored key is no good")
                    }
                    is Registration.Refused -> unavailable("it refused the registration: ${registered.why}")
                }
            }
        } catch (e: WebOsFault) {
            unavailable("it answered with an error: ${e.message}")
        } catch (e: WebOsClosedByTv) {
            closedByTv(e)
        } catch (e: IOException) {
            unavailable("it could not be reached: ${e.message}")
        } catch (e: CancellationException) {
            throw e
        } catch (e: RuntimeException) {
            Diag.warn("webos", "$label: webOS control failed unexpectedly", e)
            unavailable("it failed unexpectedly: ${e.javaClass.simpleName}")
        }
    }

    private fun closedByTv(e: WebOsClosedByTv): Outcome.Unavailable {
        val sinceSwitchOffMs = switchedOffAtMs?.let { now() - it }
        if (sinceSwitchOffMs == null || sinceSwitchOffMs > SWITCH_OFF_WINDOW_MS) {
            return unavailable("it could not be reached: ${e.message}")
        }
        Diag.log(
            "webos",
            "$label: the TV closed the connection as it switched off (code ${e.code}, ${sinceSwitchOffMs}ms after " +
                "Totum switched it off); webOS control is not marked unavailable",
        )
        return Outcome.Unavailable("the TV closed the connection as it switched off")
    }

    private fun unavailable(why: String): Outcome.Unavailable {
        if (why != lastUnavailable) Diag.warn("webos", "$label: webOS control unavailable, $why")
        lastUnavailable = why
        return Outcome.Unavailable(why)
    }
}

private const val SWITCH_OFF_WINDOW_MS = 2 * 60_000L

internal class WebOsPowerOff(private val tv: WebOsTv) : PowerOff {
    override val name: String = "webOS system/turnOff"

    override val available: Boolean get() = tv.paired

    override suspend fun run(): String? = tv.powerOff()
}
