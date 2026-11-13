package com.dewijones92.totum.data.targets

import com.dewijones92.totum.data.backup.BackupItem
import com.dewijones92.totum.data.backup.toBackup
import com.dewijones92.totum.data.backup.toPlayable
import com.dewijones92.totum.data.queue.QueueEntry
import com.dewijones92.totum.data.queue.QueueGroup
import com.dewijones92.totum.data.queue.QueueSnapshot
import com.dewijones92.totum.domain.RepeatMode
import com.dewijones92.totum.playback.SleepFade
import com.dewijones92.totum.playback.VolumeBoost
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
public enum class TargetKind(
    public val hasPicture: Boolean,
    public val label: String,
    public val reachableAway: Boolean = false,
) {
    PHONE(true, "📱 Phone", reachableAway = true),
    SPEAKER(false, "🔈 Speaker"),
    TV(true, "📺 TV"),
    CHROMECAST(true, "📺 Chromecast"),
    BROWSER(true, "🖥️ Browser", reachableAway = true),
}

@Serializable
public data class Target(val id: String, val label: String, val kind: TargetKind = TargetKind.SPEAKER)

@Serializable
public data class Requester(val id: String, val label: String)

@Serializable
public data class SoundSettings(val skipSilence: Boolean = false, val boost: VolumeBoost = VolumeBoost.OFF) {
    val changesSound: Boolean get() = skipSilence || boost != VolumeBoost.OFF
}

@Serializable
public data class TargetEntry(
    val item: BackupItem,
    val groupId: String? = null,
    val groupTitle: String? = null,
    val addedBy: String? = null,
    val reportsToYouTube: Boolean = false,
    val loop: Boolean = false,
)

@Serializable
public enum class SleepKind { OFF, AFTER_TIME, AFTER_ITEMS }

@Serializable
public data class TargetSleepAsk(
    val kind: SleepKind,
    val seconds: Int = 0,
    val fadeSeconds: Int = SleepFade.DEFAULT_LENGTH.inWholeSeconds.toInt(),
    val itemIds: List<String> = emptyList(),
    val title: String? = null,
)

@Serializable
public data class TargetSleep(
    val kind: SleepKind,
    val remainingMs: Long? = null,
    val itemsLeft: Int = 0,
    val title: String? = null,
    val fadeSeconds: Int = 0,
    val fading: Boolean = false,
)

@Serializable
public enum class TargetPower { ON, ASLEEP, WAKING }

@Serializable
public enum class VolumeMode { HARDWARE, SOFTWARE }

@Serializable
public enum class KeepAwake { KEPT, RECONNECTING, OUTSIDE_WINDOW }

@Serializable
public enum class TroubleKind {
    NOT_CONNECTED,
    SWITCH_ON,
    SWITCHED_OFF,
    WONT_WAKE,
    COULD_NOT_PLAY,
    YOUTUBE_BACKOFF,
    STALLED,
}

@Serializable
public data class TargetTrouble(
    val kind: TroubleKind,
    val itemId: String? = null,
    val reason: String? = null,
    val untilMs: Long? = null,
)

@Serializable
public data class TargetState(
    val target: Target,
    val entries: List<TargetEntry> = emptyList(),
    val at: Int = -1,
    val playing: Boolean = false,
    val positionMs: Long = 0,
    val durationMs: Long? = null,
    val volume: Int? = null,
    val volumeMode: VolumeMode? = null,
    val keepAwake: KeepAwake? = null,
    val linked: Boolean = false,
    val problem: String? = null,
    val sound: SoundSettings = SoundSettings(),
    val repeat: RepeatMode = RepeatMode.OFF,
    val live: Boolean = false,
    val power: TargetPower = TargetPower.ON,
    val starting: Boolean = false,
    val trouble: TargetTrouble? = null,
    val sleep: TargetSleep? = null,
) {
    val current: TargetEntry? get() = entries.getOrNull(at)
}

@Serializable
public data class TargetsAnswer(val targets: List<TargetState>)

@Serializable
public enum class QueueMode { REPLACE, NEXT, END, PLAY }

@Serializable
public data class TargetQueueRequest(
    val items: List<BackupItem>,
    val mode: QueueMode,
    val shuffle: Boolean = false,
    val groupId: String? = null,
    val groupTitle: String? = null,
    val from: Requester? = null,
    val startAtMs: Long = 0,
    val startItemId: String? = null,
    val repeat: RepeatMode? = null,
    val loop: Boolean = false,
) {
    val group: QueueGroup? get() = groupOf(groupId, groupTitle)
}

@Serializable
public enum class TargetAction {
    PLAY,
    PAUSE,
    NEXT,
    PREVIOUS,
    JUMP,
    SEEK,
    VOLUME,
    STOP,
    REMOVE,
    SOUND,
    REPEAT,
    WAKE,
    SLEEP,
}

@Serializable
public data class TargetControl(
    val action: TargetAction,
    val index: Int? = null,
    val positionMs: Long? = null,
    val volume: Int? = null,
    val sound: SoundSettings? = null,
    val repeat: RepeatMode? = null,
    val itemId: String? = null,
    val itemIds: List<String>? = null,
    val sleep: TargetSleepAsk? = null,
)

public fun QueueEntry.toTargetEntry(from: Requester? = null, ownerId: String? = null): TargetEntry = TargetEntry(
    item.toBackup(),
    group?.id,
    group?.title,
    from?.label,
    TargetReporting.reports(from, ownerId),
)

public fun TargetEntry.toQueueEntry(): QueueEntry? =
    item.toPlayable()?.let { QueueEntry(it, groupOf(groupId, groupTitle)) }

public fun QueueSnapshot.toTargetEntries(): List<TargetEntry> = entries.map { it.toTargetEntry() }

public fun List<TargetEntry>.toSnapshot(at: Int): QueueSnapshot {
    val kept = mapNotNull { it.toQueueEntry() }
    return QueueSnapshot(kept, if (at in kept.indices) at else QueueSnapshot.NOTHING_PLAYING)
}

public val TargetsJson: Json = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
}

private fun groupOf(id: String?, title: String?): QueueGroup? = id?.let { QueueGroup(it, title ?: it) }
