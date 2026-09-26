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
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink
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

    /** The user's skip-silences intent. Applies to both pillars now. */
    @Volatile
    private var skipSilenceEnabled = false

    @UnstableApi
    private val silenceCutter = SilenceCuttingAudioProcessor()

    /**
     * The volume boost, in the chain rather than on the audio session.
     *
     * Replaces the platform `LoudnessEnhancer`, which was capped, could not compress, and had to be
     * rebuilt on every audio-session change — see [BoostingAudioProcessor].
     */
    @UnstableApi
    private val booster = BoostingAudioProcessor()

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
                val sink = DefaultAudioSink.Builder(context)
                    .setEnableFloatOutput(enableFloatOutput)
                    .setEnableAudioTrackPlaybackParams(enableAudioTrackPlaybackParams)
                    .setAudioProcessorChain(
                        SilenceCuttingAudioProcessorChain(silenceCutter, after = arrayOf(booster)),
                    )
                    .setAudioTrackBufferSizeProvider(SkipSilenceOutputBuffer { skipSilenceEnabled })
                    .build()
                return HeardSilenceAudioSink(sink, silenceCutter)
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
        currentPlayer = player
        player.addListener(
            object : Player.Listener {
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
            DefaultMediaSourceFactory(this).setLoadErrorHandlingPolicy(
                DoNotRetryWhatSabrHasGivenUpOn(),
            ).setDataSourceFactory(
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
                applyUserSpeed(args.getFloat(EXTRA_USER_SPEED, NORMAL_SPEED))
                return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
            }
            if (customCommand.customAction == ACTION_SKIP_SILENCE) {
                val enabled = args.getBoolean(EXTRA_SKIP_SILENCE_ENABLED)
                skipSilenceEnabled = enabled
                player?.skipSilenceEnabled = enabled
                Diag.log("playback", "skip-silence -> $enabled (player now ${player?.skipSilenceEnabled})")
                return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
            }
            return super.onCustomCommand(session, controller, customCommand, args)
        }
    }

    @UnstableApi
    private fun applyVolumeBoost(boost: VolumeBoost) {
        booster.level = boost
    }

    /**
     * The rate the user chose, told to us rather than guessed from the player.
     *
     * Sent on every play as well as on every change, so the rate survives moving to the next item
     * in the queue — Dewi, 2026-08-09: *"I want everything to be maintained going to the next
     * video"*.
     */
    // A lambda property rather than a method: the class sits on detekt's function limit and this
    // reads identically at its one call site.
    private val applyUserSpeed: (Float) -> Unit = { speed ->
        Diag.log("playback", "user speed -> $speed (skip-silence=$skipSilenceEnabled)")
        player?.setPlaybackSpeed(speed)
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

        const val NORMAL_SPEED = 1f
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
 * The rate the user chose. Told to the service rather than read off the player.
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
