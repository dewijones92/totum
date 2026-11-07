package com.dewijones92.totum.data.ai

import com.dewijones92.totum.common.Diag
import com.dewijones92.totum.data.ask.AskJson
import com.dewijones92.totum.data.ask.AskOutcome
import com.dewijones92.totum.data.ask.JOB_LOST_MESSAGE
import com.dewijones92.totum.data.ask.JobState
import com.dewijones92.totum.data.home.HomeAnswer
import com.dewijones92.totum.data.home.HomePiCall
import kotlinx.coroutines.delay
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

public object AiTasks {
    public const val SPOILER: String = "spoiler"
    public const val TRANSCRIPT_DIGEST: String = "transcript-digest"
    public const val TRANSCRIPT_ASK: String = "transcript-ask"
    public const val FOR_YOU: String = "for-you"
    public const val MORNING_DIGEST: String = "morning-digest"

    public fun pathOf(task: String): String = "/ai/$task"

    public fun jobPathOf(id: String): String = "/ai/job/$id"
}

@Serializable
public data class AiJobStatus(
    val id: String,
    val state: JobState,
    val elapsedMs: Long = 0,
    val result: JsonObject? = null,
    val error: String? = null,
)

public class HttpAiJobs(
    client: OkHttpClient,
    private val base: String,
    token: () -> String,
    private val pollMs: Long = POLL_MS,
    private val giveUpMs: Long = GIVE_UP_MS,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val pi = HomePiCall(client, token, "ai-job")

    public suspend fun <Q, A> run(
        task: String,
        request: Q,
        requestSerializer: KSerializer<Q>,
        answerSerializer: KSerializer<A>,
        progress: (elapsedMs: Long) -> Unit = {},
    ): AskOutcome<A> {
        val body = AskJson.encodeToString(requestSerializer, request).toRequestBody(JSON)
        val path = AiTasks.pathOf(task)
        val first = when (
            val posted = pi.call("POST $path", Request.Builder().url(base + path).post(body), AiJobStatus.serializer())
        ) {
            is HomeAnswer.Ok -> posted.value
            is HomeAnswer.No -> return AskOutcome.Failed(posted.reason)
        }
        Diag.log("ai-job", "$task job ${first.id} started")
        val last = when (val ended = settled(task, first, progress)) {
            Ended.TimedOut -> return AskOutcome.Failed("❌ The home server took too long")
            Ended.Lost -> return AskOutcome.Failed(JOB_LOST_MESSAGE)
            is Ended.Settled -> ended.status
        }
        val answer = last.result?.takeIf { last.state == JobState.DONE }?.let {
            runCatching { AskJson.decodeFromJsonElement(answerSerializer, it) }
                .onFailure { e -> Diag.warn("ai-job", "$task job ${last.id}: the answer did not fit", e) }
                .getOrNull()
        }
        if (answer != null) return AskOutcome.Answered(answer, last.elapsedMs)
        Diag.warn("ai-job", "$task job ${last.id} ${last.state}: ${last.error}")
        return AskOutcome.Failed("❌ ${last.error ?: "No answer"}")
    }

    private sealed interface Ended {
        data object TimedOut : Ended

        data object Lost : Ended

        data class Settled(val status: AiJobStatus) : Ended
    }

    private suspend fun settled(task: String, first: AiJobStatus, progress: (Long) -> Unit): Ended {
        var status = first
        val began = clock()
        while (status.state == JobState.RUNNING) {
            val waited = clock() - began
            if (waited >= giveUpMs) {
                Diag.warn("ai-job", "$task job ${status.id} still running after ${waited}ms; giving up")
                return Ended.TimedOut
            }
            delay(pollMs)
            val path = AiTasks.jobPathOf(status.id)
            val polled = pi.call("GET $path", Request.Builder().url(base + path).get(), AiJobStatus.serializer())
            if (polled is HomeAnswer.No && polled.httpCode == HTTP_NOT_FOUND) {
                Diag.warn(
                    "ai-job",
                    "$task job ${status.id}: the home server no longer knows it (HTTP 404); it restarted, so the job is lost",
                )
                return Ended.Lost
            }
            status = (polled as? HomeAnswer.Ok)?.value ?: status
            progress(status.elapsedMs)
        }
        return Ended.Settled(status)
    }

    private companion object {
        val JSON = "application/json".toMediaType()
        const val POLL_MS = 1_500L
        const val GIVE_UP_MS = 330_000L
        const val HTTP_NOT_FOUND = 404
    }
}
