package com.dewijones92.totum.cli.serve.cast

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.Base64

internal class KodiLogin(val user: String, val password: String) {
    override fun toString(): String = "KodiLogin(user=…, password=…)"
}

internal fun interface KodiLogins {
    fun read(): KodiLogin?
}

internal object KodiCredentials {
    const val NONE = "no credentials file is configured (TOTUM_KODI_CREDENTIALS)"

    fun fromFile(file: File?): KodiLogins = KodiLogins {
        file?.takeIf { it.isFile }?.let { found ->
            val pairs = found.readLines().mapNotNull { line ->
                line.substringBefore('=', "").trim().takeIf { it.isNotEmpty() }
                    ?.let { it to line.substringAfter('=').trim() }
            }.toMap()
            val user = pairs["user"]
            val password = pairs["password"]
            if (user.isNullOrEmpty() || password == null) null else KodiLogin(user, password)
        }
    }
}

internal class KodiFault(val code: Int?, message: String) : IOException(message)

internal class KodiRefusedLogin(message: String, val noCredentials: Boolean = false) : IOException(message)

internal interface KodiRpc {
    fun call(method: String, params: JsonObject = JsonObject(emptyMap())): JsonElement
}

internal fun kodiEndpoint(address: String): String {
    val url = if (address.contains("://")) address else "http://$address"
    return if (url.trimEnd('/').endsWith("/jsonrpc")) url.trimEnd('/') else url.trimEnd('/') + "/jsonrpc"
}

internal fun kodiHostPort(address: String): String = address.substringAfter("://").substringBefore('/')

internal class JdkKodiRpc(
    private val endpoint: String,
    private val logins: KodiLogins,
    private val timeoutMs: Int = TIMEOUT_MS,
) : KodiRpc {

    private var nextId = 0

    override fun call(method: String, params: JsonObject): JsonElement {
        val login = logins.read() ?: throw KodiRefusedLogin(KodiCredentials.NONE, noCredentials = true)
        val body = buildJsonObject {
            put("jsonrpc", JsonPrimitive("2.0"))
            put("id", JsonPrimitive(synchronized(this@JdkKodiRpc) { ++nextId }))
            put("method", JsonPrimitive(method))
            put("params", params)
        }.toString()
        val connection = URL(endpoint).openConnection() as HttpURLConnection
        try {
            connection.requestMethod = "POST"
            connection.connectTimeout = timeoutMs
            connection.readTimeout = timeoutMs
            connection.doOutput = true
            connection.setRequestProperty("Content-Type", "application/json")
            connection.setRequestProperty(
                "Authorization",
                "Basic " + Base64.getEncoder().encodeToString("${login.user}:${login.password}".toByteArray()),
            )
            connection.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            val status = connection.responseCode
            if (status == HTTP_UNAUTHORIZED) throw KodiRefusedLogin("Kodi refused the sign-in (HTTP 401)")
            val text = (if (status < HTTP_ERROR) connection.inputStream else connection.errorStream)
                ?.use { it.readBytes().toString(Charsets.UTF_8) }.orEmpty()
            if (status >= HTTP_ERROR) throw IOException("Kodi answered HTTP $status to $method")
            return answer(method, text)
        } finally {
            connection.disconnect()
        }
    }

    private fun answer(method: String, text: String): JsonElement {
        val reply = runCatching { Json.parseToJsonElement(text).jsonObject }.getOrNull()
            ?: throw IOException("Kodi's answer to $method was not JSON")
        val error = reply["error"]?.takeIf { it !is JsonNull }?.jsonObject
        if (error != null) {
            throw KodiFault(
                error["code"]?.jsonPrimitive?.intOrNull,
                "Kodi refused $method: ${error["message"]?.jsonPrimitive?.contentOrNull ?: "no reason given"}",
            )
        }
        return reply["result"] ?: JsonNull
    }

    private companion object {
        const val TIMEOUT_MS = 8_000
        const val HTTP_UNAUTHORIZED = 401
        const val HTTP_ERROR = 400
    }
}
