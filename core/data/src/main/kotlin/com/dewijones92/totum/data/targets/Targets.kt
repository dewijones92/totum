package com.dewijones92.totum.data.speakers

import com.dewijones92.totum.data.backup.BackupItem
import com.dewijones92.totum.data.backup.toBackup
import com.dewijones92.totum.data.backup.toPlayable
import com.dewijones92.totum.data.queue.QueueEntry
import com.dewijones92.totum.data.queue.QueueGroup
import com.dewijones92.totum.data.queue.QueueSnapshot
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
public data class Speaker(val id: String, val label: String)

@Serializable
public data class SpeakerEntry(val item: BackupItem, val groupId: String? = null, val groupTitle: String? = null)

@Serializable
public data class SpeakerState(
    val speaker: Speaker,
    val entries: List<SpeakerEntry> = emptyList(),
    val at: Int = -1,
    val playing: Boolean = false,
    val positionMs: Long = 0,
    val durationMs: Long? = null,
    val volume: Int = DEFAULT_VOLUME,
    val linked: Boolean = false,
    val problem: String? = null,
) {
    val current: SpeakerEntry? get() = entries.getOrNull(at)
}

@Serializable
public data class SpeakersAnswer(val speakers: List<SpeakerState>)

@Serializable
public enum class QueueMode { REPLACE, NEXT, END }

@Serializable
public data class SpeakerQueueRequest(
    val items: List<BackupItem>,
    val mode: QueueMode,
    val shuffle: Boolean = false,
    val groupId: String? = null,
    val groupTitle: String? = null,
) {
    val group: QueueGroup? get() = groupOf(groupId, groupTitle)
}

@Serializable
public enum class SpeakerAction { PLAY, PAUSE, NEXT, PREVIOUS, JUMP, SEEK, VOLUME, STOP, REMOVE }

@Serializable
public data class SpeakerControl(
    val action: SpeakerAction,
    val index: Int? = null,
    val positionMs: Long? = null,
    val volume: Int? = null,
)

public fun QueueEntry.toSpeakerEntry(): SpeakerEntry = SpeakerEntry(item.toBackup(), group?.id, group?.title)

public fun SpeakerEntry.toQueueEntry(): QueueEntry? =
    item.toPlayable()?.let { QueueEntry(it, groupOf(groupId, groupTitle)) }

public fun QueueSnapshot.toSpeakerEntries(): List<SpeakerEntry> = entries.map { it.toSpeakerEntry() }

public fun List<SpeakerEntry>.toSnapshot(at: Int): QueueSnapshot {
    val kept = mapNotNull { it.toQueueEntry() }
    return QueueSnapshot(kept, if (at in kept.indices) at else QueueSnapshot.NOTHING_PLAYING)
}

public const val DEFAULT_VOLUME: Int = 70

public val SpeakersJson: Json = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
}

private fun groupOf(id: String?, title: String?): QueueGroup? = id?.let { QueueGroup(it, title ?: it) }
