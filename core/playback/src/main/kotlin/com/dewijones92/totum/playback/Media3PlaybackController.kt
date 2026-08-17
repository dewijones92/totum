package com.dewijones92.totum.playback

import android.content.ComponentName
import android.content.Context
import android.os.Bundle
import android.os.Debug
import androidx.annotation.OptIn
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import androidx.core.os.bundleOf
import androidx.media3.common.C
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.HttpDataSource
import androidx.media3.session.MediaController
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionToken
import com.dewijones92.totum.common.Diag
import com.dewijones92.totum.common.HttpUrl
import com.dewijones92.totum.common.SubtitleTrack
import com.dewijones92.totum.common.Vitals
import com.dewijones92.totum.domain.Chapter
import com.dewijones92.totum.domain.MediaItem
import com.dewijones92.totum.domain.MediaItemId
import com.dewijones92.totum.domain.MediaKind
import com.dewijones92.totum.domain.SkipSegment
import com.dewijones92.totum.domain.SourceId
import com.dewijones92.totum.domain.skipTargetFor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File
import java.io.IOException
import java.time.Instant
import kotlin.time.Duration.Companion.milliseconds
import androidx.media3.common.MediaItem as Media3MediaItem

/**
 * [PlaybackController] backed by a [MediaController] connected to
 * [PlaybackService]. Commands issued before the async connection completes
 * are queued and replayed on connect.
 */
// The one adapter binding the app's PlaybackController seam to Media3; its
// method count is the interface surface plus a few small private helpers, and
// collapsing them to satisfy the counter would only duplicate logic.
@Suppress("TooManyFunctions")
public class Media3PlaybackController(
    context: Context,
    private val scope: CoroutineScope,
    private val progressStore: PlaybackProgressStore = NoOpPlaybackProgressStore,
    private val speedStore: PlaybackSpeedStore = NoOpPlaybackSpeedStore,
    private val boostStore: VolumeBoostStore = NoOpVolumeBoostStore,
    private val onPlay: (MediaItem, MediaKind) -> Unit = { _, _ -> },
) : PlaybackController {

    private val _state = MutableStateFlow<PlaybackState?>(null)

    // extraBufferCapacity so an emit from the player's main-thread callback never suspends.
    private val _streamFailures = MutableSharedFlow<StreamFailure>(extraBufferCapacity = 1)

    // Buffered, because an end is emitted from the player's main-thread callback and must never
    // suspend it — and REPLAY of zero, because an event is news: a consumer subscribing later
    // must not be told about an end that happened before it was listening, which is precisely
    // the "already ended on connect" case the old state-watching code had to special-case.
    private val _events = MutableSharedFlow<PlaybackEvent>(extraBufferCapacity = EVENT_BUFFER)
    override val state: StateFlow<PlaybackState?> = _state

    override val streamFailures: Flow<StreamFailure> = _streamFailures.asSharedFlow()

    override val events: Flow<PlaybackEvent> = _events.asSharedFlow()

    private var controller: MediaController? = null
    override val player: Player? get() = controller
    private val pendingCommands = mutableListOf<(MediaController) -> Unit>()
    private var activeSkipSegments: List<SkipSegment> = emptyList()
    private var activeChapters: List<Chapter> = emptyList()

    // Held rather than read back from the player's text tracks: the tracks know a
    // language code but not the label or whether it's machine-generated, and those are
    // exactly what a menu needs to show.
    private var activeSubtitles: List<SubtitleTrack> = emptyList()

    /**
     * What the listing said — held here, like the segments and subtitles above and for the same
     * reason: it does not reliably cross the session.
     *
     * It rode in `MediaMetadata.extras` first, which worked locally and then failed **intermittently**
     * on CI: the view count came back null from the queue's play path in one run and not the next,
     * on a commit that touched only test files. Extras are not dependably carried by a
     * `MediaController`'s copy of an item, so a channel that appears to work is really a race — and
     * the video page would have dropped the numbers a moment after showing them, on a device, with
     * nothing to explain it. Every other per-item fact the UI needs is already held exactly this way.
     */
    private var activeViewsText: String? = null
    private var activePublishedText: String? = null
    private var activePublishedAt: Instant? = null
    private var subtitleLanguage: String? = null

    /** The rate the user chose. The player's own rate is not it — see [applyUserSpeed]. */
    private var userSpeed: Float = SilenceRacer.NORMAL
    private var currentSourceId: SourceId? = null
    private var playGeneration = 0
    private var skipSilence = false
    private var volumeBoost = VolumeBoost.OFF
    private var ticksSinceSave = 0
    private var ticksSinceMemory = 0

    init {
        val token = SessionToken(context, ComponentName(context, PlaybackService::class.java))
        val future = MediaController.Builder(context, token).buildAsync()
        future.addListener(
            {
                val connected = future.get()
                controller = connected
                // The stored rate, before anything is played. Without this the speed button reads
                // 1x on a cold start until the first play() lands — the app appearing to have
                // forgotten a setting it had not.
                scope.launch { userSpeed = speedStore.speed().coerceIn(MIN_SPEED, MAX_SPEED) }
                // Observation is a separate listener from state mapping, so a logging
                // change can never affect what the UI sees.
                connected.addListener(PlaybackDiagnostics(player = { controller }))
                connected.addListener(
                    object : Player.Listener {
                        /*
                         * Reaching the end IS "played" — the ground truth, rather than the
                         * position heuristic in the progress store, which needs a known
                         * duration and so could never mark a live or duration-less item
                         * played at all.
                         */
                        override fun onPlaybackStateChanged(playbackState: Int) {
                            if (playbackState != Player.STATE_ENDED) return
                            val id = connected.currentMediaItem?.mediaId ?: return
                            scope.launch { progressStore.setPlayed(MediaItemId(id), played = true) }
                            // The one place an end is turned into a fact. This callback fires on
                            // the TRANSITION into ENDED, so it is already the edge every watcher
                            // used to reconstruct for itself — including the second end of an
                            // item that has ended before, which is the case they got wrong.
                            _events.tryEmit(
                                PlaybackEvent.Ended(
                                    itemId = MediaItemId(id),
                                    atMs = connected.currentPosition,
                                    durationMs = connected.duration.takeIf { it > 0 },
                                ),
                            )
                        }

                        @OptIn(markerClass = [UnstableApi::class])
                        override fun onPlayerError(error: PlaybackException) {
                            val reason = error.recoverableReason() ?: run {
                                // Said out loud: an unrecoverable error used to leave no
                                // trace here at all, so "playback stopped and nothing in
                                // the trail explains it" was a real state.
                                Diag.warn("playback", "player error with no recovery for it", error)
                                return
                            }
                            val id = connected.currentMediaItem?.mediaId ?: return
                            val at = connected.currentPosition
                            Diag.log("playback", "stream failed at ${at}ms — $reason")
                            _streamFailures.tryEmit(StreamFailure(MediaItemId(id), at, reason))
                        }

                        override fun onEvents(player: Player, events: Player.Events) {
                            if (events.containsAny(
                                    Player.EVENT_VIDEO_SIZE_CHANGED,
                                    Player.EVENT_TRACKS_CHANGED,
                                )
                            ) {
                                Diag.log(
                                    "playback",
                                    "video size=${player.videoSize.width}x${player.videoSize.height} " +
                                        "hasVideo=${player.currentTracks.groups.any {
                                            it.type == C.TRACK_TYPE_VIDEO
                                        }}",
                                )
                            }
                            _state.value = connected.currentPlaybackState()
                        }
                    },
                )
                pendingCommands.forEach { it(connected) }
                pendingCommands.clear()
                startPositionTicker(connected)
            },
            ContextCompat.getMainExecutor(context),
        )
    }

    override fun play(
        item: MediaItem,
        kind: MediaKind,
        skipSegments: List<SkipSegment>,
        localPath: String?,
        audioUrl: HttpUrl?,
        subtitles: List<SubtitleTrack>,
        startPositionMs: Long,
    ) {
        val uri = localPath?.let { File(it).toURI().toString() }
            ?: requireNotNull(item.mediaUrl) { "MediaItem ${item.id.value} has no mediaUrl" }.value
        // WHAT is actually being played, which nothing recorded before. The queue stores the
        // stable watch URL, so a report could say a video was played and never say from
        // where — and an HLS URL with no HLS extractor bundled died instantly with only an
        // ExoPlayer ClassNotFoundException in logcat to show for it (0.1.230, found on the
        // emulator 2026-07-31). Signature and expiry parameters are dropped: they are long,
        // secret, and never the answer.
        val mergedAudio = audioUrl?.let { " + audio ${it.value.forLog()}" }.orEmpty()
        Diag.log("playback", "play ${item.id.value} from ${uri.forLog()}$mergedAudio")
        onPlay(item, kind)
        // Each play() claims a generation; only the latest one commits its state and
        // media item. Guards against two quick play() calls (double-tap, queue
        // auto-advance) whose async loads resume out of order and would otherwise
        // leave the player on one item with another's source/segments/chapters.
        val generation = ++playGeneration
        // A separate audio track (higher-than-muxed qualities) rides along in
        // the request metadata; the service merges it with the video-only URI.
        val requestMetadata = Media3MediaItem.RequestMetadata.Builder()
            .setExtras(audioUrl?.let { bundleOf(EXTRA_AUDIO_URL to it.value) })
            .build()
        val media3Item = Media3MediaItem.Builder()
            .setMediaId(item.id.value)
            .setUri(uri)
            .setRequestMetadata(requestMetadata)
            // Side-loaded text tracks. DefaultMediaSourceFactory turns these into text
            // sources itself, and the service's audio-merging wrapper delegates to it, so
            // captions survive the higher-quality video+audio merge rather than being
            // dropped by it.
            .setSubtitleConfigurations(subtitles.map { it.toSubtitleConfiguration() })
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle(item.title)
                    .setArtist(item.author)
                    .setDescription(item.description)
                    .setArtworkUri(item.thumbnailUrl?.value?.let(android.net.Uri::parse))
                    // Round-trips the pillar through the session so the UI can label it.
                    .setMediaType(
                        when (kind) {
                            MediaKind.VIDEO -> MediaMetadata.MEDIA_TYPE_VIDEO
                            MediaKind.PODCAST -> MediaMetadata.MEDIA_TYPE_PODCAST_EPISODE
                        },
                    )
                    .build(),
            )
            .build()
        // Resume where this item was left (both pillars). Fetched first so we
        // can hand the start position straight to the player — no jump from 0.
        scope.launch {
            // An explicit position wins: a re-resolved stream knows exactly where the dead
            // one stopped, which is finer-grained than the periodically-saved progress.
            val resumeMs = startPositionMs.takeIf { it > 0 }
                ?: progressStore.resumePositionMs(item.id) ?: 0L
            val speed = speedStore.speed()
            val boost = boostStore.boost()
            withController { controller ->
                // A newer play() superseded this one while we were loading — drop it,
                // so its media item and state never clobber the current item.
                if (generation != playGeneration) return@withController
                activeSkipSegments = skipSegments
                activeChapters = item.chapters
                activeSubtitles = subtitles
                activeViewsText = item.viewsText
                activePublishedText = item.publishedText
                activePublishedAt = item.publishedAt
                currentSourceId = item.sourceId
                ticksSinceSave = 0
                controller.setMediaItem(media3Item, resumeMs)
                // Re-applied on EVERY item, and told to the service too: the rate the user chose
                // is a promise that has to survive the queue moving on (Dewi, 2026-08-09).
                applyUserSpeed(controller, speed)
                applySubtitleLanguage(controller)
                if (boost != volumeBoost) setVolumeBoost(boost)
                controller.prepare()
                controller.play()
            }
        }
    }

    override fun togglePlayPause() {
        withController {
            if (it.isPlaying) {
                it.pause()
                saveProgress(it) // capture where we paused straight away
            } else {
                it.play()
            }
        }
    }

    override fun seekTo(positionMs: Long) {
        withController { controller ->
            val max = controller.duration.takeIf { it > 0 } ?: Long.MAX_VALUE
            controller.seekTo(positionMs.coerceIn(0, max))
            _state.value = controller.currentPlaybackState()
        }
    }

    override fun seekBackward() {
        withController {
            it.seekBack()
            _state.value = it.currentPlaybackState()
        }
    }

    override fun seekForward() {
        withController {
            it.seekForward()
            _state.value = it.currentPlaybackState()
        }
    }

    override fun setSpeed(speed: Float) {
        val clamped = speed.coerceIn(MIN_SPEED, MAX_SPEED)
        withController {
            applyUserSpeed(it, clamped)
            _state.value = it.currentPlaybackState()
        }
        scope.launch { speedStore.save(clamped) }
    }

    /**
     * Sets the rate AND tells the service it was the user's.
     *
     * Both halves matter. The player's own rate is not a reliable record of what was asked for —
     * skip-silence races through dead air by raising it — so the service used to guess, and
     * ignored the guess while racing. A rate chosen during a silent stretch was therefore dropped
     * on the floor, and speech puts silence between sentences every few seconds.
     */
    private fun applyUserSpeed(controller: MediaController, speed: Float) {
        userSpeed = speed
        controller.setPlaybackSpeed(speed)
        controller.sendCustomCommand(
            SessionCommand(ACTION_USER_SPEED, Bundle.EMPTY),
            bundleOf(EXTRA_USER_SPEED to speed),
        )
    }

    override fun preloadNext(itemId: MediaItemId, url: HttpUrl) {
        withController {
            it.sendCustomCommand(
                SessionCommand(ACTION_PRELOAD_NEXT, Bundle.EMPTY),
                bundleOf(
                    EXTRA_PRELOAD_URI to url.value,
                    // The identity the service releases on. See PlaybackController.preloadNext.
                    EXTRA_PRELOAD_ITEM_ID to itemId.value,
                ),
            )
        }
    }

    override fun setVolumeBoost(boost: VolumeBoost) {
        volumeBoost = boost
        withController {
            it.sendCustomCommand(
                SessionCommand(ACTION_VOLUME_BOOST, Bundle.EMPTY),
                bundleOf(EXTRA_VOLUME_BOOST_LEVEL to boost.name),
            )
            _state.value = it.currentPlaybackState()
        }
        scope.launch { boostStore.save(boost) }
    }

    override fun setSubtitleLanguage(languageCode: String?) {
        subtitleLanguage = languageCode
        withController { controller ->
            applySubtitleLanguage(controller)
            _state.value = controller.currentPlaybackState()
        }
        Diag.log("subtitles", "language -> ${languageCode ?: "off"}")
    }

    /**
     * Disabling the whole track type is what actually turns captions off: leaving it enabled with
     * no preferred language lets the player fall back to a default track, so "off" would quietly
     * still show something.
     *
     * Re-applied on every item as well as on every change. Captions are a sitting-level choice,
     * not a per-video one — Dewi, 2026-08-09: *"I want everything to be maintained going to the
     * next video"* — and re-asserting it costs nothing, where relying on the player to carry
     * selection parameters across a `setMediaItem` is relying on something nobody wrote down.
     */
    private fun applySubtitleLanguage(controller: MediaController) {
        controller.trackSelectionParameters = controller.trackSelectionParameters
            .buildUpon()
            .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, subtitleLanguage == null)
            .setPreferredTextLanguage(subtitleLanguage)
            .build()
    }

    override fun setSkipSilence(enabled: Boolean) {
        skipSilence = enabled
        withController {
            it.sendCustomCommand(
                SessionCommand(ACTION_SKIP_SILENCE, Bundle.EMPTY),
                bundleOf(EXTRA_SKIP_SILENCE_ENABLED to enabled),
            )
            _state.value = it.currentPlaybackState()
        }
    }

    private fun withController(command: (MediaController) -> Unit) {
        controller?.let(command) ?: pendingCommands.add(command)
    }

    private fun startPositionTicker(controller: MediaController) {
        scope.launch {
            while (isActive) {
                if (controller.isPlaying) {
                    applySkipSegments(controller)
                    _state.value = controller.currentPlaybackState()
                    if (++ticksSinceSave >= TICKS_PER_SAVE) {
                        ticksSinceSave = 0
                        saveProgress(controller)
                    }
                    if (++ticksSinceMemory >= TICKS_PER_MEMORY_LOG) {
                        ticksSinceMemory = 0
                        logMemory()
                    }
                }
                delay(POSITION_TICK_MS)
            }
        }
    }

    /**
     * One line of heap, once a minute while something is playing.
     *
     * Because 0.1.346 died of OutOfMemoryError and the report could not say what had been
     * climbing towards it: a Java OOM stack names whoever allocated last (a 16-byte allocation
     * in Media3's frame-release code), never the hog. A sampled trail can show the shape, and
     * sampling is the only way to see it — a watcher that only reports on change never fires
     * while the number creeps.
     *
     * Sampled on the clock rather than per tick, and only while playing, so it costs one line a
     * minute in a bounded report buffer.
     */
    private fun logMemory() {
        val runtime = Runtime.getRuntime()
        val usedMb = (runtime.totalMemory() - runtime.freeMemory()) / BYTES_PER_MB
        val maxMb = runtime.maxMemory() / BYTES_PER_MB
        val nativeMb = Debug.getNativeHeapAllocatedSize() / BYTES_PER_MB
        Vitals.set("memory.heapUsedMb", usedMb.toString())
        Vitals.set("memory.heapMaxMb", maxMb.toString())
        Diag.log(
            "memory",
            "heap ${usedMb}MB of ${maxMb}MB, native ${nativeMb}MB " +
                "(buffered ${_state.value?.bufferedPositionMs?.minus(_state.value?.positionMs ?: 0)}ms ahead)",
        )
    }

    /** Persists the current item's position so it resumes there next time. */
    private fun saveProgress(controller: MediaController) {
        val id = controller.currentMediaItem?.mediaId ?: return
        val position = controller.currentPosition.coerceAtLeast(0)
        val duration = controller.duration.takeIf { it > 0 }
        scope.launch { progressStore.save(MediaItemId(id), position, duration) }
    }

    /** The one place segment-skipping happens, for every pillar. */
    private fun applySkipSegments(controller: MediaController) {
        val target = activeSkipSegments.skipTargetFor(controller.currentPosition.milliseconds) ?: return
        controller.seekTo(target.inWholeMilliseconds)
    }

    private fun MediaController.currentPlaybackState(): PlaybackState? {
        val current = currentMediaItem ?: return null
        return PlaybackState(
            itemId = MediaItemId(current.mediaId),
            title = current.mediaMetadata.title?.toString().orEmpty(),
            artist = current.mediaMetadata.artist?.toString(),
            artworkUrl = current.mediaMetadata.artworkUri?.toString(),
            viewsText = activeViewsText,
            publishedText = activePublishedText,
            publishedAt = activePublishedAt,
            description = current.mediaMetadata.description?.toString(),
            kind = if (current.mediaMetadata.mediaType == MediaMetadata.MEDIA_TYPE_PODCAST_EPISODE) {
                MediaKind.PODCAST
            } else {
                MediaKind.VIDEO
            },
            isPlaying = isPlaying,
            positionMs = currentPosition.coerceAtLeast(0),
            durationMs = duration.takeIf { it > 0 },
            // The rate the USER chose, not the player's current one: skip-silence races through
            // dead air at up to 8x, and reporting that made the speed button flick to "4x" during
            // every pause in speech — the app appearing to change a setting nobody touched.
            speed = userSpeed,
            hasVideo = currentTracks.groups.any { it.type == C.TRACK_TYPE_VIDEO },
            videoAspectRatio = videoSize.takeIf { it.width > 0 && it.height > 0 }
                ?.let { it.width * it.pixelWidthHeightRatio / it.height },
            hasEnded = playbackState == Player.STATE_ENDED,
            isBuffering = playbackState == Player.STATE_BUFFERING,
            bufferedPositionMs = bufferedPosition.coerceAtLeast(currentPosition).coerceAtLeast(0),
            skipSegments = activeSkipSegments,
            subtitles = activeSubtitles,
            subtitleLanguage = subtitleLanguage,
            skipSilence = skipSilence,
            volumeBoost = volumeBoost,
            chapters = activeChapters,
        )
    }

    private fun SubtitleTrack.toSubtitleConfiguration(): Media3MediaItem.SubtitleConfiguration =
        Media3MediaItem.SubtitleConfiguration.Builder(url.value.toUri())
            .setMimeType(format.mimeType)
            .setLanguage(languageCode)
            .setLabel(label)
            .build()

    private companion object {
        const val POSITION_TICK_MS = 500L

        /** A minute at [POSITION_TICK_MS], so the heap trail is one line a minute while playing. */
        const val TICKS_PER_MEMORY_LOG = 120
        const val BYTES_PER_MB = 1024L * 1024L
        const val MIN_SPEED = 0.5f
        const val MAX_SPEED = 3.0f

        /** Persist progress every ~5s of playback (10 ticks of 500ms). */
        const val TICKS_PER_SAVE = 10

        /** Room for a burst of ends without ever suspending the player's callback thread. */
        const val EVENT_BUFFER = 8
    }
}

/**
 * Whether a failure is the kind a freshly-resolved URL might fix, and which kind it is — null
 * when the status says nothing about the address.
 *
 * Matched on the cause chain rather than the top-level error code, because the code is not
 * reliable: the real report carried ERROR_CODE_IO_UNSPECIFIED even though the cause was a
 * plain 403.
 *
 * The URL is carried out of the exception because a 403 alone cannot say whether the lease ran
 * out or the stream is being refused, and those need opposite amounts of patience. See
 * [leaseVerdict], which holds the judgement in a form a JVM test can reach.
 */
@UnstableApi
internal fun PlaybackException.deadAddressReason(nowEpochSeconds: Long): StreamFailure.Reason? {
    var cause: Throwable? = this
    while (cause != null) {
        val http = cause as? HttpDataSource.InvalidResponseCodeException
        if (http != null && isExpiredStatus(http.responseCode)) {
            val url = http.dataSpec.uri.toString()
            val verdict = leaseVerdict(url, nowEpochSeconds)
            // The INPUTS, not only the verdict. "Expired" appeared 14 times in one report and there
            // was no way to tell from it that every one of those URLs had six hours left to run.
            //
            // The CLIENT is here because it is the open question a refusal raises and nothing could
            // answer it: 0.1.390's refused URLs were all `c=ANDROID_VR`, which is one of yt-dlp's
            // own defaults, and every refusal was on a range deep into the item (1689219ms of
            // 2260648ms, then 1800024ms). Whether that pairing is the cause is not something this
            // app can decide from here, so it is counted per client and left for the next report
            // to settle rather than guessed at with a flag.
            val client = streamClient(url)
            val lease = leaseSecondsLeft(url, nowEpochSeconds)?.let { "${it}s of its lease left" }
                ?: "no lease to read"
            Vitals.add("playback.refusedBy.$client")
            Diag.log(
                "playback",
                "HTTP ${http.responseCode} from client $client on a stream with $lease -> $verdict",
            )
            return verdict
        }
        cause = cause.cause
    }
    return null
}

/**
 * What, if anything, could still get this playing again — null when nothing could.
 *
 * The address is judged first because a 403 arrives as an [HttpDataSource] failure too, and a dead
 * or refused address deserves an answer of its own rather than a wait for a network that is
 * already there.
 */
@UnstableApi
internal fun PlaybackException.recoverableReason(
    nowEpochSeconds: Long = System.currentTimeMillis() / MILLIS_PER_SECOND,
): StreamFailure.Reason? = when (val address = deadAddressReason(nowEpochSeconds)) {
    null -> if (isUnreachable()) StreamFailure.Reason.Unreachable else null
    else -> address
}

private const val MILLIS_PER_SECOND = 1_000L

/** How long the URL's own lease has left, negative once it is past; null when it carries none. */
internal fun leaseSecondsLeft(url: String, nowEpochSeconds: Long): Long? =
    LEASE.find(url)?.groupValues?.get(1)?.toLongOrNull()?.minus(nowEpochSeconds)

/**
 * Which YouTube client signed this address, from the `c=` it carries — `none` when it carries no
 * such parameter, which is every URL that is not googlevideo's.
 *
 * Worth naming because a refusal is a fact about the *client*, not about the video: yt-dlp asks
 * several and hands back whichever offered the best format, so two plays of the same item can be
 * signed by different clients and behave differently. Nothing in a report could previously say
 * which, and the URL is truncated in the trail well before `c=`.
 */
internal fun streamClient(url: String): String =
    CLIENT.find(url)?.groupValues?.get(1) ?: "none"

private val CLIENT = Regex("""[?&]c=([A-Za-z0-9_]+)""")

/**
 * Whether a 403/410 means the lease ran out or the stream is being refused.
 *
 * Every googlevideo address carries the epoch second it dies at, in `expire`, so this is readable
 * rather than guessable — and it was being read backwards. Report 0.1.390: a 403 at 18:31Z on a
 * URL stamped `expire=1787013060`, which is 00:31Z the following morning. Nearly six hours of
 * lease left, called an expiry, and three re-resolves spent on it at 12–18 seconds each.
 *
 * Split out from the cause-chain walk above for the same reason [isExpiredStatus] is: building a
 * Media3 exception needs an `android.net.Uri`, which a JVM test cannot make, and this is the part
 * with the judgement in it. [StreamFailure.Reason.Expired] is the fallback throughout — a URL with
 * no lease to read (a podcast enclosure, the home torrent server) keeps the behaviour it has always
 * had, and one wasted retry is the cheaper of the two mistakes.
 */
internal fun leaseVerdict(url: String, nowEpochSeconds: Long): StreamFailure.Reason {
    val left = leaseSecondsLeft(url, nowEpochSeconds) ?: return StreamFailure.Reason.Expired
    return if (left <= 0) StreamFailure.Reason.Expired else StreamFailure.Reason.Rejected
}

/** Anchored on the parameter boundary, so `expires_in` is not mistaken for the lease. */
private val LEASE = Regex("""[?&]expire=(\d+)""")

/**
 * Whether the failure was the connection itself rather than the content.
 *
 * Matched on the cause chain for the same reason [looksExpired] is: the top-level code lies.
 * An [IOException] under a playback error means the bytes did not arrive — no route, DNS,
 * reset, timeout — and every one of those is fixed by the network coming back, so they get
 * one shared answer rather than a list of exception types that would inevitably miss one.
 * An [HttpDataSource.InvalidResponseCodeException] is excluded: the server answered, so this
 * is the content's problem, not the connection's.
 */
@UnstableApi
internal fun PlaybackException.isUnreachable(): Boolean {
    var cause: Throwable? = this
    while (cause != null) {
        if (cause is HttpDataSource.InvalidResponseCodeException) return false
        if (cause is IOException) return true
        cause = cause.cause
    }
    return false
}

/**
 * Which HTTP statuses mean "this address is dead, the content is not".
 *
 * Split out from the cause-chain walk above so the judgement is unit-testable: building a
 * Media3 [HttpDataSource.InvalidResponseCodeException] needs an `android.net.Uri`, which a
 * JVM test cannot make, and the codes are the part that would actually be wrong. 403 is an
 * expired signature and 410 a retired URL, so both earn a re-resolve. 404 means the content
 * itself is gone and 5xx would fail identically on a fresh URL, so neither does.
 */
internal fun isExpiredStatus(code: Int): Boolean = code == HTTP_FORBIDDEN || code == HTTP_GONE

private const val HTTP_FORBIDDEN = 403
private const val HTTP_GONE = 410

/**
 * A stream URL short enough to log and safe to keep.
 *
 * Host and path answer the questions that matter — which CDN, and whether this is a
 * progressive stream, an HLS manifest or a local file — while the query carries only a
 * signature and an expiry, which are secret, enormous, and never the reason something broke.
 */
internal fun String.forLog(): String = substringBefore('?').let {
    if (length > it.length) "$it?…" else it
}
