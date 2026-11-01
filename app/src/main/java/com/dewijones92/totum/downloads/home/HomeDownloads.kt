package com.dewijones92.totum.downloads.home

import com.dewijones92.totum.common.Diag
import com.dewijones92.totum.data.backup.toBackup
import com.dewijones92.totum.data.backup.toPlayable
import com.dewijones92.totum.data.download.DownloadManager
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
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

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
    val copyOnWifi: () -> Boolean = { true },
    val kept: KeptAtHome = InMemoryKeptAtHome(),
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
        HomeReporting(operations, notices) { id -> cancel(id) },
        phone,
        scope,
        pacing.pollMs
    )
    private val phoneDownloads = downloads
    val kept: StateFlow<Set<String>> get() = phone.kept.ids
    private val handover = SignInHandover(phone.youTubeTokens)
    private val wake = Channel<String>(Channel.CONFLATED)

    fun watch() {
        if (client == null) return
        scope.launch {
            var why = "tick"
            while (true) {
                refresh(why)
                val pause = if (known.value.values.any { it.state.busy() }) pacing.pollMs else pacing.idleMs
                why = withTimeoutOrNull(pause) { wake.receive() } ?: "tick"
            }
        }
        pacing.refreshWhen?.let { changes -> scope.launch { changes.collect { refresh("network changed") } } }
    }

    fun start(item: PlayableItem, choice: DownloadChoice?) {
        val home = client ?: return Diag.warn(TAG, "\"${item.item.title}\": no home server; not sent home")
        scope.launch {
            if (item.handle is PlayHandle.Video) handover.ensure(home)
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
                    wake.trySend("following ${answer.value}")
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

    val copiesByItself: Boolean get() = phone.copyOnWifi()

    fun copyNow(id: MediaItemId) {
        val job = known.value[id]?.takeIf { it.state == HomeJobState.DONE } ?: return
        val item = job.item.toPlayable() ?: return
        phone.kept.release(id.value)
        Diag.log(TAG, "\"${job.item.title}\": copy to the phone now (asked; whatever the network)")
        scope.launch { phoneDownloads.download(item, audioOnly = job.audioOnly) }
    }

    fun deleteOnPhoneOnly(id: MediaItemId) {
        phone.kept.keep(id.value)
        Diag.log(TAG, "${id.value}: deleted from the phone only; kept at home and not copied back by itself")
        scope.launch { phoneDownloads.delete(id) }
    }

    fun retry(id: MediaItemId) = act("retry", id) { client?.retry(it) }

    fun forgetAccount() {
        handover.forget()
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

    private fun act(what: String, id: MediaItemId, call: suspend (String) -> HomeAnswer<Unit>?) {
        if (known.value[id] == null) return
        scope.launch {
            Diag.log(TAG, "$what ${id.value} at home: ${call(id.value)}")
            refresh(what)
            wake.trySend("after $what")
        }
    }
}

internal const val TAG = "home"
internal const val PERCENT = 100

internal fun HomeJob.percent(): Int = totalBytes?.takeIf { it > 0 }?.let { (bytes * PERCENT / it).toInt() } ?: 0

internal fun HomeJob.describe(): String =
    "${item.title}=${state.name.lowercase()}${if (state.busy()) " ${percent()}%" else ""}"

internal fun HomeJobState.busy(): Boolean = this == HomeJobState.WAITING || this == HomeJobState.DOWNLOADING
