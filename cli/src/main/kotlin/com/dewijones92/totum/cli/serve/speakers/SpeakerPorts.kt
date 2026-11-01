package com.dewijones92.totum.cli.serve.speakers

import com.dewijones92.totum.domain.PlayableItem

internal interface SpeakerPlayer {
    fun play(url: String, sink: String, volume: Int)
    fun pause(paused: Boolean)
    fun seek(positionMs: Long)
    fun volume(percent: Int)
    fun stop()
    val positionMs: Long?
    val durationMs: Long?
    var onEnded: (failed: Boolean) -> Unit
}

internal interface SpeakerLink {
    suspend fun connect(): String?
    suspend fun release()
}

internal fun interface SpeakerSounds {
    suspend fun urlFor(item: PlayableItem): String?
}
