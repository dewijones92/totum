package com.dewijones92.totum.data.phone.google

import com.dewijones92.totum.common.Diag
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.File
import java.io.IOException
import java.time.Instant

@Serializable
public data class PollingConfig(val pollInterval: String? = null, val timeoutIn: String? = null)

@Serializable
public data class PickerSession(
    val id: String,
    val pickerUri: String? = null,
    val pollingConfig: PollingConfig? = null,
    val expireTime: String? = null,
    val mediaItemsSet: Boolean = false,
)

@Serializable
public data class PickedMetadata(val width: Int? = null, val height: Int? = null)

@Serializable
public data class PickedFile(
    val baseUrl: String,
    val mimeType: String? = null,
    val filename: String? = null,
    val mediaFileMetadata: PickedMetadata? = null,
)

@Serializable
public data class PickedItem(
    val id: String,
    val createTime: String? = null,
    val type: String? = null,
    val mediaFile: PickedFile? = null,
) {
    public val isVideo: Boolean get() = type == "VIDEO"
}

@Serializable
public data class PickedItemsPage(val mediaItems: List<PickedItem> = emptyList(), val nextPageToken: String? = null)

public sealed interface PickerCall<out T> {
    public data class Ok<T>(val value: T) : PickerCall<T>

    public data class No(val reason: String, val httpCode: Int? = null) : PickerCall<Nothing>
}

public object PickerDurations {
    public fun millis(text: String?): Long? =
        text?.trim()?.removeSuffix("s")?.toDoubleOrNull()?.let { (it * MS_PER_SECOND).toLong() }

    public fun expiryMs(text: String?): Long? =
        text?.let { runCatching { Instant.parse(it).toEpochMilli() }.getOrNull() }

    private const val MS_PER_SECOND = 1000.0
}

public class PhotosPickerApi(
    private val http: OkHttpClient,
    private val token: suspend () -> String?,
    private val refreshToken: suspend () -> String?,
    private val base: String = BASE,
) {
    private val json = Json { ignoreUnknownKeys = true }

    public suspend fun createSession(): PickerCall<PickerSession> =
        text("POST", "$base/sessions", """{"pickingConfig":{"maxItemCount":"$MAX_ITEMS"}}""").parsed {
            json.decodeFromString<PickerSession>(it)
        }

    public suspend fun session(id: String): PickerCall<PickerSession> =
        text("GET", "$base/sessions/$id", null).parsed { json.decodeFromString<PickerSession>(it) }

    public suspend fun items(sessionId: String, pageToken: String?): PickerCall<PickedItemsPage> {
        val url = "$base/mediaItems".toHttpUrl().newBuilder()
            .addQueryParameter("sessionId", sessionId)
            .addQueryParameter("pageSize", PAGE_SIZE.toString())
            .apply { pageToken?.let { addQueryParameter("pageToken", it) } }
            .build().toString()
        return text("GET", url, null).parsed { json.decodeFromString<PickedItemsPage>(it) }
    }

    public suspend fun endSession(id: String) {
        text("DELETE", "$base/sessions/$id", null)
    }

    public suspend fun download(item: PickedItem, into: File): PickerCall<Long> {
        val file = item.mediaFile ?: return PickerCall.No("❌ Google gave no file for this item")
        val url = file.baseUrl + if (item.isVideo) "=dv" else "=d"
        val partial = File(into.parentFile, into.name + ".part")
        val saved = send("GET", url, null) { response ->
            val bytes = response.body.byteStream().use { input -> partial.outputStream().use { input.copyTo(it) } }
            when {
                bytes == 0L -> PickerCall.No("❌ Google sent an empty file")
                partial.renameTo(into) -> PickerCall.Ok(bytes)
                else -> PickerCall.No("❌ Couldn't save the file")
            }
        }
        if (saved !is PickerCall.Ok) partial.delete()
        return saved
    }

    private suspend fun text(method: String, url: String, body: String?): PickerCall<String> =
        send(method, url, body) { response ->
            val answer = response.body.string()
            Diag.log(
                "google-photos",
                "$method ${url.substringBefore('?')} -> ${response.code} (${answer.length} bytes)"
            )
            PickerCall.Ok(answer)
        }

    @Suppress("TooGenericExceptionCaught")
    private suspend fun <T> send(
        method: String,
        url: String,
        body: String?,
        read: (Response) -> PickerCall<T>,
    ): PickerCall<T> = withContext(Dispatchers.IO) {
        var last: PickerCall<T> = PickerCall.No(SIGN_IN_AGAIN, UNAUTHORISED)
        var tries = 0
        while (tries++ < ATTEMPTS) {
            val bearer = token() ?: return@withContext PickerCall.No(SIGN_IN_AGAIN)
            val payload = body?.toRequestBody(JSON) ?: if (method == "POST") "".toRequestBody() else null
            val request = Request.Builder().url(url).header("Authorization", "Bearer $bearer")
                .method(method, payload).build()
            last = try {
                http.newCall(request).execute().use { response ->
                    if (response.isSuccessful) read(response) else failed(response)
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: IOException) {
                Diag.warn("google-photos", "$method $url failed", failure)
                PickerCall.No("📶 Couldn't reach Google: ${failure.message}")
            } catch (failure: Exception) {
                Diag.warn("google-photos", "$method $url gave an answer that could not be read", failure)
                PickerCall.No("❌ Google's answer could not be read: ${failure.message}")
            }
            val refusedToken = (last as? PickerCall.No)?.httpCode == UNAUTHORISED
            if (!refusedToken || refreshToken() == null) break
        }
        last
    }

    private fun failed(response: Response): PickerCall.No {
        val text = runCatching { response.body.string() }.getOrDefault("")
        return PickerCall.No(describe(response.code, text), response.code)
    }

    private fun <T> PickerCall<String>.parsed(parse: (String) -> T): PickerCall<T> = when (this) {
        is PickerCall.No -> this
        is PickerCall.Ok -> try {
            PickerCall.Ok(parse(value))
        } catch (failure: kotlinx.serialization.SerializationException) {
            Diag.warn("google-photos", "an answer could not be read", failure)
            PickerCall.No("❌ Google's answer could not be read: ${failure.message}")
        }
    }

    private fun describe(code: Int, text: String): String = when (code) {
        UNAUTHORISED -> SIGN_IN_AGAIN
        FORBIDDEN ->
            "🚫 Google refused: the Photos Picker API is probably not switched on for this app's " +
                "Google Cloud project" + messageIn(text)
        TOO_MANY -> "⏳ Google is asking Totum to slow down: try again in a minute"
        else -> "❌ Google answered $code"
    }

    private fun messageIn(text: String): String =
        text.substringAfter("\"message\":", "").substringBefore(',').trim().trim('"')
            .takeIf { it.isNotBlank() }?.let { " ($it)" }.orEmpty()

    public companion object {
        public const val BASE: String = "https://photospicker.googleapis.com/v1"
        public const val PAGE_SIZE: Int = 100
        private const val MAX_ITEMS = 200
        private const val ATTEMPTS = 2
        private const val UNAUTHORISED = 401
        private const val FORBIDDEN = 403
        private const val TOO_MANY = 429
        private const val SIGN_IN_AGAIN = "🔑 Sign in to Google Photos again"
        private val JSON = "application/json".toMediaType()
    }
}
