package com.dewijones92.totum.playback

import android.app.PendingIntent
import android.content.Context
import android.os.Bundle
import androidx.media3.cast.CastPlayer
import androidx.media3.cast.SessionAvailabilityListener
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.Tracks
import androidx.media3.common.audio.SonicAudioProcessor
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink
import androidx.media3.exoplayer.audio.SilenceSkippingAudioProcessor
import androidx.media3.exoplayer.drm.DrmSessionManagerProvider
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.source.MergingMediaSource
import androidx.media3.exoplayer.upstream.DefaultBandwidthMeter
import androidx.media3.exoplayer.upstream.LoadErrorHandlingPolicy
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionResult
import com.dewijones92.totum.common.Diag
import com.google.android.gms.cast.framework.CastContext
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture

/**
 * Foreground media service so playback continues when the app is backgrounded.
 * Media3 renders and updates the media notification itself; the seek
 * increments below surface as skip buttons in the notification and on the
 * lock screen.
 */
public class PlaybackService : MediaSessionService() {

    private var mediaSession: MediaSession? = null

    /**
     * Detects silence so it can be handled by **rate**, not by dropping samples.
     * Dropping samples shortens the audio but not the video clock, which is why
     * skip-silence used to be audio-only; speeding up retimes both together, so this
     * works on video too and cannot desync.
     */
    @UnstableApi
    private val silenceDetector = SilenceDetectingAudioProcessor { silent ->
        mainHandler.post { onSilenceChanged(silent) }
    }

    private var silenceChanges = 0L

    /** The user's skip-silences intent. Applies to both pillars now. */
    private var skipSilenceEnabled = false

    /**
     * Media3's own sample-removing processor — the mechanism AntennaPod uses, and the reason it
     * sounds seamless. Enabled only when nothing is being kept in sync with the audio clock.
     */
    @UnstableApi
    private val silenceSkipper = SilenceSkippingAudioProcessor()

    /**
     * The volume boost, in the chain rather than on the audio session.
     *
     * Replaces the platform `LoudnessEnhancer`, which was capped, could not compress, and had to be
     * rebuilt on every audio-session change — see [BoostingAudioProcessor].
     */
    @UnstableApi
    private val booster = BoostingAudioProcessor()

    /** The rate the user chose and whether we are racing through silence — see [SilenceRacer]. */
    private val racer = SilenceRacer()

    private val mainHandler = android.os.Handler(android.os.Looper.getMainLooper())

    /** Follows the CONTENT, so the silence mechanism suits whatever is playing now. */
    private val speedWatcher = object : Player.Listener {
        /**
         * A queue mixes both kinds, so the mechanism has to follow the content — the same switch
         * means sample-removal for a podcast and a rate change for the video after it. Video size
         * rather than track type: it is what the player reports once a picture is actually being
         * rendered, which is the thing that must not desync.
         */
        @UnstableApi
        override fun onTracksChanged(tracks: androidx.media3.common.Tracks) {
            applySilenceStrategy()
        }
    }
    private var player: ExoPlayer? = null

    // Cast: present only when Google Play Services + a receiver are available.
    // The session swaps between the local player and this one as a Cast session
    // comes and goes; null (or no devices) means everything plays locally as before.
    @UnstableApi
    private var castPlayer: CastPlayer? = null
    private var currentPlayer: Player? = null

    // Last item/position handed across a local↔cast switch, so a cast disconnect
    // (which nulls the CastPlayer's queue) can still resume locally.
    private var lastHandoffItem: MediaItem? = null
    private var lastHandoffPositionMs: Long = 0

    @androidx.annotation.OptIn(markerClass = [UnstableApi::class])
    override fun onCreate() {
        super.onCreate()
        // A custom audio sink whose processor chain carries the silence skipper
        // (Sonic stays for speed/pitch); skipping is off until the user turns it on.
        val renderersFactory = object : DefaultRenderersFactory(this) {
            override fun buildAudioSink(
                context: Context,
                enableFloatOutput: Boolean,
                enableAudioTrackPlaybackParams: Boolean,
            ): AudioSink {
                return DefaultAudioSink.Builder(context)
                    .setEnableFloatOutput(enableFloatOutput)
                    .setEnableAudioTrackPlaybackParams(enableAudioTrackPlaybackParams)
                    .setAudioProcessorChain(
                        // The detector only observes; Sonic does the actual retiming.
                        // The detector only observes; the skipper removes silent samples when
                        // there is no picture to keep in sync; Sonic does the retiming when there
                        // is. Exactly one of the last two is ever active — see SilenceStrategy.
                        // The THREE-ARGUMENT constructor, and it matters more than it looks. The
                        // vararg one treats every processor as opaque and builds its own (idle)
                        // silence skipper, so the chain reports ZERO skipped frames — and the sink
                        // corrects its clock from exactly that number. Handing ours over by name is
                        // what lets the media clock learn that samples were removed, which is what
                        // NewPipe/PipePipe get for free by calling setSkipSilenceEnabled().
                        DefaultAudioSink.DefaultAudioProcessorChain(
                            // The booster goes AFTER the detector: the detector decides what counts
                            // as silence, and it must judge the recording rather than the recording
                            // plus 30dB, or a boosted noise floor would read as speech and
                            // skip-silence would stop skipping anything.
                            arrayOf(silenceDetector, booster),
                            silenceSkipper,
                            SonicAudioProcessor(),
                        ),
                    )
                    .build()
            }
        }
        // Held so stalls can be reported with the throughput at the time. Without it a
        // stall is just "it stopped": a stream delivering 60 kbps and a 1080p stream that
        // needs more than the connection has look identical, and the fixes are opposite.
        val bandwidth = DefaultBandwidthMeter.Builder(this).build()
        PlaybackVitals.bitrateEstimate = bandwidth::getBitrateEstimate
        val player = ExoPlayer.Builder(this)
            .setRenderersFactory(renderersFactory)
            .setBandwidthMeter(bandwidth)
            .setLoadControl(
                DefaultLoadControl.Builder()
                    .setBufferDurationsMs(
                        BufferBudget.MIN_BUFFER_MS,
                        BufferBudget.MAX_BUFFER_MS,
                        DefaultLoadControl.DEFAULT_BUFFER_FOR_PLAYBACK_MS,
                        DefaultLoadControl.DEFAULT_BUFFER_FOR_PLAYBACK_AFTER_REBUFFER_MS,
                    )
                    // A little behind too, so a small scrub back does not refetch.
                    .setBackBuffer(BufferBudget.BACK_BUFFER_MS, true)
                    // The byte ceiling that makes the duration above safe — see [BufferBudget].
                    .setTargetBufferBytes(BufferBudget.PLAYBACK_BYTES)
                    .build(),
            )
            // Ranged fetches, not one open-ended GET: see ChunkedDataSource for the
            // measurements. This is what stops the every-seven-seconds stalling.
            .setMediaSourceFactory(sourceFactory())
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(C.USAGE_MEDIA)
                    .setContentType(C.AUDIO_CONTENT_TYPE_SPEECH)
                    .build(),
                // handleAudioFocus:
                true,
            )
            .setHandleAudioBecomingNoisy(true)
            .setSeekBackIncrementMs(SEEK_BACK_MS)
            .setSeekForwardIncrementMs(SEEK_FORWARD_MS)
            .build()
        this.player = player
        // Where the detail behind a stall comes from: chosen format, per-chunk
        // throughput, load failures, dropped frames. Media3 exposes it only here.
        player.addAnalyticsListener(PlaybackAnalytics())
        player.addListener(speedWatcher)
        currentPlayer = player
        // When the tracks change (a new item, video vs audio), re-apply the effective
        // skip-silence: off whenever a video track is present, so A/V stays in sync.
        player.addListener(
            object : Player.Listener {
                override fun onTracksChanged(tracks: Tracks) = applyEffectiveSkipSilence()

                // The service's own view of what started playing. Worth a line: the app logs the
                // transition it ASKED for, which is not evidence the player made it.
                override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                    Diag.log("playback", "service now on ${mediaItem?.mediaId ?: "nothing"} (reason $reason)")
                    cachedPreloader?.releaseIfPlaying(mediaItem)
                }
            },
        )
        cachedPreloader = NextItemPreloader(this, ::sourceFactory)
        setUpCast(player)
        mediaSession = MediaSession.Builder(this, currentPlayer ?: player)
            .setCallback(SkipSilenceCallback())
            .apply { openAppIntent()?.let { setSessionActivity(it) } }
            .build()
    }

    /**
     * The one factory, shared by the player and the preloader.
     *
     * They MUST be the same: a source preloaded by one factory and played through another is a
     * different object with different settings, and the preloaded bytes would simply be discarded.
     */
    @UnstableApi
    private var cachedSourceFactory: MergingAudioVideoFactory? = null

    // A function rather than `by lazy`: Android lint does not follow an opt-in into a lazy lambda,
    // and the annotation has to sit somewhere it understands.
    @UnstableApi
    private fun sourceFactory(): MergingAudioVideoFactory = cachedSourceFactory ?: run {
        MergingAudioVideoFactory(
            DefaultMediaSourceFactory(this).setDataSourceFactory(
                // sabr:// URLs are served from a registered session; everything else goes through
                // the ranged fetcher exactly as before, so the path that already works is untouched.
                SabrDataSourceFactory(
                    ChunkedDataSource.Factory(DefaultDataSource.Factory(this)),
                ),
            ),
        ).also { cachedSourceFactory = it }
    }

    /** Holds the first seconds of what is coming next; see [NextItemPreloader]. */
    @UnstableApi
    private var cachedPreloader: NextItemPreloader? = null

    // Constructed in onCreate rather than lazily: Android lint does not follow an opt-in into a
    // lazy lambda (see [sourceFactory]), and it is cheap — it builds no preload manager until it
    // is first asked to hold something.

    /** Adds the skip-silences custom command and applies it to the audio processor. */
    @UnstableApi
    private inner class SkipSilenceCallback : MediaSession.Callback {
        override fun onConnect(
            session: MediaSession,
            controller: MediaSession.ControllerInfo,
        ): MediaSession.ConnectionResult =
            MediaSession.ConnectionResult.AcceptedResultBuilder(session)
                .setAvailableSessionCommands(
                    MediaSession.ConnectionResult.DEFAULT_SESSION_COMMANDS.buildUpon()
                        .add(SessionCommand(ACTION_SKIP_SILENCE, Bundle.EMPTY))
                        .add(SessionCommand(ACTION_VOLUME_BOOST, Bundle.EMPTY))
                        .add(SessionCommand(ACTION_PRELOAD_NEXT, Bundle.EMPTY))
                        .add(SessionCommand(ACTION_USER_SPEED, Bundle.EMPTY))
                        .build(),
                )
                .build()

        override fun onCustomCommand(
            session: MediaSession,
            controller: MediaSession.ControllerInfo,
            customCommand: SessionCommand,
            args: Bundle,
        ): ListenableFuture<SessionResult> {
            if (customCommand.customAction == ACTION_PRELOAD_NEXT) {
                val uri = args.getString(EXTRA_PRELOAD_URI)
                val itemId = args.getString(EXTRA_PRELOAD_ITEM_ID)
                if (uri != null && itemId != null) {
                    cachedPreloader?.hold(itemId, uri)
                } else {
                    Diag.warn("preload", "nomination with no ${if (uri == null) "uri" else "item id"} — ignored")
                }
                return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
            }
            if (customCommand.customAction == ACTION_VOLUME_BOOST) {
                // By NAME, not by a gain figure: the level is what the boost means and the
                // decibels are its current implementation, so sending the number would have to be
                // kept in step with the enum by hand.
                applyVolumeBoost(
                    args.getString(EXTRA_VOLUME_BOOST_LEVEL)
                        ?.let { name -> runCatching { VolumeBoost.valueOf(name) }.getOrNull() }
                        ?: VolumeBoost.OFF,
                )
                return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
            }
            if (customCommand.customAction == ACTION_USER_SPEED) {
                applyUserSpeed(args.getFloat(EXTRA_USER_SPEED, SilenceRacer.NORMAL))
                return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
            }
            if (customCommand.customAction == ACTION_SKIP_SILENCE) {
                val enabled = args.getBoolean(EXTRA_SKIP_SILENCE_ENABLED)
                Diag.log("playback", "skip-silence -> $enabled")
                skipSilenceEnabled = enabled
                applySilenceStrategy()
                applyEffectiveSkipSilence()
                return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
            }
            return super.onCustomCommand(session, controller, customCommand, args)
        }
    }

    /**
     * Races through a silent stretch by raising the playback rate, and drops back when
     * sound returns. [Player.setPlaybackSpeed] retimes audio *and* video, so this holds
     * for both pillars and cannot pull them apart — the reason this replaced the
     * sample-dropping processor.
     */
    private fun onSilenceChanged(silent: Boolean) {
        val target = player ?: return
        // Sample removal handles its own gaps entirely, so touching the rate as well would add
        // back the audible step this exists to avoid.
        if (strategy == SilenceStrategy.REMOVE_SAMPLES || !skipSilenceEnabled) {
            racer.stopRacing()?.let(target::setPlaybackSpeed)
            return
        }
        val speed = racer.silence(silent) ?: return
        // Counted rather than logged per change. Speech enters silence every few seconds,
        // so a line per transition is not diagnostics — it is a flood that evicts them: a
        // real report from 0.1.170 was 59% skip-silence, leaving 16 minutes of history in
        // a buffer that should hold hours, right when a stall needed explaining.
        silenceChanges++
        if (silenceChanges == 1L || silenceChanges % SILENCE_LOG_EVERY == 0L) {
            Diag.log("playback", "skip-silence change #$silenceChanges -> speed=$speed (user=${racer.userSpeed})")
        }
        target.setPlaybackSpeed(speed)
    }

    /**
     * Applies gain to the player's audio session with the platform's own enhancer.
     * A session effect can't reach a Cast receiver, so this is local playback only —
     * accepted rather than half-built.
     */
    /** What handles silence right now, given the setting and whether a picture is being shown. */
    private val strategy: SilenceStrategy
        get() = SilenceStrategy.of(
            skipSilenceEnabled,
            // The selected TRACKS, not videoSize. Size is only populated once the decoder has
            // reported one, so asking too early says "no video" for a video — which the device
            // test caught choosing sample-removal for a clip, the one combination that desyncs.
            hasVideo = player?.currentTracks?.groups?.any {
                it.type == C.TRACK_TYPE_VIDEO && it.isSelected
            } == true,
        )

    /**
     * Points the right mechanism at the current content.
     *
     * Called whenever the setting OR the content changes, because a queue mixes both: the same
     * switch has to mean sample-removal for the podcast and a rate change for the video after it.
     */
    // A lambda property rather than a method: the class sits on detekt's function limit, and this
    // reads identically at both call sites.
    @UnstableApi
    private val applySilenceStrategy: () -> Unit = {
        val current = strategy
        silenceSkipper.setEnabled(current == SilenceStrategy.REMOVE_SAMPLES)
        if (current != SilenceStrategy.SPEED_UP) {
            racer.stopRacing()?.let { player?.setPlaybackSpeed(it) }
        }
        Diag.log("silence", "handling silence by $current")
    }

    @UnstableApi
    private fun applyVolumeBoost(boost: VolumeBoost) {
        booster.level = boost
    }

    /** Turns the user's intent off cleanly, restoring their speed if we were racing. */
    private fun applyEffectiveSkipSilence() {
        if (!skipSilenceEnabled && racer.racing) onSilenceChanged(false)
    }

    /**
     * The rate the user chose, told to us rather than guessed from the player.
     *
     * Sent on every play as well as on every change, so the rate survives moving to the next item
     * in the queue — Dewi, 2026-08-09: *"I want everything to be maintained going to the next
     * video"*. Applying it lands on the RACED rate when a silent stretch is in progress, so a
     * change made mid-silence neither drops out of the race nor gets lost when it ends.
     */
    // A lambda property rather than a method, for the same reason as [applySilenceStrategy]: the
    // class sits on detekt's function limit and this reads identically at its one call site.
    private val applyUserSpeed: (Float) -> Unit = { speed ->
        val apply = racer.userChose(speed)
        Diag.log("playback", "user speed -> $speed (playing at $apply, racing=${racer.racing})")
        player?.setPlaybackSpeed(apply)
    }

    /** Wires a Cast session in if Play Services + a receiver are reachable; otherwise stays fully local. */
    @UnstableApi
    private fun setUpCast(localPlayer: Player) {
        val castContext = runCatching { CastContext.getSharedInstance(this) }.getOrNull() ?: return
        val cast = CastPlayer(castContext)
        cast.setSessionAvailabilityListener(
            object : SessionAvailabilityListener {
                override fun onCastSessionAvailable() = switchTo(cast)
                override fun onCastSessionUnavailable() = switchTo(localPlayer)
            },
        )
        castPlayer = cast
    }

    /** Hands the current item + position to [target] and points the session at it (local ↔ cast). */
    @UnstableApi
    private fun switchTo(target: Player) {
        val previous = currentPlayer ?: return
        if (previous === target) return
        // On disconnect the CastPlayer has already torn its queue down, so its
        // currentMediaItem is null — fall back to the item/position cached when the
        // cast session started, so ending a cast resumes locally instead of dying.
        val item = previous.currentMediaItem ?: lastHandoffItem
        val position = previous.currentMediaItem?.let { previous.currentPosition } ?: lastHandoffPositionMs
        item?.let {
            target.setMediaItem(it, position)
            target.playWhenReady = previous.playWhenReady
            target.playbackParameters = previous.playbackParameters // carry playback speed across the handoff
            target.prepare()
            lastHandoffItem = it
            lastHandoffPositionMs = position
        }
        previous.stop()
        currentPlayer = target
        mediaSession?.player = target
        Diag.log("cast", "player -> ${if (target === castPlayer) "cast" else "local"}")
    }

    /** Tapping the media notification reopens the app (its launcher activity). */
    private fun openAppIntent(): PendingIntent? {
        val launch = packageManager.getLaunchIntentForPackage(packageName) ?: return null
        return PendingIntent.getActivity(
            this,
            0,
            launch,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = mediaSession

    @UnstableApi
    override fun onDestroy() {
        mediaSession?.release()
        player?.release()
        castPlayer?.setSessionAvailabilityListener(null)
        castPlayer?.release()
        mediaSession = null
        player = null
        castPlayer = null
        currentPlayer = null
        super.onDestroy()
    }

    private companion object {
        /** Enough to ride out a hiccup without a long wait before playback begins. */

        /**
         * How much of the NEXT item to hold. Dewi's figure, 2026-08-02: *"just 30 seconds of future
         * to be loaded right??"*. Flat in time, but eight times apart in bytes across the pillars —
         * ~0.5MB for a podcast, ~8MB for 1080p video — which is why the app only ever nominates
         * something on Wi-Fi.
         */
        const val MICROS_PER_MS = 1_000L
        const val URL_CHARS = 80

        // Podcast-style transport: small hop back to re-hear, bigger hop forward.
        const val SEEK_BACK_MS = 10_000L
        const val SEEK_FORWARD_MS = 30_000L

        /** Silence transitions between logged lines, so the trail keeps room for stalls. */
        const val SILENCE_LOG_EVERY = 50L
    }
}

/** Custom session command to toggle silence-skipping; the bool rides in [EXTRA_SKIP_SILENCE_ENABLED]. */
internal const val ACTION_SKIP_SILENCE: String = "com.dewijones92.totum.SKIP_SILENCE"

/**
 * Nominates the item to preload next, with its URL.
 *
 * A command rather than a shared object because only the SERVICE owns `MediaSource`s — a
 * `MediaController` cannot be handed one — so the app can name what is coming but never build it.
 */
internal const val ACTION_PRELOAD_NEXT: String = "com.dewijones92.totum.PRELOAD_NEXT"
internal const val EXTRA_PRELOAD_URI: String = "uri"

/** The item a nomination is FOR; what the preloader releases on. See [NextItemPreloader]. */
internal const val EXTRA_PRELOAD_ITEM_ID: String = "item_id"
internal const val ACTION_VOLUME_BOOST: String = "com.dewijones92.totum.VOLUME_BOOST"
internal const val EXTRA_VOLUME_BOOST_LEVEL: String = "boost_level"

/**
 * The rate the user chose. Told to the service rather than read off the player, because the
 * player's rate is also whatever a silent stretch is currently racing at.
 */
internal const val ACTION_USER_SPEED: String = "com.dewijones92.totum.USER_SPEED"
internal const val EXTRA_USER_SPEED: String = "user_speed"
internal const val EXTRA_SKIP_SILENCE_ENABLED: String = "enabled"

/**
 * Wraps the default source factory: when a [MediaItem] carries a separate audio
 * URL (higher-than-muxed video qualities stream video-only + audio-only), the
 * two are merged into one [MergingMediaSource] so they play in sync. Everything
 * else — podcasts, muxed streams, local files — passes straight through.
 */
@UnstableApi
private class MergingAudioVideoFactory(
    private val default: DefaultMediaSourceFactory,
) : MediaSource.Factory {

    override fun getSupportedTypes(): IntArray = default.supportedTypes

    override fun setDrmSessionManagerProvider(provider: DrmSessionManagerProvider): MediaSource.Factory =
        apply { default.setDrmSessionManagerProvider(provider) }

    override fun setLoadErrorHandlingPolicy(policy: LoadErrorHandlingPolicy): MediaSource.Factory =
        apply { default.setLoadErrorHandlingPolicy(policy) }

    override fun createMediaSource(mediaItem: MediaItem): MediaSource {
        val audioUrl = mediaItem.requestMetadata.extras?.getString(EXTRA_AUDIO_URL)
        val video = default.createMediaSource(mediaItem)
        if (audioUrl.isNullOrEmpty()) return video
        val audio = default.createMediaSource(MediaItem.fromUri(audioUrl))
        return MergingMediaSource(video, audio)
    }
}

/** Extras key on a [MediaItem]'s request metadata carrying the separate audio-track URL. */
internal const val EXTRA_AUDIO_URL: String = "com.dewijones92.totum.AUDIO_URL"
