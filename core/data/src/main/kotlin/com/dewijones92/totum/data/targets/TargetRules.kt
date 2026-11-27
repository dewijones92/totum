package com.dewijones92.totum.data.targets

import com.dewijones92.totum.common.HttpUrl
import com.dewijones92.totum.common.youTubeVideoId
import com.dewijones92.totum.data.backup.BackupItem
import com.dewijones92.totum.data.backup.toBackup
import com.dewijones92.totum.data.backup.toPlayable
import com.dewijones92.totum.data.queue.QueueSnapshot
import com.dewijones92.totum.domain.MediaContentKind
import com.dewijones92.totum.domain.Picture
import com.dewijones92.totum.domain.PlayHandle
import com.dewijones92.totum.domain.picture

public val TargetState.showsPicture: Boolean
    get() = target.kind.hasPicture && current?.toQueueEntry()?.item?.picture.let { it != null && it != Picture.NO }

public val TargetState.offersSkipSilence: Boolean
    get() = !showsPicture

public val TargetState.offersBoost: Boolean
    get() = true

public val TargetState.offersCaptions: Boolean
    get() = captions.offered.isNotEmpty()

public val TargetState.isLive: Boolean
    get() = live || current?.toQueueEntry()?.item?.item?.contentKind == MediaContentKind.LIVE

public val TargetState.shownLengthMs: Long?
    get() = durationMs?.takeIf { !isLive && it > 0 }

public object TargetReporting {

    public fun reports(requester: Requester?, ownerId: String?): Boolean =
        requester != null && ownerId != null && requester.id == ownerId

    public fun videoIdToReport(entry: TargetEntry, signedIn: Boolean): String? {
        if (!entry.reportsToYouTube || !signedIn) return null
        val playable = entry.item.toPlayable() ?: return null
        val handle = playable.handle as? PlayHandle.Video ?: return null
        return HttpUrl.parse(handle.watchUrl.value)?.youTubeVideoId()
    }

    public fun why(entry: TargetEntry, signedIn: Boolean): String = when {
        !entry.reportsToYouTube ->
            "not queued from the phone that signed the Pi in (added by ${entry.addedBy ?: "unknown"})"
        !signedIn -> "the Pi is not signed in to YouTube"
        videoIdToReport(entry, signedIn = true) == null -> "not a YouTube video"
        else -> "reported"
    }
}

public object TargetColours {

    public const val PHONE_ID: String = "phone"
    public const val PHONE: Long = 0xFFFF8A3DL

    public val palette: List<Long> = listOf(
        0xFF0089B0L,
        0xFF7B3FE4L,
        0xFF1E9E5AL,
        0xFFD6336CL,
        0xFF2F6FEDL,
        0xFF8C6A00L,
        0xFF00A39BL,
        0xFF6D4C41L,
    )

    public fun of(colours: Map<String, Long>, id: String): Long = colours[id] ?: PHONE

    public fun assign(targets: List<Target>, remembered: Map<String, Long>): Map<String, Long> {
        val result = LinkedHashMap<String, Long>()
        result[PHONE_ID] = remembered[PHONE_ID] ?: PHONE
        targets.filter { it.id in remembered }.forEach { result[it.id] = remembered.getValue(it.id) }
        val used = result.values.toMutableSet()
        targets.filter { it.id !in result }.sortedBy { it.id }.forEach { target ->
            val start = Math.floorMod(target.id.hashCode(), palette.size)
            val free = (0 until palette.size).map { palette[(start + it) % palette.size] }.firstOrNull { it !in used }
                ?: palette[start]
            result[target.id] = free
            used += free
        }
        return result
    }
}

public data class SendPlan(
    val items: List<BackupItem>,
    val groupId: String?,
    val groupTitle: String?,
    val startItemId: String,
    val startAtMs: Long,
    val toRemove: List<Int>,
)

public fun SendPlan.request(from: Requester?): TargetQueueRequest = TargetQueueRequest(
    items = items,
    mode = QueueMode.PLAY,
    groupId = groupId,
    groupTitle = groupTitle,
    from = from,
    startAtMs = startAtMs,
    startItemId = startItemId,
)

public object SendTo {

    public fun plan(snapshot: QueueSnapshot, playingPositionMs: Long): SendPlan? {
        val at = snapshot.currentIndex
        val current = snapshot.entries.getOrNull(at) ?: return null
        val group = current.group
        val lastOfGroup = if (group == null) {
            at
        } else {
            var end = at
            while (snapshot.entries.getOrNull(end + 1)?.group?.id == group.id) end++
            end
        }
        val sent = (at..lastOfGroup).map { snapshot.entries[it] }
        return SendPlan(
            items = sent.map { it.item.toBackup() },
            groupId = group?.id,
            groupTitle = group?.title,
            startItemId = current.item.item.id.value,
            startAtMs = playingPositionMs.coerceAtLeast(0),
            toRemove = (at..lastOfGroup).toList(),
        )
    }
}

public enum class TargetConnection { LINKED, READY, WAKING, ASLEEP, RECONNECTING, PROBLEM }

public val TargetState.connection: TargetConnection
    get() = when {
        power == TargetPower.ASLEEP -> TargetConnection.ASLEEP
        power == TargetPower.WAKING -> TargetConnection.WAKING
        keepAwake == KeepAwake.RECONNECTING -> TargetConnection.RECONNECTING
        trouble != null || problem != null -> TargetConnection.PROBLEM
        linked || playing || keepAwake == KeepAwake.KEPT -> TargetConnection.LINKED
        else -> TargetConnection.READY
    }

public fun List<TargetState>.barFor(selected: String?): TargetState? =
    firstOrNull { it.target.id == selected }?.takeIf { it.current != null }

public sealed interface DockBar {
    public data class Remote(val state: TargetState) : DockBar

    public data object Phone : DockBar

    public data object Hidden : DockBar
}

public fun List<TargetState>.dockBarFor(selected: String?, onQueueTab: Boolean): DockBar {
    val remote = barFor(selected) ?: return DockBar.Phone
    return if (onQueueTab) DockBar.Hidden else DockBar.Remote(remote)
}

public enum class Doing { STARTING, PLAYING, PAUSED, IDLE }

public val TargetState.doing: Doing
    get() = when {
        starting -> Doing.STARTING
        current == null -> Doing.IDLE
        playing -> Doing.PLAYING
        power != TargetPower.ON -> Doing.IDLE
        else -> Doing.PAUSED
    }

public fun TargetState.doingLine(title: String?): String {
    val named = title?.takeIf { it.isNotBlank() }
    return when (doing) {
        Doing.STARTING -> "⏳ ${named ?: "Starting"}"
        Doing.PLAYING -> "▶️ ${named ?: "Playing"}"
        Doing.PAUSED -> "⏸️ ${named ?: "Paused"}"
        Doing.IDLE -> "⚪ Idle"
    }
}

public data class BarDecision(val state: TargetState?, val why: String)

public fun List<TargetState>.barDecision(selected: String?): BarDecision {
    val state = barFor(selected)
    val why = when {
        selected == null -> "this phone is the chosen device"
        state != null -> "${state.target.label} is chosen and has ${state.entries.size} queued, at=${state.at}"
        none { it.target.id == selected } -> "$selected is chosen but the Pi does not list it"
        else -> "$selected is chosen but has nothing playing or queued to show"
    }
    return BarDecision(state, why)
}
