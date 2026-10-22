package com.dewijones92.totum.playback

import com.dewijones92.totum.common.Diag
import com.dewijones92.totum.domain.MediaItemId
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Drops video for audio when the phone falls off Wi-Fi onto mobile data, and says so.
 *
 * Dewi, 2026-08-04: *"if the phone is playing video but then there is suddenly no wifi, a
 * notification appears saying 'hey we have switched to listening only mode'"*.
 *
 * The saving is the point and it is large: measured on a torrent, 15.2 MB/min for the whole stream
 * against 2.1 for the audio alone. Walking out of the house with a video playing otherwise spends
 * mobile data at eight times the rate, silently, until the bill says so.
 *
 * Nothing here knows about pillars. Whether the audio-only route is a YouTube audio stream or an
 * HLS playlist the home server remuxed is [switchToAudio]'s business — this decides *when*, which
 * is the same question for both.
 *
 * **Hysteresis is the whole risk.** A connection bouncing between Wi-Fi and mobile — a lift, a
 * train, the end of the drive — would otherwise thrash a re-prepare every few seconds and stutter
 * playback continuously. So metered has to HOLD for [holdMs] before anything happens, which turns
 * the flapping case into a no-op rather than the worst experience in the app.
 *
 * It samples on a clock rather than collecting a flow, for the same reason [StallWatchdog] does:
 * connectivity is a level, not an event, and the interesting case is a state that persists.
 *
 * @param metered whether the active connection charges by the byte (mobile, hotspot).
 * @param playingVideoId the item playing WITH VIDEO, or null when there is nothing to downgrade —
 *   already audio, paused, or stopped.
 * @param switchToAudio re-plays the current item as audio from where it is.
 * @param announce tells the person what happened, and how to undo it.
 */
internal class MeteredAudioSwitch(
    private val metered: () -> Boolean,
    private val playingVideoId: () -> MediaItemId?,
    private val switchToAudio: suspend () -> Boolean,
    private val announce: (MediaItemId) -> Unit,
    private val scope: CoroutineScope,
    private val checkEveryMs: Long = CHECK_MS,
    private val holdMs: Long = HOLD_MS,
) {
    private var meteredForMs = 0L

    /**
     * The item switched on this stretch of mobile data, or null before the first switch.
     *
     * Once per stretch, not once per item: report 20261017T082929 had the picture put back on by hand
     * and the very next video switched to audio again, because the stretch had long passed the hold.
     * Cleared when Wi-Fi returns, so the next trip off it switches again.
     */
    private var switched: MediaItemId? = null

    private var lastStoodDownFor: MediaItemId? = null

    fun start() {
        Diag.log("data", "watching for a drop onto mobile data (hold ${holdMs}ms before switching)")
        scope.launch {
            while (true) {
                delay(checkEveryMs)
                consider()
            }
        }
    }

    private suspend fun consider() {
        if (!metered()) {
            // Back on Wi-Fi. Deliberately does NOT switch video back on: the screen lighting up
            // with video nobody asked for is worse than staying where you are, and the person can
            // choose it in one tap. Dewi's call, 2026-08-04.
            if (meteredForMs > 0) Diag.log("data", "back on unmetered network — the switch is re-armed")
            meteredForMs = 0
            switched = null
            lastStoodDownFor = null
            return
        }
        meteredForMs += checkEveryMs
        if (meteredForMs < holdMs) return

        val id = playingVideoId() ?: return
        switched?.let { first ->
            if (id != first && lastStoodDownFor != id) {
                Diag.log(
                    "data",
                    "${id.value} plays video on mobile data: not switching, this stretch already switched " +
                        "${first.value} and the picture was put back by hand",
                )
                lastStoodDownFor = id
            }
            return
        }
        switched = id

        Diag.log("data", "${id.value} has been on mobile data ${meteredForMs}ms — switching to audio only")
        if (switchToAudio()) {
            announce(id)
        } else {
            // Said out loud: a failed switch leaves video running on mobile data, which is the
            // opposite of what was intended and would otherwise be invisible.
            Diag.warn("data", "${id.value} could not be switched to audio — it is still using video data")
        }
    }

    private companion object {
        const val CHECK_MS = 5_000L

        /**
         * How long mobile data has to hold before acting. Long enough that a lift, a tunnel or the
         * end of the drive never triggers it; short enough that a real journey is caught in the
         * first few hundred kilobytes rather than the first few megabytes.
         */
        const val HOLD_MS = 15_000L
    }
}
