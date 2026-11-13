package com.dewijones92.totum.cli.serve.speakers

import com.dewijones92.totum.cli.serve.targets.TargetPlayer
import com.dewijones92.totum.cli.serve.targets.TargetVolume
import com.dewijones92.totum.common.Diag
import com.dewijones92.totum.data.targets.VolumeMode

internal class SpeakerVolume(
    private val id: String,
    private val transport: SpeakerTransport,
    private val software: TargetPlayer,
) : TargetVolume {
    @Volatile
    private var hardwarePercent: Int? = null

    @Volatile
    override var mode: VolumeMode? = null
        private set

    @Volatile
    private var lastLogged: String? = null

    override val percent: Int? get() = if (mode == VolumeMode.HARDWARE) hardwarePercent else software.volumePercent

    override suspend fun set(percent: Int) {
        val raw = HardwareVolume.rawOf(percent)
        when (val answer = transport.setVolume(raw)) {
            is TransportVolume.Level -> {
                hardwarePercent = answer.percent
                mode = VolumeMode.HARDWARE
                val mpv = software.volumePercent
                val putBack = mpv != null && mpv != FULL
                if (putBack) software.volume(FULL)
                Diag.log(
                    "speakers",
                    "$id: volume from the phone $percent% went to the speaker's own (asked raw=$raw, " +
                        "it reports raw=${answer.raw} = ${answer.percent}%); mpv " +
                        if (putBack) "put back from $mpv% to $FULL%" else "left at ${mpv ?: "untouched"}",
                )
            }
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

    override suspend fun refresh() {
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
            }
            is TransportVolume.Unavailable -> {
                hardwarePercent = null
                mode = if (chosen != null) VolumeMode.SOFTWARE else null
                note("speaker's own volume unavailable (${answer.reason}); mode=$mode mpv=${chosen ?: "untouched"}")
            }
        }
    }

    private fun note(what: String) {
        if (what == lastLogged) return
        lastLogged = what
        Diag.log("speakers", "$id: $what")
    }

    private companion object {
        const val FULL = 100
    }
}
