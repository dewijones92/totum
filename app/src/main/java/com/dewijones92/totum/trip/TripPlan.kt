package com.dewijones92.totum.trip

import com.dewijones92.totum.data.queue.QueueEntry
import com.dewijones92.totum.data.queue.QueueSnapshot
import com.dewijones92.totum.domain.DownloadState
import com.dewijones92.totum.domain.MediaItemId
import com.dewijones92.totum.domain.PlayHandle
import com.dewijones92.totum.domain.PlayableItem
import com.dewijones92.totum.domain.isPermanent
import com.dewijones92.totum.downloads.home.aheadFor

const val UNGROUPED = "trip:ungrouped"

data class PiFile(val id: MediaItemId, val title: String, val bytes: Long)

data class TripInputs(
    val queue: QueueSnapshot,
    val perGroup: Int,
    val states: Map<MediaItemId, DownloadState>,
    val attempts: Map<MediaItemId, Int>,
    val piFiles: List<PiFile>,
    val freeBytes: Long?,
    val finished: (PlayableItem) -> Boolean,
    val fetchesAudioOnly: (PlayableItem) -> Boolean,
    val atHome: (PlayableItem) -> Boolean,
    val maxAttempts: Int = MAX_ATTEMPTS,
    val reserveBytes: Long = RESERVE_BYTES,
)

data class Skipped(val item: PlayableItem, val why: String)

data class TripPlan(
    val wanted: List<PlayableItem>,
    val onPhone: Int,
    val fetching: Int,
    val fetch: List<PlayableItem>,
    val copyFromPi: List<MediaItemId>,
    val piExtras: List<PiFile>,
    val skipped: List<Skipped>,
    val failed: Int,
    val lowSpace: Boolean,
)

fun planTrip(inputs: TripInputs): TripPlan {
    val groups = inputs.queue.entries.map(::groupKey).distinct()
    val keep = groups.associateWith { inputs.perGroup }
    val wanted = aheadFor(inputs.queue, keep, { false }, inputs.finished, ::groupKey)
    val missing = wanted.filter { inputs.states[it.item.id].let { s -> s !is DownloadState.Downloaded } }
    val onPhone = wanted.size - missing.size
    val fetching = missing.count { inputs.states[it.item.id] is DownloadState.Downloading }
    val idle = missing.filter { inputs.states[it.item.id] !is DownloadState.Downloading }

    val skipped = mutableListOf<Skipped>()
    var failed = 0
    val fetch = mutableListOf<PlayableItem>()
    val copy = mutableListOf<MediaItemId>()
    idle.forEach { item ->
        val state = inputs.states[item.item.id]
        val why = skipReason(item, state, inputs)
        when {
            why != null -> {
                skipped += Skipped(item, why)
                if (state is DownloadState.Failed) failed++
            }
            inputs.atHome(item) -> copy += item.item.id
            else -> fetch += item
        }
    }

    val lowSpace = inputs.freeBytes?.let { it < inputs.reserveBytes } == true
    val queuedIds = wanted.map { it.item.id }.toSet()
    val extras = if (lowSpace) emptyList() else piExtras(inputs, queuedIds, copy)
    return TripPlan(
        wanted = wanted,
        onPhone = onPhone,
        fetching = fetching,
        fetch = if (lowSpace) emptyList() else fetch.toList(),
        copyFromPi = if (lowSpace) emptyList() else copy.toList(),
        piExtras = extras,
        skipped = skipped,
        failed = failed,
        lowSpace = lowSpace,
    )
}

private fun skipReason(item: PlayableItem, state: DownloadState?, inputs: TripInputs): String? = when {
    item.handle is PlayHandle.LocalVideo -> "it is already a file on the phone"
    inputs.atHome(item) -> givenUpReason(item.item.id, state, inputs)
    !inputs.fetchesAudioOnly(item) -> "a film or a torrent is a download you ask for, never one the trip starts"
    item.fetchUrl == null -> "it has nothing to fetch yet"
    else -> givenUpReason(item.item.id, state, inputs)
}

private fun givenUpReason(id: MediaItemId, state: DownloadState?, inputs: TripInputs): String? = when {
    state is DownloadState.Failed && state.isPermanent -> "asking again cannot help: ${state.reason.take(REASON_CHARS)}"
    state is DownloadState.Failed && (inputs.attempts[id] ?: 0) >= inputs.maxAttempts ->
        "gave up after ${inputs.attempts[id]} attempts"
    else -> null
}

private fun piExtras(inputs: TripInputs, queued: Set<MediaItemId>, alreadyCopying: List<MediaItemId>): List<PiFile> {
    val taken = queued + alreadyCopying
    val copying = inputs.piFiles.filter { it.id in alreadyCopying }.sumOf { it.bytes }
    var room = inputs.freeBytes?.let { it - inputs.reserveBytes - copying }
    val chosen = mutableListOf<PiFile>()
    inputs.piFiles.filter {
        it.id !in taken && !onPhoneOrComing(inputs.states[it.id]) && givenUpReason(it.id, inputs.states[it.id], inputs) == null
    }.forEach { file ->
        val left = room
        if (left == null) {
            chosen += file
        } else if (file.bytes <= left) {
            chosen += file
            room = left - file.bytes
        }
    }
    return chosen
}

private fun onPhoneOrComing(state: DownloadState?): Boolean =
    state is DownloadState.Downloaded || state is DownloadState.Downloading

private fun groupKey(entry: QueueEntry): String = entry.group?.id ?: UNGROUPED

const val MAX_ATTEMPTS = 2
const val RESERVE_BYTES = 2L * 1024 * 1024 * 1024
private const val REASON_CHARS = 100
