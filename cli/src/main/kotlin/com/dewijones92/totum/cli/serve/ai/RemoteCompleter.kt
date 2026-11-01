package com.dewijones92.totum.cli.serve.ai

import com.dewijones92.totum.cli.serve.BrainFailure
import com.dewijones92.totum.cli.serve.NotSignedIn
import com.dewijones92.totum.data.ask.AskJson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException

internal class RemoteCompleter(private val base: String, private val http: OkHttpClient) : Completer {

    override suspend fun complete(completion: Completion): JsonObject = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url(base.trimEnd('/') + "/complete")
            .post(AskJson.encodeToString(Completion.serializer(), completion).toRequestBody(JSON))
            .build()
        val (code, body) = try {
            http.newCall(request).execute().use { it.code to it.body.string() }
        } catch (failure: IOException) {
            throw BrainFailure("the AI service could not be reached: ${failure.message}", failure)
        }
        when (code) {
            OK -> runCatching { AskJson.decodeFromString(Completed.serializer(), body).structured }
                .getOrElse { throw BrainFailure("the AI service answered something unreadable") }
            NOT_SIGNED_IN -> throw NotSignedIn()
            else -> throw BrainFailure("the AI service said $code: ${body.take(LOGGED)}")
        }
    }

    internal companion object {
        const val OK = 200
        const val NOT_SIGNED_IN = 503
        private const val LOGGED = 300
        private val JSON = "application/json".toMediaType()
    }
}
