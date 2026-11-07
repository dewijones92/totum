package com.dewijones92.totum.data.home

import com.dewijones92.totum.common.Diag
import com.dewijones92.totum.common.HomeServerAuth
import com.dewijones92.totum.data.ask.AskJson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException

public class HomePiCall(
    private val client: OkHttpClient,
    private val token: () -> String,
    private val diagTag: String = DEFAULT_TAG,
) {
    public suspend fun <T> call(what: String, builder: Request.Builder, serializer: KSerializer<T>): HomeAnswer<T> =
        call(what, builder) { AskJson.decodeFromString(serializer, it) }

    public suspend fun <T> call(what: String, builder: Request.Builder, read: (String) -> T): HomeAnswer<T> =
        withContext(Dispatchers.IO) {
            val signedInToken = token()
            if (signedInToken.isBlank()) {
                Diag.warn(diagTag, "$what not sent: no home-server token")
                return@withContext HomeAnswer.No(NOT_SIGNED_IN)
            }
            try {
                client.newCall(builder.header(HomeServerAuth.HEADER, signedInToken).build()).execute().use { response ->
                    val text = response.body.string()
                    if (response.isSuccessful) {
                        runCatching { HomeAnswer.Ok(read(text)) }.getOrElse {
                            Diag.warn(diagTag, "$what -> unreadable answer: ${text.take(LOGGED)}", it)
                            HomeAnswer.No("❌ The home server's answer made no sense")
                        }
                    } else {
                        Diag.warn(diagTag, "$what -> HTTP ${response.code}: ${text.take(LOGGED)}")
                        val reason = reasonFor(response.code, errorOf(text))
                        HomeAnswer.No(reason, response.code)
                    }
                }
            } catch (e: IOException) {
                Diag.warn(diagTag, "$what failed", e)
                HomeAnswer.No("❌ The home server didn't answer")
            }
        }

    private fun errorOf(text: String): String? =
        runCatching { AskJson.parseToJsonElement(text).jsonObject["error"]?.jsonPrimitive?.content }.getOrNull()

    public companion object {
        public fun reasonFor(code: Int, error: String?): String = when {
            error != null && error.trim().startsWith(UNKNOWN_ROUTE, ignoreCase = true) -> NEEDS_UPDATING
            error != null -> emojiLed(error)
            else -> "❌ The home server said no (HTTP $code)"
        }

        public fun emojiLed(reason: String): String {
            val text = reason.trim()
            val first = text.codePointAt(0)
            val drawnAsEmoji = text.getOrNull(Character.charCount(first)) == VARIATION_SELECTOR_16
            val plainWord = first < ASCII || Character.isLetterOrDigit(first) && !drawnAsEmoji
            return if (plainWord) "❌ $text" else text
        }

        private const val ASCII = 128
        private const val VARIATION_SELECTOR_16 = '️'
        public const val NOT_SIGNED_IN: String = "❌ Not signed in to the home server"
        public const val NEEDS_UPDATING: String = "⚠️ The home server needs updating for this"
        private const val UNKNOWN_ROUTE = "no such route"
        private const val DEFAULT_TAG = "home"
        private const val LOGGED = 200
    }
}
