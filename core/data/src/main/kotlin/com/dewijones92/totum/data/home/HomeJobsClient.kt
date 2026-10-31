package com.dewijones92.totum.data.home

import com.dewijones92.totum.common.Diag
import com.dewijones92.totum.common.HomeServerAuth
import com.dewijones92.totum.common.HttpUrl
import com.dewijones92.totum.data.ask.AskJson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.net.URLEncoder

public sealed interface HomeAnswer<out T> {
    public data class Ok<T>(val value: T) : HomeAnswer<T>
    public data class No(val reason: String) : HomeAnswer<Nothing>
}

public interface HomeJobsApi {
    public suspend fun start(job: HomeJobRequest): HomeAnswer<String>
    public suspend fun jobs(): HomeAnswer<HomeJobs>
    public suspend fun cancel(id: String): HomeAnswer<Unit>
    public suspend fun retry(id: String): HomeAnswer<Unit>
    public suspend fun handOver(account: AccountHandover): HomeAnswer<Unit>
    public suspend fun forgetAccount(): HomeAnswer<Unit>
    public fun fileUrl(id: String): HttpUrl
}

@Suppress("TooManyFunctions")
public class HomeJobsClient(
    private val client: OkHttpClient,
    private val base: String,
    private val token: () -> String,
) : HomeJobsApi {
    override suspend fun start(job: HomeJobRequest): HomeAnswer<String> =
        call("POST /jobs", post("jobs", AskJson.encodeToString(HomeJobRequest.serializer(), job))) { text ->
            AskJson.parseToJsonElement(text).jsonObject["id"]?.jsonPrimitive?.content ?: job.item.itemId
        }

    override suspend fun jobs(): HomeAnswer<HomeJobs> =
        call("GET /jobs", Request.Builder().url("$base/jobs").get(), HomeJobs.serializer())

    override suspend fun cancel(id: String): HomeAnswer<Unit> =
        call("DELETE /jobs?id=$id", Request.Builder().url("$base/jobs?id=${encoded(id)}").delete()) { }

    override suspend fun retry(id: String): HomeAnswer<Unit> =
        call("POST /jobs/retry?id=$id", post("jobs/retry?id=${encoded(id)}", "{}")) { }

    override suspend fun handOver(account: AccountHandover): HomeAnswer<Unit> = call(
        "PUT /account",
        Request.Builder().url(
            "$base/account"
        ).put(AskJson.encodeToString(AccountHandover.serializer(), account).json()),
    ) { }

    override suspend fun forgetAccount(): HomeAnswer<Unit> =
        call("DELETE /account", Request.Builder().url("$base/account").delete()) { }

    override fun fileUrl(id: String): HttpUrl = HttpUrl.of("$base/files?id=${encoded(id)}")

    private fun post(path: String, body: String) = Request.Builder().url("$base/$path").post(body.json())

    private suspend fun <T> call(what: String, builder: Request.Builder, serializer: KSerializer<T>): HomeAnswer<T> =
        call(what, builder) { AskJson.decodeFromString(serializer, it) }

    private suspend fun <T> call(what: String, builder: Request.Builder, read: (String) -> T): HomeAnswer<T> =
        withContext(Dispatchers.IO) {
            if (token().isBlank()) return@withContext HomeAnswer.No("❌ Not signed in to the home server")
            try {
                client.newCall(builder.header(HomeServerAuth.HEADER, token()).build()).execute().use { response ->
                    val text = response.body.string()
                    if (response.isSuccessful) {
                        runCatching { HomeAnswer.Ok(read(text)) }.getOrElse {
                            Diag.warn("home", "$what -> unreadable answer: ${text.take(LOGGED)}", it)
                            HomeAnswer.No("❌ The home server's answer made no sense")
                        }
                    } else {
                        Diag.warn("home", "$what -> HTTP ${response.code}: ${text.take(LOGGED)}")
                        HomeAnswer.No(errorOf(text) ?: "❌ The home server said no (HTTP ${response.code})")
                    }
                }
            } catch (e: IOException) {
                Diag.warn("home", "$what failed", e)
                HomeAnswer.No("❌ The home server didn't answer")
            }
        }

    private fun errorOf(text: String): String? =
        runCatching { AskJson.parseToJsonElement(text).jsonObject["error"]?.jsonPrimitive?.content }.getOrNull()

    private fun encoded(id: String) = URLEncoder.encode(id, Charsets.UTF_8).replace("+", "%20")

    private fun String.json(): RequestBody = toRequestBody(JSON)

    private companion object {
        val JSON = "application/json".toMediaType()
        const val LOGGED = 200
    }
}
