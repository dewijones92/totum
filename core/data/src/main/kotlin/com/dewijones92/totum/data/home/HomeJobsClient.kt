package com.dewijones92.totum.data.home

import com.dewijones92.totum.common.HttpUrl
import com.dewijones92.totum.data.ask.AskJson
import com.dewijones92.totum.data.backup.BackupItem
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.job
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.File
import java.net.URLEncoder

public sealed interface HomeAnswer<out T> {
    public data class Ok<T>(val value: T) : HomeAnswer<T>
    public data class No(val reason: String, val httpCode: Int? = null) : HomeAnswer<Nothing>
}

public interface HomeJobsApi {
    public suspend fun start(job: HomeJobRequest): HomeAnswer<String>
    public suspend fun jobs(): HomeAnswer<HomeJobs>
    public suspend fun cancel(id: String): HomeAnswer<Unit>
    public suspend fun retry(id: String): HomeAnswer<Unit>
    public suspend fun handOver(account: AccountHandover): HomeAnswer<Unit>
    public suspend fun forgetAccount(by: String?): HomeAnswer<Unit>
    public fun fileUrl(id: String): HttpUrl
    public suspend fun upload(
        item: BackupItem,
        audioOnly: Boolean,
        file: File,
        onSent: (sent: Long, total: Long) -> Unit,
    ): HomeAnswer<Unit>
}

@Suppress("TooManyFunctions")
public class HomeJobsClient(
    private val client: OkHttpClient,
    private val base: String,
    private val token: () -> String,
) : HomeJobsApi {
    private val pi = HomePiCall(client, token)

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

    override suspend fun forgetAccount(by: String?): HomeAnswer<Unit> {
        val query = by?.let { "?by=${encoded(it)}" }.orEmpty()
        return call("DELETE /account$query", Request.Builder().url("$base/account$query").delete()) { }
    }

    override fun fileUrl(id: String): HttpUrl = HttpUrl.of("$base/files?id=${encoded(id)}")

    override suspend fun upload(
        item: BackupItem,
        audioOnly: Boolean,
        file: File,
        onSent: (sent: Long, total: Long) -> Unit,
    ): HomeAnswer<Unit> {
        val job = currentCoroutineContext().job
        val body = HomeUploadBody(item, file, stillWanted = { job.isActive }, onSent = onSent)
        val url = "$base/files?id=${encoded(item.itemId)}&audioOnly=$audioOnly"
        return call("PUT /files?id=${item.itemId} (${file.length()} bytes)", Request.Builder().url(url).put(body)) { }
    }

    private fun post(path: String, body: String) = Request.Builder().url("$base/$path").post(body.json())

    private suspend fun <T> call(what: String, builder: Request.Builder, serializer: KSerializer<T>): HomeAnswer<T> =
        pi.call(what, builder, serializer)

    private suspend fun <T> call(what: String, builder: Request.Builder, read: (String) -> T): HomeAnswer<T> =
        pi.call(what, builder, read)

    private fun encoded(id: String) = URLEncoder.encode(id, Charsets.UTF_8).replace("+", "%20")

    private fun String.json(): RequestBody = toRequestBody(JSON)

    private companion object {
        val JSON = "application/json".toMediaType()
    }
}
