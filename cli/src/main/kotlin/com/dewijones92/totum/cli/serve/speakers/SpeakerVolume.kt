package com.dewijones92.totum.cli.serve.speakers

import com.dewijones92.totum.cli.serve.targets.TargetPlayer
import com.dewijones92.totum.cli.serve.targets.TargetVolume
import com.dewijones92.totum.cli.serve.targets.VolumeFade
import com.dewijones92.totum.common.Diag
import com.dewijones92.totum.data.targets.VolumeMode
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.math.roundToInt

internal class SpeakerVolume(
    private val id: String,
    private val transport: SpeakerTransport,
    private val software: TargetPlayer,
) : TargetVolume {
    private val gate = Mutex()

    @Volatile
    private var hardwarePercent: Int? = null

    @Volatile
    private var hardwareRaw: Int? = null

    @Volatile
    override var mode: VolumeMode? = null
        private set

    @Volatile
    private var lastLogged: String? = null

    override val percent: Int? get() = if (mode == VolumeMode.HARDWARE) hardwarePercent else software.volumePercent

    override suspend fun set(percent: Int) = gate.withLock {
        val raw = HardwareVolume.rawOf(percent)
        when (val answer = transport.setVolume(raw)) {
            is TransportVolume.Level -> followed(percent, raw, answer)
            is TransportVolume.Unavailable -> {
                hardwarePercent = null
                mode = VolumeMode.SOFTWARE
                software.volume(percent)
                Diag.warn(
                    "speakers",
                    "$id: volume from the phone $percent% went to mpv's own volume because the speaker's own is " +
                        "unavailable: ${answer.reason}",
                )
            }
        }
    }

    private fun followed(percent: Int, raw: Int, answer: TransportVolume.Level) {
        hardwarePercent = answer.percent
        hardwareRaw = answer.raw
        val mpv = software.volumePercent
        val turnedDown = mpv != null && mpv != FULL
        val holdsBack = answer.raw > raw + TOLERANCE_RAW
        val inputs = "asked raw=$raw, it reports raw=${answer.raw} = ${answer.percent}%, mpv=${mpv ?: "untouched"}"
        when {
            !turnedDown -> {
                mode = VolumeMode.HARDWARE
                Diag.log(
                    "speakers",
                    "$id: volume from the phone $percent% went to the speaker's own ($inputs); mpv left alone",
                )
            }
            !holdsBack -> {
                mode = VolumeMode.HARDWARE
                software.volume(FULL)
                Diag.log(
                    "speakers",
                    "$id: volume from the phone $percent% went to the speaker's own ($inputs); " +
                        "mpv put back from $mpv% to $FULL%",
                )
            }
            else -> {
                val matched = (percent * FULL / answer.percent.coerceAtLeast(1).toDouble()).roundToInt()
                    .coerceIn(0, FULL)
                mode = VolumeMode.SOFTWARE
                software.volume(matched)
                Diag.warn(
                    "speakers",
                    "$id: volume from the phone $percent% went to the speaker's own but it still reports a " +
                        "higher level ($inputs); mpv is not put back to $FULL%, it is set to $matched% so what " +
                        "is heard stays at about $percent%",
                )
            }
        }
    }

    override suspend fun refresh() = gate.withLock { refreshLocked() }

    private suspend fun refreshLocked() {
        val chosen = software.volumePercent
        when (val answer = transport.volume()) {
            is TransportVolume.Level -> {
                hardwarePercent = answer.percent
                mode = if (chosen == null || chosen == FULL) VolumeMode.HARDWARE else VolumeMode.SOFTWARE
                note(
                    "speaker's own volume is ${answer.percent}% (raw=${answer.raw}); mode=$mode" +
                        if (mode == VolumeMode.SOFTWARE) {
                            ", mpv still holds $chosen% from earlier and the next volume change from the phone " +
                                "moves it to the speaker's own"
                        } else {
                            ""
                        },
                )
                hardwareRaw = answer.raw
            }
            is TransportVolume.Unavailable -> {
                hardwarePercent = null
                hardwareRaw = null
                mode = if (chosen != null) VolumeMode.SOFTWARE else null
                note("speaker's own volume unavailable (${answer.reason}); mode=$mode mpv=${chosen ?: "untouched"}")
            }
        }
    }

    override suspend fun beginFade(): VolumeFade? = gate.withLock {
        refreshLocked()
        val raw = hardwareRaw
        val mpv = software.volumePercent
        when {
            mode == VolumeMode.HARDWARE && raw != null -> HardwareFade(raw)
            mode == VolumeMode.SOFTWARE && mpv != null -> SoftwareFade(mpv)
            else -> null
        }.also {
            Diag.log(
                "speakers",
                "$id: a fade ${if (it == null) "cannot begin" else "begins on the control mode=$mode names"} " +
                    "(speaker raw=${raw ?: "unknown"}, mpv=${mpv ?: "untouched"}, base=${it?.basePercent})",
            )
        }
    }

    private inner class HardwareFade(private val startRaw: Int) : VolumeFade {
        override val basePercent: Int = HardwareVolume.percentOf(startRaw)

        override suspend fun set(percent: Int): Boolean = gate.withLock {
            val raw = if (percent >= basePercent) startRaw else minOf(startRaw, HardwareVolume.rawOf(percent))
            when (val answer = transport.setVolume(raw)) {
                is TransportVolume.Level -> {
                    hardwarePercent = answer.percent
                    hardwareRaw = answer.raw
                    true
                }
                is TransportVolume.Unavailable -> {
                    Diag.warn(
                        "speakers",
                        "$id: fade write of raw=$raw (asked $percent%) not made, the speaker's own volume is " +
                            "unavailable: ${answer.reason}; mpv is not used in its place",
                    )
                    false
                }
            }
        }
    }

    private inner class SoftwareFade(private val start: Int) : VolumeFade {
        override val basePercent: Int = start

        override suspend fun set(percent: Int): Boolean = gate.withLock {
            software.volume(if (percent >= start) start else percent)
            true
        }
    }

    private fun note(what: String) {
        if (what == lastLogged) return
        lastLogged = what
        Diag.log("speakers", "$id: $what")
    }

    private companion object {
        const val FULL = 100
        const val TOLERANCE_RAW = 2
    }
}
