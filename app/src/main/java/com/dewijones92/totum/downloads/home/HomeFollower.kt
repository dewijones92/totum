package com.dewijones92.totum.downloads.home

import com.dewijones92.totum.common.Diag
import com.dewijones92.totum.data.backup.toPlayable
import com.dewijones92.totum.data.download.DownloadManager
import com.dewijones92.totum.data.home.HomeJob
import com.dewijones92.totum.data.home.HomeJobState
import com.dewijones92.totum.domain.DownloadState
import com.dewijones92.totum.domain.MediaItemId
import com.dewijones92.totum.operations.OperationEnd
import com.dewijones92.totum.operations.OperationKind
import com.dewijones92.totum.operations.Operations
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap

internal class HomeReporting(val operations: Operations, val notices: HomeNotices)

internal class HomeFollower(
    private val known: StateFlow<Map<MediaItemId, HomeJob>>,
    private val downloads: DownloadManager,
    private val reporting: HomeReporting,
    private val unmetered: () -> Boolean,
    private val scope: CoroutineScope,
    private val pollMs: Long,
) {
    private val tracked = ConcurrentHashMap.newKeySet<String>()
    private val seenBusy = ConcurrentHashMap.newKeySet<String>()
    private val announced = ConcurrentHashMap.newKeySet<String>()

    suspend fun followAll(jobs: Collection<HomeJob>) {
        jobs.forEach { job ->
            if (job.state.busy()) {
                seenBusy.add(job.id)
                if (tracked.add(job.id)) strip(job)
            }
            if (job.state == HomeJobState.DONE && job.id in seenBusy && announced.add(job.id)) {
                reporting.notices.ready(job.item.title)
            }
        }
        copyWhatIsReady()
    }

    private fun strip(job: HomeJob) = scope.launch {
        reporting.operations.track("home:${job.id}", OperationKind.HOME_DOWNLOAD, job.item.title) { progress ->
            var end: OperationEnd? = null
            while (end == null) {
                val now = known.value[MediaItemId(job.id)]
                progress.report(step = now?.percent() ?: 0, steps = PERCENT)
                end = when (now?.state) {
                    null -> OperationEnd.Nothing
                    HomeJobState.DONE -> OperationEnd.Done(1)
                    HomeJobState.FAILED -> OperationEnd.Failed(now.error ?: "failed at home")
                    else -> null.also { delay(pollMs) }
                }
            }
            end
        }
        tracked.remove(job.id)
    }

    private suspend fun copyWhatIsReady() {
        val ready = known.value.values.filter { it.state == HomeJobState.DONE }
        if (ready.isEmpty()) return
        if (!unmetered()) {
            Diag.log(TAG, "${ready.size} ready at home; not copying on a metered network")
            return
        }
        val local = downloads.observeDownloads().first()
        ready.filter { local[MediaItemId(it.id)].let { s -> s == null || s == DownloadState.NotDownloaded } }
            .forEach { job ->
                val item = job.item.toPlayable() ?: return@forEach
                Diag.log(TAG, "\"${job.item.title}\": copying from home to the phone (unmetered)")
                downloads.download(item, audioOnly = job.audioOnly)
            }
    }
}
