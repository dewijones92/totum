package com.dewijones92.totum.downloads.home

import com.dewijones92.totum.common.Diag
import com.dewijones92.totum.data.backup.titleOf
import com.dewijones92.totum.data.backup.toPlayable
import com.dewijones92.totum.data.download.DownloadManager
import com.dewijones92.totum.data.home.HomeJob
import com.dewijones92.totum.data.home.HomeJobState
import com.dewijones92.totum.data.home.busy
import com.dewijones92.totum.domain.DownloadState
import com.dewijones92.totum.domain.ItemShields
import com.dewijones92.totum.domain.MediaItemId
import com.dewijones92.totum.operations.OperationEnd
import com.dewijones92.totum.operations.OperationKind
import com.dewijones92.totum.operations.Operations
import com.dewijones92.totum.operations.Retrying
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap

internal class HomeReporting(
    val operations: Operations,
    val notices: HomeNotices,
    val cancelAtHome: (MediaItemId) -> Unit,
)

internal class HomeFollower(
    private val known: StateFlow<Map<MediaItemId, HomeJob>>,
    private val downloads: DownloadManager,
    private val reporting: HomeReporting,
    private val phone: PhoneSide,
    private val scope: CoroutineScope,
) {
    private val tracked = ConcurrentHashMap.newKeySet<String>()
    private val seenBusy = ConcurrentHashMap.newKeySet<String>()
    private val announced = ConcurrentHashMap.newKeySet<String>()

    suspend fun followAll(jobs: Collection<HomeJob>) {
        jobs.forEach { job ->
            if (job.busy) {
                seenBusy.add(job.id)
                if (tracked.add(job.id)) strip(job)
            }
            if (job.state == HomeJobState.DONE && job.id in seenBusy && announced.add(job.id)) {
                reporting.notices.ready(ItemShields.titleOf(job.item))
            }
        }
        copyWhatIsReady()
    }

    private fun strip(job: HomeJob) = scope.launch {
        var finished = false
        try {
            reporting.operations.track(
                "home:${job.id}",
                OperationKind.HOME_DOWNLOAD,
                ItemShields.titleOf(job.item),
            ) { progress ->
                val end = known.map { it[MediaItemId(job.id)] }
                    .onEach {
                        progress.report(step = it?.percent() ?: 0, steps = PERCENT)
                        progress.retrying(
                            it?.retry?.let { r -> Retrying(r.attempt, r.of, r.atEpochMs, it.error.orEmpty()) }
                        )
                    }
                    .mapNotNull { endOf(it) }
                    .first()
                finished = true
                end
            }
        } finally {
            tracked.remove(job.id)
            if (!finished) {
                Diag.log(TAG, "\"${job.item.title}\": stopped from the strip; cancelling it at home")
                reporting.cancelAtHome(MediaItemId(job.id))
            }
        }
    }

    private fun endOf(now: HomeJob?): OperationEnd? = when (now?.state) {
        null -> OperationEnd.Nothing
        HomeJobState.DONE -> OperationEnd.Done(1)
        HomeJobState.FAILED -> if (now.retry != null) null else OperationEnd.Failed(now.error ?: "failed on the Pi")
        else -> null
    }

    private suspend fun copyWhatIsReady() {
        val ready = known.value.values.filter { it.state == HomeJobState.DONE }
        if (ready.isEmpty()) return
        if (!phone.copyOnWifi()) {
            Diag.log(TAG, "${ready.size} ready at home; copying to the phone by itself is off")
            return
        }
        if (!phone.unmetered()) {
            Diag.log(TAG, "${ready.size} ready at home; not copying on a metered network")
            return
        }
        val local = downloads.observeDownloads().first()
        val kept = phone.kept.ids.value
        ready.filter { job -> job.id !in kept && wanted(job, local[MediaItemId(job.id)]) }
            .forEach { job ->
                val item = job.item.toPlayable() ?: return@forEach
                Diag.log(TAG, "\"${job.item.title}\": copying from home to the phone (unmetered)")
                downloads.download(item, audioOnly = job.audioOnly)
            }
    }

    private fun wanted(job: HomeJob, onPhone: DownloadState?): Boolean = when (onPhone) {
        null, DownloadState.NotDownloaded, is DownloadState.Failed -> true
        is DownloadState.Downloaded -> onPhone.audioOnly && !job.audioOnly
        is DownloadState.Downloading -> false
    }
}
