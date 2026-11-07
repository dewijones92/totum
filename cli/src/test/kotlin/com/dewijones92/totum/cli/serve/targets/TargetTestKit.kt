package com.dewijones92.totum.cli.serve.targets

import com.dewijones92.totum.common.HttpUrl
import com.dewijones92.totum.data.backup.BackupItem
import com.dewijones92.totum.data.backup.toBackup
import com.dewijones92.totum.data.targets.Target
import com.dewijones92.totum.data.targets.TargetKind
import com.dewijones92.totum.domain.MediaContentKind
import com.dewijones92.totum.domain.MediaItem
import com.dewijones92.totum.domain.MediaItemId
import com.dewijones92.totum.domain.PlayHandle
import com.dewijones92.totum.domain.PlayableItem
import com.dewijones92.totum.domain.SourceId

internal class RecordingPlayer : TargetPlayer {
    val requests = mutableListOf<PlayRequest>()
    var paused = false
    var stopped = false
    var volume: Int? = null
    val volumeCommands = mutableListOf<Int>()
    override fun play(request: PlayRequest) {
        requests += request
        paused = false
        stopped = false
    }
    override fun pause(paused: Boolean) {
        this.paused = paused
    }
    override fun seek(positionMs: Long) = Unit
    override fun volume(percent: Int) {
        volumeCommands += percent
        volume = percent
    }
    override val volumePercent: Int? get() = volume
    override fun stop() {
        stopped = true
    }
    override val positionMs: Long? = 1_000
    override val durationMs: Long? = 60_000
    override var onEnded: (failed: Boolean) -> Unit = {}
}

internal class ConnectingLink(var sink: String? = "bar-sink") : TargetLink {
    var connects = 0
    override suspend fun connect(): String? = sink.also { connects++ }
    override suspend fun release() = Unit
}

internal fun podcastItem(id: String): BackupItem = PlayableItem(
    MediaItem(
        MediaItemId(id),
        SourceId("feed"),
        "Bluey $id",
        publishedAt = null,
        duration = null,
        mediaUrl = HttpUrl.of("https://example.com/$id.mp3"),
        contentKind = MediaContentKind.STANDARD,
    ),
    PlayHandle.Podcast(),
).toBackup()

internal fun speaker(id: String = "bar") = Target(id, "LG soundbar", TargetKind.SPEAKER)
