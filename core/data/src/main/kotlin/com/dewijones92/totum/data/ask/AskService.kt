package com.dewijones92.totum.data.ask

import com.dewijones92.totum.common.Diag
import com.dewijones92.totum.common.HomeServerAuth
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException

public interface AskService {
    public suspend fun ask(request: AskRequest, progress: (elapsedMs: Long) -> Unit = {}): AskOutcome<AskAnswer>
    public suspend fun vet(request: VetRequest, progress: (elapsedMs: Long) -> Unit = {}): AskOutcome<VetAnswer>
}

public sealed interface AskOutcome<out T> {
    public data class Answered<T>(val value: T, val elapsedMs: Long) : AskOutcome<T>
    public data class Failed(val reason: String) : AskOutcome<Nothing>
}

public class HttpAskService(
    private val client: OkHttpClient,
    private val base: String,
    private val token: () -> String,
    private val pollMs: Long = POLL_MS,
    private val giveUpMs: Long = GIVE_UP_MS,
    private val clock: () -> Long = System::currentTimeMillis,
) : AskService {

    override suspend fun ask(request: AskRequest, progress: (Long) -> Unit): AskOutcome<AskAnswer> =
        run("ask", AskJson.encodeToString(AskRequest.serializer(), request), progress) { it.ask }

    override suspend fun vet(request: VetRequest, progress: (Long) -> Unit): AskOutcome<VetAnswer> =
        run("vet", AskJson.encodeToString(VetRequest.serializer(), request), progress) { it.vet }

    private suspend fun <T> run(
        path: String,
        body: String,
        progress: (Long) -> Unit,
        answer: (AskJob) -> T?,
    ): AskOutcome<T> {
        if (token().isBlank()) return AskOutcome.Failed("❌ Not signed in to the home server")
        val started = post(path, body) ?: return AskOutcome.Failed("❌ The home server didn't answer")
        Diag.log("ask", "$path job ${started.id} started")
        var job = started
        val began = clock()
        while (job.state == JobState.RUNNING) {
            val waited = clock() - began
            if (waited >= giveUpMs) {
                Diag.warn("ask", "$path job ${job.id} still running after ${waited}ms; giving up")
                return AskOutcome.Failed("❌ The home server took too long")
            }
            delay(pollMs)
            job = poll(job.id) ?: job
            progress(job.elapsedMs)
        }
        val value = answer(job)
        return when {
            job.state == JobState.DONE && value != null -> AskOutcome.Answered(value, job.elapsedMs)
            else -> {
                Diag.warn("ask", "$path job ${job.id} ${job.state}: ${job.error}")
                AskOutcome.Failed("❌ ${job.error ?: "No answer"}")
            }
        }
    }

    private suspend fun post(path: String, body: String): AskJob? = call(
        Request.Builder().url("$base/$path").post(body.toRequestBody(JSON)),
        "POST /$path",
    )

    private suspend fun poll(id: String): AskJob? = call(Request.Builder().url("$base/job/$id").get(), "GET /job/$id")

    private suspend fun call(builder: Request.Builder, what: String): AskJob? = withContext(Dispatchers.IO) {
        val request = builder.header(HomeServerAuth.HEADER, token()).build()
        try {
            client.newCall(request).execute().use { response ->
                val text = response.body.string()
                if (!response.isSuccessful) {
                    Diag.warn("ask", "$what -> HTTP ${response.code}: ${text.take(LOGGED_CHARS)}")
                    null
                } else {
                    runCatching { AskJson.decodeFromString(AskJob.serializer(), text) }
                        .onFailure { Diag.warn("ask", "$what -> unreadable answer: ${text.take(LOGGED_CHARS)}", it) }
                        .getOrNull()
                }
            }
        } catch (e: IOException) {
            Diag.warn("ask", "$what failed", e)
            null
        }
    }

    private companion object {
        val JSON = "application/json".toMediaType()
        const val POLL_MS = 1_500L
        const val GIVE_UP_MS = 330_000L
        const val LOGGED_CHARS = 200
    }
}
