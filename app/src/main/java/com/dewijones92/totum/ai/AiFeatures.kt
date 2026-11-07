package com.dewijones92.totum.ai

import com.dewijones92.totum.common.Diag
import com.dewijones92.totum.data.ai.AiTasks
import com.dewijones92.totum.data.ai.HttpAiJobs
import com.dewijones92.totum.data.ask.AskOutcome
import com.dewijones92.totum.data.digest.DigestService
import com.dewijones92.totum.data.digest.FileDigestStore
import com.dewijones92.totum.data.digest.InMemoryDigestStore
import com.dewijones92.totum.data.spoiler.FileShieldStore
import com.dewijones92.totum.data.spoiler.InMemoryShieldStore
import com.dewijones92.totum.data.spoiler.SpoilerAnswer
import com.dewijones92.totum.data.spoiler.SpoilerRequest
import com.dewijones92.totum.data.spoiler.SpoilerShield
import com.dewijones92.totum.di.AppContainer
import com.dewijones92.totum.domain.MediaItemId
import kotlinx.coroutines.CoroutineScope
import okhttp3.OkHttpClient
import java.io.File

class AiFeatures(val spoilerShield: SpoilerShield, val digests: DigestService) {
    companion object {
        fun fake(scope: CoroutineScope): AiFeatures = AiFeatures(
            SpoilerShield(
                store = InMemoryShieldStore(),
                check = { AskOutcome.Failed("no home server in this container") },
                watched = { false },
                scope = scope,
            ),
            DigestService({ null }, InMemoryDigestStore()),
        )
    }
}

internal fun homeAiJobs(c: AppContainer, client: OkHttpClient): HttpAiJobs? {
    val settings = c.appPreferences.settings.value
    val base = settings.homeServerBase.takeIf { it.isNotBlank() } ?: return null
    return HttpAiJobs(client, "https://totum.$base/fetch", { c.appPreferences.settings.value.homeServerToken })
}

internal fun aiFeaturesOf(
    c: AppContainer,
    client: OkHttpClient,
    filesDir: File,
    watched: (MediaItemId) -> Boolean,
): AiFeatures {
    val spoilers = SpoilerShield(
        store = FileShieldStore(File(filesDir, "spoiler-shield.json")),
        check = { batch ->
            val jobs = homeAiJobs(c, client)
            if (jobs == null) {
                Diag.warn("spoiler", "no home server is set up, so ${batch.size} title(s) cannot be checked")
                AskOutcome.Failed("❌ No home server")
            } else {
                jobs.run(
                    AiTasks.SPOILER,
                    SpoilerRequest(batch),
                    SpoilerRequest.serializer(),
                    SpoilerAnswer.serializer(),
                )
            }
        },
        watched = watched,
        scope = c.applicationScope,
    )
    val digests = DigestService({ homeAiJobs(c, client) }, FileDigestStore(File(filesDir, "digests.json")))
    return AiFeatures(spoilers, digests)
}
