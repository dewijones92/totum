package com.dewijones92.totum.downloads.home

import com.dewijones92.totum.common.Diag
import com.dewijones92.totum.data.backup.toBackup
import com.dewijones92.totum.data.download.DownloadManager
import com.dewijones92.totum.data.home.AccountHandover
import com.dewijones92.totum.data.home.HomeAnswer
import com.dewijones92.totum.data.home.HomeJob
import com.dewijones92.totum.data.home.HomeJobRequest
import com.dewijones92.totum.data.home.HomeJobState
import com.dewijones92.totum.data.home.HomeJobsApi
import com.dewijones92.totum.data.home.PhoneDecoder
import com.dewijones92.totum.domain.HomeCopy
import com.dewijones92.totum.domain.MediaItemId
import com.dewijones92.totum.domain.PlayHandle
import com.dewijones92.totum.domain.PlayableItem
import com.dewijones92.totum.innertube.auth.OAuthTokens
import com.dewijones92.totum.operations.Operations
import com.dewijones92.totum.settings.DownloadChoice
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

interface HomeNotices {
    fun started(title: String)
    fun ready(title: String)
    fun refused(title: String, why: String)
}

object NoHomeNotices : HomeNotices {
    override fun started(title: String) = Unit
    override fun ready(title: String) = Unit
    override fun refused(title: String, why: String) = Unit
}

class PhoneSide(
    val youTubeTokens: suspend () -> OAuthTokens?,
    val decoders: () -> List<PhoneDecoder>,
    val unmetered: () -> Boolean,
)

class Pacing(
    val pollMs: Long = POLL_MS,
    val idleMs: Long = IDLE_MS,
    val refreshWhen: Flow<*>? = null,
) {
    private companion object {
        const val POLL_MS = 20_000L
        const val IDLE_MS = 10 * 60_000L
    }
}

class HomeDownloads(
    private val client: HomeJobsApi?,
    private val phone: PhoneSide,
    downloads: DownloadManager,
    operations: Operations,
    private val notices: HomeNotices,
    private val scope: CoroutineScope,
    private val pacing: Pacing = Pacing(),
) {
    private val known = MutableStateFlow<Map<MediaItemId, HomeJob>>(emptyMap())
    val jobs: StateFlow<Map<MediaItemId, HomeJob>> = known.asStateFlow()
    val available: Boolean get() = client != null

    private val follower = HomeFollower(
        known,
        downloads,
        HomeReporting(operations, notices),
        phone.unmetered,
        scope,
        pacing.pollMs
    )
    private var handedOver = false

    fun watch() {
        if (client == null) return
        scope.launch {
            while (true) {
                refresh("tick")
                delay(if (known.value.values.any { it.state.busy() }) pacing.pollMs else pacing.idleMs)
            }
        }
        pacing.refreshWhen?.let { changes -> scope.launch { changes.collect { refresh("network changed") } } }
    }

    fun start(item: PlayableItem, choice: DownloadChoice?) {
        val home = client ?: return Diag.warn(TAG, "\"${item.item.title}\": no home server; not sent home")
        scope.launch {
            if (item.handle is PlayHandle.Video) handOver(home)
            val request = HomeJobRequest(
                item.toBackup(),
                audioOnly = choice == DownloadChoice.Sound,
                maxHeight = (choice as? DownloadChoice.Video)?.maxHeight,
                decoders = phone.decoders(),
            )
            Diag.log(TAG, "\"${item.item.title}\": sending home as ${choice?.stored ?: "the whole file"}")
            when (val answer = home.start(request)) {
                is HomeAnswer.Ok -> {
                    notices.started(item.item.title)
                    refresh("started ${answer.value}")
                }
                is HomeAnswer.No -> {
                    Diag.warn(TAG, "\"${item.item.title}\": the home server refused: ${answer.reason}")
                    notices.refused(item.item.title, answer.reason)
                }
            }
        }
    }

    fun atHome(item: PlayableItem): HomeCopy? {
        val home = client ?: return null
        val job = known.value[item.item.id]?.takeIf { it.state == HomeJobState.DONE } ?: return null
        return HomeCopy(home.fileUrl(job.id), job.audioOnly)
    }

    fun cancel(id: MediaItemId) = act("cancel", id) { client?.cancel(it) }

    fun retry(id: MediaItemId) = act("retry", id) { client?.retry(it) }

    fun forgetAccount() {
        handedOver = false
        scope.launch {
            val answer = client?.forgetAccount() ?: return@launch
            Diag.log(TAG, "asked home to forget the YouTube sign-in: $answer")
        }
    }

    suspend fun refresh(why: String) {
        val home = client ?: return
        when (val answer = home.jobs()) {
            is HomeAnswer.No -> Diag.log(TAG, "jobs not read ($why): ${answer.reason}")
            is HomeAnswer.Ok -> {
                val now = answer.value.jobs.associateBy { MediaItemId(it.id) }
                if (now != known.value) Diag.log(TAG, "jobs ($why): ${now.values.joinToString { it.describe() }}")
                known.value = now
                follower.followAll(now.values)
            }
        }
    }

    private suspend fun handOver(home: HomeJobsApi) {
        if (handedOver) return
        val tokens = phone.youTubeTokens()
            ?: return Diag.log(TAG, "not signed in to YouTube here; nothing to hand over")
        val answer = home.handOver(
            AccountHandover(tokens.accessToken.value, tokens.refreshToken.value, tokens.expiresAtEpochSeconds),
        )
        handedOver = answer is HomeAnswer.Ok
        Diag.log(TAG, "YouTube sign-in handed to home: ${if (handedOver) "ok" else answer}")
    }

    private fun act(what: String, id: MediaItemId, call: suspend (String) -> HomeAnswer<Unit>?) {
        if (known.value[id] == null) return
        scope.launch {
            Diag.log(TAG, "$what ${id.value} at home: ${call(id.value)}")
            refresh(what)
        }
    }
}

internal const val TAG = "home"
internal const val PERCENT = 100

internal fun HomeJob.percent(): Int = totalBytes?.takeIf { it > 0 }?.let { (bytes * PERCENT / it).toInt() } ?: 0

internal fun HomeJob.describe(): String =
    "${item.title}=${state.name.lowercase()}${if (state.busy()) " ${percent()}%" else ""}"

internal fun HomeJobState.busy(): Boolean = this == HomeJobState.WAITING || this == HomeJobState.DOWNLOADING
