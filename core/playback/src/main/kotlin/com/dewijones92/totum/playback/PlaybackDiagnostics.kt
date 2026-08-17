package com.dewijones92.totum.playback

import android.os.SystemClock
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import com.dewijones92.totum.common.Diag
import com.dewijones92.totum.common.Vitals
import androidx.media3.common.MediaItem as Media3MediaItem

/**
 * Records what playback actually did — errors, stalls, transitions — as breadcrumbs and
 * running [Vitals].
 *
 * It exists because none of this was observable. There was no [Player.Listener.onPlayerError]
 * anywhere in the app, so a failed stream was completely silent: the UI sat there and a
 * crash report carried no hint. Buffering was equally invisible — `isBuffering` drove a
 * spinner but nothing counted or timed the stalls, which is why "is it buffering?" could
 * only be answered by watching the screen.
 *
 * A separate listener from the one that publishes [PlaybackState] on purpose: observing is
 * not the same job as mapping state, and this way logging can never break playback.
 */
// The count is Player.Listener's own callback surface plus a few small formatting helpers; each
// callback observes one thing, and merging any of them to satisfy the counter would hide which.
@Suppress("TooManyFunctions")
internal class PlaybackDiagnostics(
    private val player: () -> Player?,
    private val now: () -> Long = System::currentTimeMillis,
) : Player.Listener {

    private var stalledSince: Long? = null

    /**
     * Wall clock at the last end of playback, so the GAP to the next item can be stated.
     *
     * The one number that says whether autoplay felt right, and it was the one number nowhere in
     * a report: "ended" and "playing" both carry media positions, not wall clock, so a
     * three-second handover and a forty-second one read identically unless both lines happen to
     * survive the bounded buffer AND someone subtracts their timestamps by hand. Measured here
     * instead, because a resolve on the SABR path costs ~200ms and an extraction 14-25s — the
     * difference is entirely audible and nothing was reporting it.
     */
    private var endedAt: Long? = null

    override fun onPlayerError(error: PlaybackException) {
        Vitals.add("playback.errors")
        Vitals.set("playback.lastError", "${error.errorCodeName}: ${error.message}")
        Diag.warn(
            "playback",
            "ERROR ${error.errorCodeName} (${error.errorCode}) at ${position()} — ${describeItem()}",
            error,
        )
    }

    /**
     * Closes off the stall in progress, counting it however it ended.
     *
     * **The stall that never recovers is the one that matters, and it used to count for nothing.**
     * `playback.bufferingMs` was written only on `STATE_READY`, and every other way out of
     * BUFFERING — a transition, ENDED, IDLE — just discarded `stalledSince`. So a spinner the
     * person escaped by pressing play again contributed zero. Report 0.1.332 recorded
     * `bufferingMs = 1370` for a session containing a **136-second** freeze, which is why "we have
     * lots of buffering issues" never showed up in the numbers: the metric structurally excluded
     * the worst cases.
     *
     * Abandoned time is counted separately as well as in the total. It is the figure that
     * corresponds to what someone actually experienced — a stall that resolved in 400ms and one
     * that was still frozen when they gave up are not the same event, and summing them hides
     * exactly the difference worth seeing.
     */
    private fun endStall(recovered: Boolean): Long? {
        val waited = stalledSince?.let { now() - it } ?: return null
        stalledSince = null
        Vitals.add("playback.bufferingMs", waited)
        if (!recovered) {
            Vitals.add("playback.abandonedBufferingMs", waited)
            Diag.warn(
                "playback",
                "gave up buffering after ${waited}ms at ${position()} — it never recovered",
            )
        }
        return waited
    }

    /**
     * The moment the player stops fetching, and how much it had when it stopped.
     *
     * This is the line that separates the two explanations for a stall near the end of an item, and
     * neither was distinguishable without it. If loading stops with the buffer reaching the
     * duration, the item is fully fetched and anything after that is playback's problem. If it
     * stops **short** — report 0.1.359 stalled with 70ms buffered and 35 seconds of the item still
     * to come — then the fetch gave up believing it was done, and the tail never arrived at all.
     *
     * A count of buffering milliseconds cannot tell those apart, which is why that report could
     * only be diagnosed by reading code.
     *
     * The judgement itself is [loadStopIsAFault], and it was wrong for a month: it asked whether
     * the buffer had reached `MIN_BUFFER_MS`, which is the level below which loading *resumes*
     * rather than a level meaning anything is wrong — and `PLAYBACK_BYTES` puts 30 seconds out of
     * reach for a 1080p AV1 stream anyway. So every ordinary pause was reported as a lost tail: 33
     * of the 400 events in 0.1.390, all false, with `loadsStoppedShort = 92` measuring nothing.
     */
    override fun onIsLoadingChanged(isLoading: Boolean) {
        if (isLoading) return
        val current = player() ?: return
        val buffered = current.bufferedPosition
        val ahead = buffered - current.currentPosition
        val unfetched = current.duration.takeIf { it > 0 }?.minus(buffered)
        // Stopping because the buffer is FULL is the load control doing its job, and it happens
        // constantly — counted, never a line each, because the report buffer is bounded and a
        // chatty log destroys the history it is meant to preserve. The gauge is what the 33 lines
        // were actually worth saying: how low the buffer settles when loading stops. One number
        // instead of a line each, and it is what would have shown the byte ceiling binding at ~25s
        // against a 30s target without anyone reading the code.
        if (ahead < leastAheadAtLoadStop) {
            leastAheadAtLoadStop = ahead
            Vitals.set("playback.leastAheadAtLoadStop", "${ahead}ms")
        }
        if (!loadStopIsAFault(ahead, unfetched, isStalled = stalledSince != null)) {
            Vitals.add("playback.loadPauses")
            return
        }
        Diag.warn(
            "playback",
            "stopped loading at ${position()} with only ${ahead}ms buffered ahead and " +
                "${unfetched}ms of the item never fetched — the tail is not coming",
        )
        Vitals.add("playback.loadsStoppedShort")
    }

    /** The lowest `ahead` any load stop has been seen at, for the gauge above. */
    private var leastAheadAtLoadStop = Long.MAX_VALUE

    /**
     * Times each stall rather than just noting it. A duration is what distinguishes a
     * normal start-up buffer from the repeated mid-item stalls that read as "buffering".
     */
    override fun onPlaybackStateChanged(playbackState: Int) {
        when (playbackState) {
            Player.STATE_BUFFERING -> {
                stalledSince = now()
                Vitals.add("playback.stalls")
                val kbps = PlaybackVitals.kbps()
                val vitals = Vitals.snapshot()
                val outstanding = vitals["playback.loadsOutstanding"]
                // Chunk size and the oldest in-flight load turn "it buffered" into a diagnosis:
                // small chunks arriving slowly is a throttled stream, one load sat there for
                // minutes is the player waiting on a request that will never land.
                Diag.log(
                    "playback",
                    "buffering at ${position()}" + (kbps?.let { " (was ~${it}kbps" } ?: " (") +
                        ", $outstanding load(s) in flight" +
                        ", oldest ${oldestLoadAge(vitals["playback.oldestLoadStartedAt"])}" +
                        ", ~${vitals["playback.avgChunkKb"] ?: "?"}KB chunks)",
                )
                // WHICH loads, not just how many. A count of 37 with the oldest seven hours old
                // (report 0.1.359) cannot say whether the stuck stream is the video, the audio or
                // a subtitle — and those have different fixes. See PlaybackAnalytics.publishInFlight.
                Diag.log("playback", "in flight: ${vitals["playback.loadsInFlight"] ?: "?"}")
                // Per TRACK, because the player's single buffered position is the MINIMUM across a
                // merged video+audio stream: one half stopping short pins it while the other is fine,
                // and nothing could tell them apart. See PlaybackAnalytics.recordLoadedTo.
                Diag.log("playback", "loaded to: ${vitals["playback.loadedTo"] ?: "?"}")
            }
            Player.STATE_READY -> {
                val waited = endStall(recovered = true)
                if (waited != null) {
                    // The throughput at the moment it recovered is what separates a
                    // throttled stream from a connection that simply cannot carry 1080p.
                    val kbps = PlaybackVitals.kbps()
                    kbps?.let { Vitals.set("playback.lastRecoveryKbps", it.toString()) }
                    Diag.log(
                        "playback",
                        "ready after ${waited}ms at ${position()}" +
                            (kbps?.let { " (throughput ~${it}kbps)" } ?: ""),
                    )
                }
            }
            Player.STATE_ENDED -> {
                endStall(recovered = false)
                reportEnd()
            }
            Player.STATE_IDLE -> {
                endStall(recovered = false)
                Diag.log("playback", "idle")
            }
        }
    }

    /**
     * Says whether a video ended where it was SUPPOSED to.
     *
     * "ended" alone cannot be judged: a stream that stops short looks exactly like a short video,
     * and the queue advances either way. So the end is reported against the duration, and a
     * finish more than [EARLY_END_TOLERANCE_MS] short of it is named as early — which is the
     * symptom to look for on the SABR path, where a stalled fetch used to be taken for an end.
     */
    private fun reportEnd() {
        val player = player()
        val duration = player?.duration?.takeIf { it > 0 }
        val at = player?.currentPosition ?: 0
        val shortBy = duration?.minus(at) ?: 0
        if (duration != null && shortBy > EARLY_END_TOLERANCE_MS) {
            Vitals.add("playback.earlyEnds")
            Diag.warn(
                "playback",
                "ENDED EARLY at ${at}ms of ${duration}ms — ${shortBy}ms short (${at * PERCENT / duration}%) " +
                    "— ${describeItem()}",
            )
        } else {
            Diag.log("playback", "ended at ${at}ms of ${duration ?: -1}ms — ${describeItem()}")
        }
        endedAt = now()
    }

    /** The reason matters: an automatic advance and a user tap look identical without it. */
    override fun onMediaItemTransition(mediaItem: Media3MediaItem?, reason: Int) {
        // Counted, not discarded. Moving off an item that was still buffering is precisely the
        // "I gave up and pressed play again" case, and it used to erase its own evidence.
        endStall(recovered = false)
        Vitals.add("playback.transitions")
        Diag.log("playback", "transition (${reasonName(reason)}) -> ${mediaItem?.mediaId ?: "nothing"}")
    }

    /**
     * "Not playing" is three different things — paused, stalled, finished — and Media3
     * reports them all here. Saying which matters: the first test run logged "paused"
     * in the middle of a 15-second stall, which reads like the user did it.
     */
    override fun onIsPlayingChanged(isPlaying: Boolean) {
        val why = when {
            isPlaying -> "playing"
            player()?.playWhenReady == true -> "not advancing (wants to play)"
            else -> "paused"
        }
        // How long the silence lasted, said once and only after an end — a pause the user made
        // is not a handover and must not be reported as one.
        val handover = endedAt?.takeIf { isPlaying }?.let { ended ->
            endedAt = null
            val gap = now() - ended
            Vitals.add("playback.handovers")
            Vitals.add("playback.handoverMs", gap)
            " — ${gap}ms of silence since the last item ended" +
                if (gap > SLOW_HANDOVER_MS) " (SLOW handover)" else ""
        }
        Diag.log("playback", "$why at ${position()}${handover ?: ""}")
    }

    override fun onPositionDiscontinuity(
        oldPosition: Player.PositionInfo,
        newPosition: Player.PositionInfo,
        reason: Int,
    ) {
        // Seeks only: an automatic period transition fires this on every item change and
        // would bury the trail in lines that onMediaItemTransition already covers.
        if (reason != Player.DISCONTINUITY_REASON_SEEK) return
        Diag.log("playback", "seek ${oldPosition.positionMs}ms -> ${newPosition.positionMs}ms")
    }

    private fun position(): String = player()?.let { "${it.currentPosition}ms" } ?: "?"

    private fun describeItem(): String {
        val current = player()?.currentMediaItem ?: return "nothing playing"
        return "${current.mediaId} \"${current.mediaMetadata.title}\""
    }

    private fun reasonName(reason: Int): String = when (reason) {
        Player.MEDIA_ITEM_TRANSITION_REASON_AUTO -> "auto"
        Player.MEDIA_ITEM_TRANSITION_REASON_SEEK -> "seek"
        Player.MEDIA_ITEM_TRANSITION_REASON_PLAYLIST_CHANGED -> "playlist-changed"
        Player.MEDIA_ITEM_TRANSITION_REASON_REPEAT -> "repeat"
        else -> "reason-$reason"
    }

    private companion object {
        /**
         * How near the duration counts as a proper finish. Generous, because a container's
         * declared duration and its last sample rarely agree to the millisecond, and crying
         * "early" on every ordinary ending would make the warning worthless.
         */
        const val EARLY_END_TOLERANCE_MS = 5_000L

        /**
         * A handover longer than this is worth flagging in the line itself.
         *
         * Three seconds because that is roughly where a gap stops reading as a pause between
         * tracks and starts reading as something being broken. It is a label, not a threshold
         * anything acts on — every handover is timed either way.
         */
        const val SLOW_HANDOVER_MS = 3_000L
        const val PERCENT = 100
    }
}

/**
 * Whether a load that has just stopped left the item's tail genuinely unreachable.
 *
 * Pure and top-level so the judgement is unit-testable: reaching it through the listener needs a
 * Media3 `Player`, and the thresholds are the part that was wrong. See [LoadStopIsAFaultTest] for
 * the 33 false lines this replaces and the one real case it must keep catching.
 *
 * [isStalled] is the discriminator, not the buffer level. Loading stops constantly on a healthy
 * stream — the byte ceiling or the duration target is reached, the buffer drains, it resumes — and
 * with minutes of a long item still unfetched every one of those looks identical to a lost tail.
 * What is not ordinary is stopping while playback cannot continue, or stopping with so little ahead
 * that it is one hiccup from the same thing.
 */
internal fun loadStopIsAFault(aheadMs: Long, unfetchedMs: Long?, isStalled: Boolean): Boolean {
    if (unfetchedMs == null || unfetchedMs <= SHORT_OF_THE_END_MS) return false
    return isStalled || aheadMs <= TOO_LITTLE_AHEAD_MS
}

/** Below this much left unfetched, the player is simply at the end of the item. */
private const val SHORT_OF_THE_END_MS = 5_000L

/**
 * A buffer this thin is a stall waiting to happen, so a load stopping here is worth a line even
 * while playback is still going. Deliberately far below `BufferBudget.MIN_BUFFER_MS`, which is a
 * "resume loading" level and says nothing about health — using it as one is the bug this fixes.
 */
private const val TOO_LITTLE_AHEAD_MS = 1_000L

/**
 * How long the oldest in-flight load has been running, computed HERE rather than stored.
 *
 * It used to be written as a pre-computed age on each load event and read back at stall
 * time — which meant it froze exactly when it mattered, since a stalled player issues no
 * load events. Report 0.1.306 printed "oldest 22206ms" twice, six and a half minutes apart,
 * with the identical value both times. A start timestamp read against the clock cannot lie
 * that way.
 */
private fun oldestLoadAge(startedAt: String?): String {
    val at = startedAt?.toLongOrNull() ?: return "?"
    if (at < 0) return "none"
    return "${(SystemClock.elapsedRealtime() - at).coerceAtLeast(0)}ms"
}
