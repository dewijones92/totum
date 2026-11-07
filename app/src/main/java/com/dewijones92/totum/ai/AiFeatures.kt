package com.dewijones92.totum.ai

import android.content.Context
import com.dewijones92.totum.R
import com.dewijones92.totum.common.Diag
import com.dewijones92.totum.common.HomeServerHosts
import com.dewijones92.totum.data.ai.AiTasks
import com.dewijones92.totum.data.ai.HttpAiJobs
import com.dewijones92.totum.data.ask.AskOutcome
import com.dewijones92.totum.data.digest.DigestService
import com.dewijones92.totum.data.digest.FileDigestStore
import com.dewijones92.totum.data.digest.InMemoryDigestStore
import com.dewijones92.totum.data.morning.FileMorningFile
import com.dewijones92.totum.data.morning.InMemoryMorningFile
import com.dewijones92.totum.data.morning.MorningRun
import com.dewijones92.totum.data.morning.MorningStore
import com.dewijones92.totum.data.spoiler.FileShieldStore
import com.dewijones92.totum.data.spoiler.InMemoryShieldStore
import com.dewijones92.totum.data.spoiler.SpoilerAnswer
import com.dewijones92.totum.data.spoiler.SpoilerRequest
import com.dewijones92.totum.data.spoiler.SpoilerShield
import com.dewijones92.totum.di.AppContainer
import com.dewijones92.totum.domain.MediaItemId
import com.dewijones92.totum.notifications.HousekeepingNotifier
import kotlinx.coroutines.CoroutineScope
import okhttp3.OkHttpClient
import java.io.File

class AiFeatures(
    val spoilerShield: SpoilerShield,
    val digests: DigestService,
    val morningStore: MorningStore,
    val morningJob: MorningJob?,
) {
    companion object {
        fun fake(scope: CoroutineScope): AiFeatures = AiFeatures(
            SpoilerShield(
                store = InMemoryShieldStore(),
                check = { AskOutcome.Failed("no home server in this container") },
                watched = { false },
                scope = scope,
            ),
            DigestService({ null }, InMemoryDigestStore()),
            MorningStore(InMemoryMorningFile()),
            null,
        )
    }
}

internal fun homeAiJobs(c: AppContainer, client: OkHttpClient): HttpAiJobs? {
    val settings = c.appPreferences.settings.value
    val base = settings.homeServerBase.takeIf { it.isNotBlank() } ?: return null
    return HttpAiJobs(client, "https://${HomeServerHosts.app(base)}/fetch", {
        c.appPreferences.settings.value.homeServerToken
    })
}

internal fun aiFeaturesOf(
    c: AppContainer,
    context: Context,
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
    val morningStore = MorningStore(FileMorningFile(File(filesDir, "morning.json")))
    val forYouName = context.getString(R.string.for_you_playlist_name)
    val run = MorningRun(
        jobs = { homeAiJobs(c, client) },
        store = morningStore,
        taste = { appTaste(c) },
        have = { ownedTitles(c) },
        find = { pick, taste -> findPick(c.askCopies, c.podcastRepository, pick, taste) },
        replaceForYou = { items -> replaceForYou(c, forYouName, items) },
        titleOf = { entry -> if (entry.shielded) spoilers.safeTitleOf(entry.itemId) else entry.title },
    )
    return AiFeatures(spoilers, digests, morningStore, MorningJob(context, run, HousekeepingNotifier(context)))
}
