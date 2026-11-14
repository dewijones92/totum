package com.dewijones92.totum.cli.serve.targets

import com.dewijones92.totum.data.targets.KeepAwake
import com.dewijones92.totum.data.targets.SoundSettings
import com.dewijones92.totum.data.targets.Target
import com.dewijones92.totum.data.targets.TargetEntry
import com.dewijones92.totum.data.targets.TargetKind
import com.dewijones92.totum.data.targets.TargetPower
import com.dewijones92.totum.data.targets.TargetTrouble
import com.dewijones92.totum.data.targets.TroubleKind
import com.dewijones92.totum.data.targets.VolumeMode
import com.dewijones92.totum.domain.PlayableItem
import com.dewijones92.totum.playback.cast.CastItem

internal data class PlayRequest(
    val url: String,
    val destination: String,
    val sound: SoundSettings,
    val startAtMs: Long,
    val picture: Boolean,
    val title: String,
    val cast: CastItem? = null,
)

internal class TargetStream(val url: String, val cast: CastItem? = null)

internal interface TargetPlayer {
    fun play(request: PlayRequest)
    fun pause(paused: Boolean)
    fun seek(positionMs: Long)
    fun volume(percent: Int)
    val volumePercent: Int?
    val fadeBasePercent: Int? get() = volumePercent
    fun stop()
    val positionMs: Long?
    val durationMs: Long?
    var onEnded: (failed: Boolean) -> Unit
    var onLost: (reason: String) -> Unit
        get() = {}
        set(value) {}
    val failure: String? get() = null
    val notice: String? get() = null
    val live: Boolean get() = false
    val power: TargetPower get() = TargetPower.ON
}

internal interface TargetLink {
    suspend fun connect(): String?
    suspend fun release()
    val unreachable: TargetTrouble? get() = null
    val sinkWaitMs: Long get() = 0
    val switchedOnByHand: Boolean get() = false
    val power: TargetPower? get() = null

    suspend fun look() = Unit

    suspend fun present(): Boolean = true
}

internal fun interface TargetSounds {
    suspend fun urlFor(item: PlayableItem, picture: Boolean): String?

    suspend fun streamFor(item: PlayableItem, picture: Boolean): TargetStream? =
        urlFor(item, picture)?.let { TargetStream(it) }

    fun whyNone(item: PlayableItem): String = "no stream was found for it"

    fun refusedUntil(item: PlayableItem): Long? = null
}

internal fun interface ProgressReporter {
    suspend fun report(entry: TargetEntry, positionMs: Long, durationMs: Long?, finished: Boolean)

    companion object {
        val NONE = ProgressReporter { _, _, _, _ -> }
    }
}

internal interface VolumeFade {
    val basePercent: Int

    suspend fun set(percent: Int): Boolean
}

internal interface TargetVolume {
    val percent: Int?
    val mode: VolumeMode?

    suspend fun set(percent: Int)

    suspend fun refresh()

    suspend fun beginFade(): VolumeFade? {
        refresh()
        val base = percent ?: return null
        return SetFade(this, base)
    }
}

private class SetFade(private val volume: TargetVolume, override val basePercent: Int) : VolumeFade {
    override suspend fun set(percent: Int): Boolean {
        volume.set(percent)
        return true
    }
}

internal interface TargetKeeper {
    val state: KeepAwake?

    suspend fun beat(busy: Boolean): Boolean
}

internal class TargetKit(
    val player: TargetPlayer,
    val link: TargetLink,
    val volume: TargetVolume? = null,
    val keeper: TargetKeeper? = null,
)

internal class FoundTarget(val target: Target, val kit: () -> TargetKit)

internal fun interface TargetSource {
    suspend fun find(fresh: Boolean): List<FoundTarget>
}

internal class Problem(val trouble: TargetTrouble, val text: String)

internal object TroubleWords {
    fun problem(label: String, trouble: TargetTrouble, title: String? = null, kind: TargetKind? = null): Problem =
        Problem(trouble, text(label, trouble, title, kind))

    fun text(
        label: String,
        trouble: TargetTrouble,
        title: String?,
        kind: TargetKind? = null,
    ): String = when (trouble.kind) {
        TroubleKind.NOT_CONNECTED -> "📶 Couldn't reach $label: it isn't connected (off or out of range)"
        TroubleKind.SWITCH_ON -> "🔌 Switch $label on, then try again"
        TroubleKind.SWITCHED_OFF -> when (kind) {
            TargetKind.BROWSER -> "🖥️ $label was closed"
            TargetKind.SPEAKER -> "🔌 $label was switched off or went out of range"
            else -> "📺 $label was switched off"
        }
        TroubleKind.WONT_WAKE -> "❌ $label didn't wake: check 'Turn on via Wi-Fi' in its settings"
        TroubleKind.COULD_NOT_PLAY -> "❌ Couldn't play ${title ?: "that"}: ${trouble.reason ?: "no reason given"}"
        TroubleKind.YOUTUBE_BACKOFF ->
            "⏳ YouTube is refusing the Pi for now; try again around ${clockOf(trouble.untilMs)}"
        TroubleKind.STALLED -> "⚠️ ${title ?: "It"} has stopped moving on $label"
    }

    private fun clockOf(ms: Long?): String =
        ms?.let { java.text.SimpleDateFormat("HH:mm", java.util.Locale.UK).format(java.util.Date(it)) } ?: "later"
}
