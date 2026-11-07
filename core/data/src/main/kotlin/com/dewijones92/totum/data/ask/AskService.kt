package com.dewijones92.totum.data.ask

import com.dewijones92.totum.common.Diag
import com.dewijones92.totum.data.ask.state.StateReceipt
import com.dewijones92.totum.data.home.HomeAnswer
import com.dewijones92.totum.data.home.HomePiCall
import kotlinx.coroutines.delay
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.net.URLEncoder

public interface AskService {
    public suspend fun ask(request: AskRequest, progress: (elapsedMs: Long) -> Unit = {}): AskOutcome<AskAnswer>
    public suspend fun vet(request: VetRequest, progress: (elapsedMs: Long) -> Unit = {}): AskOutcome<VetAnswer>

    public suspend fun uploadState(id: String, packed: ByteArray): AskOutcome<StateReceipt> =
        AskOutcome.Failed("❌ This build cannot send your app's data")
}

public const val JOB_LOST_MESSAGE: String = "⚠️ The home server restarted; ask again"

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
    private val pi = HomePiCall(client, token, "ask")

    override suspend fun ask(request: AskRequest, progress: (Long) -> Unit): AskOutcome<AskAnswer> =
        run("ask", AskJson.encodeToString(AskRequest.serializer(), request), progress) { it.ask }

    override suspend fun vet(request: VetRequest, progress: (Long) -> Unit): AskOutcome<VetAnswer> =
        run("vet", AskJson.encodeToString(VetRequest.serializer(), request), progress) { it.vet }

    override suspend fun uploadState(id: String, packed: ByteArray): AskOutcome<StateReceipt> {
        val began = clock()
        val request = Request.Builder().url("$base/ask-state?id=${URLEncoder.encode(id, Charsets.UTF_8)}")
            .header("Content-Encoding", "gzip")
            .put(packed.toRequestBody(OCTETS))
        val what = "PUT /ask-state ($id, ${packed.size} bytes packed)"
        return when (val answer = pi.call(what, request, StateReceipt.serializer())) {
            is HomeAnswer.Ok -> {
                Diag.log("ask", "state $id uploaded: ${packed.size} bytes packed in ${clock() - began}ms")
                AskOutcome.Answered(answer.value, clock() - began)
            }
            is HomeAnswer.No -> AskOutcome.Failed(answer.reason)
        }
    }

    private suspend fun <T> run(
        path: String,
        body: String,
        progress: (Long) -> Unit,
        answer: (AskJob) -> T?,
    ): AskOutcome<T> {
        val started = when (val answer = post(path, body)) {
            is HomeAnswer.Ok -> answer.value
            is HomeAnswer.No -> return AskOutcome.Failed(answer.reason)
        }
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
            val polled = poll(job.id)
            if (polled is HomeAnswer.No && polled.httpCode == HTTP_NOT_FOUND) {
                Diag.warn(
                    "ask",
                    "$path job ${job.id}: the home server no longer knows it (HTTP 404), so it restarted " +
                        "and the job is lost",
                )
                return AskOutcome.Failed(JOB_LOST_MESSAGE)
            }
            job = (polled as? HomeAnswer.Ok)?.value ?: job
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

    private suspend fun post(path: String, body: String): HomeAnswer<AskJob> = pi.call(
        "POST /$path",
        Request.Builder().url("$base/$path").post(body.toRequestBody(JSON)),
        AskJob.serializer(),
    )

    private suspend fun poll(id: String): HomeAnswer<AskJob> =
        pi.call("GET /job/$id", Request.Builder().url("$base/job/$id").get(), AskJob.serializer())

    private companion object {
        val JSON = "application/json".toMediaType()
        val OCTETS = "application/octet-stream".toMediaType()
        const val POLL_MS = 1_500L
        const val GIVE_UP_MS = 330_000L
        const val HTTP_NOT_FOUND = 404
    }
}
