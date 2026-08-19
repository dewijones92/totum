package com.dewijones92.totum.playback.fake

import androidx.media3.common.Player
import com.dewijones92.totum.common.HttpUrl
import com.dewijones92.totum.common.SubtitleTrack
import com.dewijones92.totum.domain.MediaItem
import com.dewijones92.totum.domain.MediaItemId
import com.dewijones92.totum.domain.MediaKind
import com.dewijones92.totum.domain.SkipSegment
import com.dewijones92.totum.playback.PlaybackController
import com.dewijones92.totum.playback.PlaybackEvent
import com.dewijones92.totum.playback.PlaybackState
import com.dewijones92.totum.playback.StreamFailure
import com.dewijones92.totum.playback.VolumeBoost
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.update

/** In-memory [PlaybackController] for tests and Compose previews. */
// The count is PlaybackController's own surface plus a few test hooks; the real
// implementation carries the same suppression for the same reason.
@Suppress("TooManyFunctions")
public class FakePlaybackController : PlaybackController {

    /** Every play() in order — lets a test assert how MANY times something was played. */
    public val played: MutableList<String> = mutableListOf()

    private val _state = MutableStateFlow<PlaybackState?>(null)
    override val state: StateFlow<PlaybackState?> = _state

    private val _streamFailures = MutableSharedFlow<StreamFailure>(extraBufferCapacity = 1)
    override val streamFailures: Flow<StreamFailure> = _streamFailures.asSharedFlow()

    private val _events = MutableSharedFlow<PlaybackEvent>(extraBufferCapacity = EVENT_BUFFER)
    override val events: Flow<PlaybackEvent> = _events.asSharedFlow()

    /** Lets a test drive the expired-stream path without a player or a network. */
    /**
     * Ends whatever is playing, so a test can exercise end-of-item behaviour.
     *
     * Needed because auto-advance is now driven purely by [PlaybackState.hasEnded]: without a
     * way to reach that state there is no way to test the thing at all, and the shorts reel's
     * advance is only reachable through it.
     */
    /**
     * Publishes an arbitrary state, so a test can drive a consumer of [state] directly.
     *
     * Needed for anything that reacts to the state stream rather than to a play() call —
     * the YouTube watch-history sync, for one, which has to be exercised across pillars and
     * with and without a video track.
     */
    public fun emitState(state: PlaybackState?) {
        _state.value = state
    }

    public fun endCurrent() {
        // Both, because the real controller does both: the state is how things ARE, the event is
        // what happened. A fake that emitted only one would let a consumer of the other pass.
        _state.value?.let { current ->
            _events.tryEmit(
                PlaybackEvent.Ended(current.itemId, current.positionMs, current.durationMs),
            )
        }
        _state.update { it?.copy(hasEnded = true, isPlaying = false) }
    }

    /**
     * Suspending, so the failure is genuinely delivered before the test looks at the result.
     *
     * It used to `tryEmit`, which put the value in a one-slot buffer that the collector never
     * drained under a test dispatcher: a test could drive four failures, see no complaint, and
     * conclude recovery had ignored them — when nothing had ever reached it. An empty capture is
     * not evidence of an empty world, and a fake that drops the signal under test is the worst
     * place to learn that.
     */
    public suspend fun failStream(failure: StreamFailure) {
        // A failed stream is a STOPPED player, which the real controller reaches by going idle.
        // Leaving isPlaying true here let a "has it recovered on its own?" check answer yes for a
        // stream that had just died — the fake asserting the opposite of what it was modelling.
        _state.update { it?.copy(isPlaying = false, isBuffering = false) }
        _streamFailures.emit(failure)
    }

    /** No real player in the fake, so previews/tests show the audio layout. */
    override val player: Player? = null

    /** Segments handed to the most recent [play] call, for assertions. */
    public var lastSkipSegments: List<SkipSegment> = emptyList()
        private set

    /** localPath handed to the most recent [play] call, for assertions. */
    public var lastLocalPath: String? = null

    /**
     * The item as played, URL included.
     *
     * The id alone cannot answer "which STREAM did it choose", which is the whole question when
     * Listen mode swaps a torrent's video URL for its audio-only one.
     */
    public var lastItem: MediaItem? = null
        private set

    /** audioUrl handed to the most recent [play] call, for assertions. */
    public var lastAudioUrl: HttpUrl? = null

    /** Where playback was asked to START — how a resume is told apart from a restart. */
    public var lastStartPositionMs: Long = 0
        private set

    override fun play(
        item: MediaItem,
        kind: MediaKind,
        skipSegments: List<SkipSegment>,
        localPath: String?,
        audioUrl: HttpUrl?,
        subtitles: List<SubtitleTrack>,
        startPositionMs: Long,
    ) {
        played += item.id.value
        lastItem = item
        lastSkipSegments = skipSegments
        lastLocalPath = localPath
        lastAudioUrl = audioUrl
        lastStartPositionMs = startPositionMs
        _state.value = PlaybackState(
            itemId = item.id,
            title = item.title,
            artist = item.author,
            artworkUrl = item.thumbnailUrl?.value,
            description = item.description,
            kind = kind,
            isPlaying = true,
            // A played item is INTENDED to play. Without this the fake modelled "moving but not meant
            // to be", which is a state the real player never reports on a fresh play, and it made the
            // first toggle after play() read as "start" rather than "stop".
            wantsToPlay = true,
            positionMs = 0,
            durationMs = item.duration?.inWholeMilliseconds,
            speed = 1.0f,
            skipSegments = skipSegments,
            chapters = item.chapters,
            subtitles = subtitles,
            subtitleLanguage = subtitleLanguage,
        )
    }

    override fun setSubtitleLanguage(languageCode: String?) {
        subtitleLanguage = languageCode
        _state.value = _state.value?.copy(subtitleLanguage = languageCode)
    }

    private var subtitleLanguage: String? = null

    /**
     * Toggles INTENT, like the real controller — not motion.
     *
     * It flipped `isPlaying`, which is a different thing while BUFFERING: a stalling stream reports
     * `isPlaying = false` with `wantsToPlay = true`, so a fake that flips motion would model a tap as
     * "start playing" when the real player pauses. That is the very bug this models (the real
     * `togglePlayPause` branched on `isPlaying`, so pause was inert on a spinner), and a fake that gets
     * it wrong lets a test pass on either behaviour.
     *
     * `isPlaying` follows intent EXCEPT while buffering, where the player is trying and failing.
     */
    override fun togglePlayPause() {
        _state.update { state ->
            state?.let {
                val wants = !it.wantsToPlay
                it.copy(wantsToPlay = wants, isPlaying = wants && !it.isBuffering)
            }
        }
    }

    override fun seekTo(positionMs: Long) {
        _state.update { it?.copy(positionMs = positionMs.coerceAtLeast(0)) }
    }

    override fun seekBackward() {
        _state.update { it?.copy(positionMs = (it.positionMs - SEEK_BACK_MS).coerceAtLeast(0)) }
    }

    override fun seekForward() {
        _state.update { it?.copy(positionMs = it.positionMs + SEEK_FORWARD_MS) }
    }

    override fun setSpeed(speed: Float) {
        _state.update { it?.copy(speed = speed) }
    }

    /** What was nominated for preloading, so a test can assert data was (or was not) spent. */
    public var preloaded: MutableList<HttpUrl> = mutableListOf()

    /** Which item each nomination was for, which is what the real preloader keys on. */
    public var preloadedFor: MutableList<MediaItemId> = mutableListOf()

    override fun preloadNext(itemId: MediaItemId, url: HttpUrl) {
        preloaded += url
        preloadedFor += itemId
    }

    override fun setVolumeBoost(boost: VolumeBoost) {
        _state.update { it?.copy(volumeBoost = boost) }
    }

    override fun setSkipSilence(enabled: Boolean) {
        _state.update { it?.copy(skipSilence = enabled) }
    }

    private companion object {
        const val SEEK_BACK_MS = 10_000L
        const val SEEK_FORWARD_MS = 30_000L
        const val EVENT_BUFFER = 8
    }
}
