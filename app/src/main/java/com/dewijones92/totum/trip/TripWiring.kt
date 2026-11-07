package com.dewijones92.totum.trip

import android.content.Context
import com.dewijones92.totum.R
import com.dewijones92.totum.data.home.HomeJobState
import com.dewijones92.totum.data.torrent.hasAudioOnlyFetch
import com.dewijones92.totum.di.AppContainer
import com.dewijones92.totum.domain.MediaItemId
import com.dewijones92.totum.downloads.EpisodeDownloads
import com.dewijones92.totum.operations.Ended
import com.dewijones92.totum.operations.OperationEnd
import com.dewijones92.totum.operations.OperationKind
import com.dewijones92.totum.settings.DownloadChoice
import com.dewijones92.totum.settings.NetworkStatus
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge

internal fun tripModeFor(c: AppContainer, context: Context): TripMode {
    val title = context.getString(R.string.trip_batch_title)
    val network = NetworkStatus(context)
    val ports = TripPorts(
        queue = c.playbackQueue.state,
        changes = merge(c.playbackQueue.state.map { }, c.homeDownloads.jobs.map { }),
        states = { c.downloadManager.observeDownloads().first() },
        unmetered = { !network.isMetered() },
        finished = { c.playbackQueue.passOver(it) != null },
        fetchesAudioOnly = { it.hasAudioOnlyFetch },
        atHome = { c.homeDownloads.atHome(it) != null },
        choice = { c.appPreferences.settings.value.downloadChoice ?: DownloadChoice.Sound },
        piFiles = { piFilesReady(c) },
        copyFromPi = c.homeDownloads::copyNow,
        freeBytes = c::freeDownloadSpaceBytes,
        fetch = { batchTitle, items, choice -> c.episodeDownloads.fetchBatch(batchTitle, items, choice) },
        batchRunning = { c.operations.isRunning(EpisodeDownloads.batchKey(title)) },
        batchCancelled = c.operations.ended.filter { it.isCancelledBatch(title) }.map { },
        batchTitle = title,
    )
    return TripMode(SharedPrefsTripStore(context), ports, c.applicationScope)
}

private fun piFilesReady(c: AppContainer): List<PiFile> {
    val kept = c.homeDownloads.kept.value
    return c.homeDownloads.jobs.value.values
        .filter { it.state == HomeJobState.DONE && it.id !in kept }
        .map { PiFile(MediaItemId(it.id), it.item.title, it.totalBytes ?: it.bytes) }
}

private fun Ended.isCancelledBatch(title: String): Boolean =
    kind == OperationKind.DOWNLOAD_BATCH && subject == title && end is OperationEnd.Cancelled
