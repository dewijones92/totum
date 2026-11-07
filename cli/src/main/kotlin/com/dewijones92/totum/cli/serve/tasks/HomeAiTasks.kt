package com.dewijones92.totum.cli.serve.tasks

import com.dewijones92.totum.cli.serve.ai.ClaudeCodeCompleter
import com.dewijones92.totum.cli.serve.ai.Completer
import com.dewijones92.totum.cli.serve.ai.RemoteCompleter
import com.dewijones92.totum.common.Diag
import com.dewijones92.totum.data.ai.AiTasks
import com.dewijones92.totum.data.ask.AskJson
import com.dewijones92.totum.data.digest.TranscriptAnswer
import com.dewijones92.totum.data.digest.TranscriptAskRequest
import com.dewijones92.totum.data.digest.TranscriptDigest
import com.dewijones92.totum.data.digest.TranscriptDigestRequest
import com.dewijones92.totum.data.morning.ForYouAnswer
import com.dewijones92.totum.data.morning.ForYouRequest
import com.dewijones92.totum.data.morning.MorningDigestAnswer
import com.dewijones92.totum.data.morning.MorningDigestRequest
import com.dewijones92.totum.data.spoiler.SpoilerAnswer
import com.dewijones92.totum.data.spoiler.SpoilerRequest
import kotlinx.coroutines.CoroutineScope
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.jsonObject
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

internal class HomeAiTasks(private val runner: AiTaskRunner) {

    fun tasks(): Map<String, AiTask> = mapOf(
        AiTasks.SPOILER to task(SpoilerRequest.serializer(), SpoilerAnswer.serializer(), ::spoiler),
        AiTasks.TRANSCRIPT_DIGEST to task(
            TranscriptDigestRequest.serializer(),
            TranscriptDigest.serializer(),
            ::transcriptDigest,
        ),
        AiTasks.TRANSCRIPT_ASK to task(
            TranscriptAskRequest.serializer(),
            TranscriptAnswer.serializer(),
            ::transcriptAnswer,
        ),
        AiTasks.FOR_YOU to task(ForYouRequest.serializer(), ForYouAnswer.serializer(), ::forYou),
        AiTasks.MORNING_DIGEST to task(
            MorningDigestRequest.serializer(),
            MorningDigestAnswer.serializer(),
            ::morningDigest,
        ),
    )

    private suspend fun forYou(request: ForYouRequest): ForYouAnswer {
        val raw = runner.run(MorningPrompts.forYouPrompt(request), ForYouAnswer.serializer())
        return MorningPrompts.enforcedForYou(request, raw)
    }

    private suspend fun morningDigest(request: MorningDigestRequest): MorningDigestAnswer {
        val raw = runner.run(MorningPrompts.digestPrompt(request), MorningDigestAnswer.serializer())
        return MorningPrompts.enforcedDigest(request, raw)
    }

    private suspend fun transcriptDigest(request: TranscriptDigestRequest): TranscriptDigest {
        val raw = runner.run(TranscriptPrompts.digestPrompt(request), TranscriptDigest.serializer())
        return TranscriptPrompts.enforcedDigest(request, raw)
    }

    private suspend fun transcriptAnswer(request: TranscriptAskRequest): TranscriptAnswer {
        val raw = runner.run(TranscriptPrompts.askPrompt(request), TranscriptAnswer.serializer())
        return TranscriptPrompts.enforcedAnswer(request, raw)
    }

    private suspend fun spoiler(request: SpoilerRequest): SpoilerAnswer {
        val raw = runner.run(SpoilerPrompts.prompt(request), SpoilerAnswer.serializer())
        return SpoilerPrompts.enforced(request, raw)
    }

    private fun <Q, A> task(
        requestSerializer: KSerializer<Q>,
        answerSerializer: KSerializer<A>,
        work: suspend (Q) -> A,
    ): AiTask = AiTask { body ->
        val request = AskJson.decodeFromString(requestSerializer, body)
        AskJson.encodeToJsonElement(answerSerializer, work(request)).jsonObject
    }
}

internal fun aiCompleterFromEnv(env: (String) -> String? = System::getenv): Completer {
    val remote = env("TOTUM_AI_URL")
    return if (remote != null) {
        RemoteCompleter(remote, OkHttpClient.Builder().readTimeout(READ_TIMEOUT_S, TimeUnit.SECONDS).build())
    } else {
        ClaudeCodeCompleter(env("TOTUM_CLAUDE") ?: "claude", env("TOTUM_ASK_MODEL") ?: "haiku")
    }
}

internal fun aiTaskRoutesFrom(scope: CoroutineScope, completer: Completer = aiCompleterFromEnv()): AiTaskRoutes {
    val tasks = HomeAiTasks(AiTaskRunner(completer)).tasks()
    Diag.log("ai-task", "AI tasks served: ${tasks.keys.sorted()}")
    return AiTaskRoutes(tasks, scope)
}

private const val READ_TIMEOUT_S = 330L
