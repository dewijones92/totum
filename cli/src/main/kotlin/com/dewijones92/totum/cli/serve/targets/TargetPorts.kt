package com.dewijones92.totum.cli.serve.targets

import com.dewijones92.totum.data.targets.SoundSettings
import com.dewijones92.totum.data.targets.Target
import com.dewijones92.totum.data.targets.TargetEntry
import com.dewijones92.totum.domain.PlayableItem

internal data class PlayRequest(
    val url: String,
    val destination: String,
    val sound: SoundSettings,
    val startAtMs: Long,
    val picture: Boolean,
    val title: String,
)

internal interface TargetPlayer {
    fun play(request: PlayRequest)
    fun pause(paused: Boolean)
    fun seek(positionMs: Long)
    fun volume(percent: Int)
    val volumePercent: Int?
    fun stop()
    val positionMs: Long?
    val durationMs: Long?
    var onEnded: (failed: Boolean) -> Unit
}

internal interface TargetLink {
    suspend fun connect(): String?
    suspend fun release()
}

internal fun interface TargetSounds {
    suspend fun urlFor(item: PlayableItem, picture: Boolean): String?
}

internal fun interface ProgressReporter {
    suspend fun report(entry: TargetEntry, positionMs: Long, durationMs: Long?, finished: Boolean)

    companion object {
        val NONE = ProgressReporter { _, _, _, _ -> }
    }
}

internal class TargetKit(val player: TargetPlayer, val link: TargetLink)

internal class FoundTarget(val target: Target, val kit: () -> TargetKit)

internal fun interface TargetSource {
    suspend fun find(fresh: Boolean): List<FoundTarget>
}
