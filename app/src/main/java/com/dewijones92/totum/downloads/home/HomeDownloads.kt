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
import com.dewijones92.totum.data.home.HomeProblem
import com.dewijones92.totum.data.home.busy
import com.dewijones92.totum.data.home.PhoneDecoder
import com.dewijones92.totum.data.targets.Requester
import com.dewijones92.totum.domain.HomeCopy
import com.dewijones92.totum.domain.ItemShields
import com.dewijones92.totum.domain.MediaItemId
import com.dewijones92.totum.domain.PlayHandle
import com.dewijones92.totum.domain.PlayableItem
import com.dewijones92.totum.domain.StorageUsage
import com.dewijones92.totum.domain.titleOf
import com.dewijones92.totum.innertube.auth.OAuthTokens
import com.dewijones92.totum.operations.Operations
import com.dewijones92.totum.settings.DownloadChoice
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File

interface HomeNotices {
    fun started(title: String)
    fun ready(title: String)
    fun refused(title: String, why: String)
    fun problem(key: String, text: String)
    fun uploading(title: String, percent: Int?) = Unit
    fun uploadStopped(title: String) = Unit
}

object NoHomeNotices : HomeNotices {
    override fun started(title: String) = Unit
    override fun ready(title: String) = Unit
    override fun problem(key: String, text: String) = Unit
    override fun refused(title: String, why: String) = Unit
}

class PhoneSide(
    val youTubeTokens: suspend () -> OAuthTokens?,
    val decoders: () -> List<PhoneDecoder>,
    val unmetered: () -> Boolean,
    val copyOnWifi: () -> Boolean = { false },
    val kept: KeptAtHome = InMemoryKeptAtHome(),
    val moving: KeptAtHome = InMemoryKeptAtHome(),
    val identity: suspend () -> Requester = { Requester("unknown", "This phone") },
)

class Pacing(
    val pollMs: Long = POLL_MS,
    val idleMs: Long = IDLE_MS,
    val refreshWhen: Flow<*>? = null,
) {
    private companion object {
        const val POLL_MS = 2_000L
        const val IDLE_MS = 10 * 60_000L
    }
}

@Suppress("TooManyFunctions")
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
    private val read = MutableStateFlow<Map<MediaItemId, HomeJob>?>(null)
    val jobsRead: StateFlow<Map<MediaItemId, HomeJob>?> = read.asStateFlow()
    private val status = HomeStatus(notices)
    val problems: StateFlow<List<HomeProblem>> get() = status.problems
    val storage: StateFlow<StorageUsage?> get() = status.storage
    val available: Boolean get() = client != null

    private val follower = HomeFollower(
        known,
        downloads,
        HomeReporting(operations, notices) { id -> cancel(id) },
        phone,
        scope,
    )
    private val phoneDownloads = downloads
    val kept: StateFlow<Set<String>> get() = phone.kept.ids
    private val handover =
        SignInHandover(phone.youTubeTokens, phone.identity) { text -> notices.problem("youtube-owner", text) }
    private var uploadLimitBytes = 0L
    private val wake = Channel<String>(Channel.CONFLATED)
    private val moves = HomeMoves(phone.moving) { deleteOnPhoneOnly(it) }
    private val uploader = PhoneUploads(client, operations, notices, moves) { refresh(it) }

    fun watch() {
        if (client == null) return
        scope.launch {
            var why = "tick"
            while (true) {
                refresh(why)
                val pause = if (known.value.values.any { it.busy }) pacing.pollMs else pacing.idleMs
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
                    notices.started(ItemShields.titleOf(item.item))
                    refresh("started ${answer.value}")
                    wake.trySend("following ${answer.value}")
                }
                is HomeAnswer.No -> {
                    moves.refused(item.item)
                    Diag.warn(TAG, "\"${item.item.title}\": the home server refused: ${answer.reason}")
                    notices.refused(ItemShields.titleOf(item.item), answer.reason)
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

    fun sendFromPhone(id: MediaItemId, move: Boolean) {
        scope.launch {
            val copy = phoneDownloads.observeDownloaded().first().firstOrNull { it.item.id == id }
            if (copy == null) {
                Diag.warn(TAG, "${id.value}: not on the phone, so nothing to ${if (move) "move" else "copy"} home")
                return@launch
            }
            if (move) moves.begin(id) else moves.copyOnly(id)
            val variant = if (copy.audioOnly) "sound" else "video"
            val file = File(copy.localPath).takeIf { it.isFile }
            val how = if (file != null) "by sending the file" else "by asking the Pi to fetch it from the web"
            Diag.log(
                TAG,
                "\"${copy.item.title}\": ${if (move) "moving" else "copying"} the phone's $variant copy to the Pi " +
                    "$how (file ${copy.localPath} ${if (file != null) "is there" else "is not there"})",
            )
            if (file != null) {
                uploader.send(copy, file, move, uploadLimitBytes)
            } else {
                start(copy.playable, if (copy.audioOnly) DownloadChoice.Sound else DownloadChoice.Video(null))
            }
        }
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
            val by = phone.identity().id
            val answer = client?.forgetAccount(by) ?: return@launch
            Diag.log(TAG, "asked home to forget the YouTube sign-in (as $by): $answer")
            if (answer is HomeAnswer.No) notices.problem("youtube-owner", answer.reason)
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
                read.value = now
                uploadLimitBytes = answer.value.uploadLimitBytes
                status.read(answer.value)
                moves.finish(now)
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
    "${item.title}=${state.name.lowercase()}${if (busy) " ${percent()}%" else ""}" +
        (retry?.let { " retry ${it.attempt} of ${it.of} at ${it.atEpochMs}" } ?: "")
