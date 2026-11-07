package com.dewijones92.totum.data.targets

import com.dewijones92.totum.common.HttpUrl
import com.dewijones92.totum.common.youTubeVideoId
import com.dewijones92.totum.data.backup.BackupItem
import com.dewijones92.totum.data.backup.toBackup
import com.dewijones92.totum.data.backup.toPlayable
import com.dewijones92.totum.data.queue.QueueSnapshot
import com.dewijones92.totum.domain.PlayHandle

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
        !entry.reportsToYouTube -> "not queued from the phone that signed the Pi in (added by ${entry.addedBy ?: "unknown"})"
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
