package com.dewijones92.totum.video

import com.dewijones92.totum.common.AudioTrackTag
import com.dewijones92.totum.common.Diag
import com.dewijones92.totum.common.HttpUrl
import com.dewijones92.totum.data.history.PlayHistoryStore
import com.dewijones92.totum.domain.MediaItem
import com.dewijones92.totum.domain.PlayHandle
import com.dewijones92.totum.domain.PlayableItem
import com.dewijones92.totum.domain.SourceId
import com.dewijones92.totum.domain.withStreamFrom
import com.dewijones92.totum.innertube.history.YouTubeWatchHistory
import com.dewijones92.totum.playback.PlaybackController
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import java.util.concurrent.atomic.AtomicLong

/**
 * The one place a video goes from "a watch URL" to "playing", and the owner of
 * the current video's selectable qualities. Streaming URLs expire, so a video
 * is resolved through the shared [VideoResolver] at play time; a downloaded
 * copy skips resolution entirely. Every entry point that plays a video — the
 * Videos feed, Search, a channel page — uses this, so the resolve-then-play
 * decision and quality switching live once.
 */
// The count is this class's legitimate surface: play / play-local / describe, the mode and quality
// and audio-track switches, and the two small queries the queue and the preloader ask it. Splitting
// it would put the "which stream plays" decision somewhere other than the class that plays it, which
// is the duplication `urlThatWouldPlay` exists to remove. The cap deliberately does not move into
// StreamChoices: that class documents itself as what the USER chose, not what the network allows.
@Suppress("TooManyFunctions")
class VideoPlaybackLauncher(
    private val resolver: VideoResolver,
    private val playback: PlaybackController,
    private val watchHistory: YouTubeWatchHistory,
    private val playHistory: PlayHistoryStore,
    /** Max video height to auto-pick for the current network (a cap); default: no cap. */
    private val preferredMaxHeight: () -> Int = { Int.MAX_VALUE },
    /**
     * Whether playback should be audio-only right now — the resolved playback mode
     * (Auto having already been turned into audio-or-video by whoever knows about the
     * network). Consulted in exactly one place, so the mode covers every entry point.
     */
    private val audioPreferred: () -> Boolean = { false },
    /**
     * The quality and audio track the user picked by hand, carried to the next video.
     *
     * Shared with the resolver, which is where the audio half has to be applied — see
     * [StreamChoices].
     */
    private val choices: StreamChoices = StreamChoices(),
) {
    /** The current video's quality options and which one is playing. */
    data class QualityState(
        val options: List<VideoQuality> = emptyList(),
        val selectedId: String? = null,
        /** True when an audio-only stream exists, so "Listen" mode is offered. */
        val canListen: Boolean = false,
        /** True while playing audio-only ("Listen"); the toggle then offers "Watch". */
        val listening: Boolean = false,
        /** Selectable audio tracks, best-first; empty when the video offers no choice. */
        val audioTracks: List<AudioTrackTag> = emptyList(),
        /** The track playing — null until one is chosen by hand, meaning "the preferred one". */
        val audioLanguage: String? = null,
    )

    private val _quality = MutableStateFlow(QualityState())
    val quality: StateFlow<QualityState> = _quality

    /** The last resolved video, kept so a quality switch replays without re-extracting. */
    private var current: VideoResolver.Resolved? = null

    /** Its watch URL, which is what the resolver is keyed by — needed to re-pick audio tracks. */
    private var currentWatchUrl: HttpUrl? = null

    /**
     * Which play request is the current one. Only the newest may actually start playback.
     *
     * An extraction takes 5–11 seconds on a real phone, and taps arrive during it. The resolver
     * already de-duplicates the *extraction* — a second caller joins the first rather than
     * running it again — but every joined caller then went on to play. Report 0.1.383: three
     * taps four seconds apart while an 11-second extraction ran, and when it landed the same
     * video was handed to the player **three times in 81ms**, with three `beginSession` calls to
     * YouTube. A tap on a *different* video during a resolve is the worse version of the same
     * thing: the older request would start playing over the newer one.
     *
     * Atomic because [beginPlay] is now called by the queue as well, from its own coroutine.
     */
    private val latestRequest = AtomicLong()

    /**
     * Claims the newest play, returning the token to hand back to [play].
     *
     * Public because the counter has to cover **every** route, not just this class's. A play that
     * reaches a downloaded file goes straight to the controller and never touches the launcher, so
     * while the counter was private a route to disk left an older streaming resolve believing it
     * was still wanted. Report 0.1.390: a twelve-second extraction landed ten seconds after the
     * same item had started playing from `/data/…/3138547848.media`, dropped the file, streamed a
     * URL that answered 403, and cost 41 seconds of buffering nobody could have escaped.
     */
    fun beginPlay(): Long = latestRequest.incrementAndGet()

    /** Drops any cached resolution for [watchUrl] — see [VideoResolver.forget]. */
    fun forgetResolved(watchUrl: HttpUrl) {
        resolver.forget(watchUrl)
    }

    /** Plays an already-downloaded file — no re-resolution, and no quality choice (it's one merged file). */
    fun playLocal(item: MediaItem, localPath: String) {
        beginPlay()
        current = null
        currentWatchUrl = null
        _quality.value = QualityState()
        playback.play(item, localPath = localPath)
    }

    /**
     * Resolves [watchUrl] to its display metadata without playing — so a bare link can be
     * put in the queue with a real title. Costs one extra resolve, which is why only the
     * share-target uses it; every other caller already holds a [MediaItem].
     */
    suspend fun describe(watchUrl: HttpUrl, sourceId: SourceId): MediaItem? =
        resolver.resolve(watchUrl, sourceId, asked = "describe")?.item?.copy(mediaUrl = watchUrl)

    /**
     * Resolves [watchUrl] to a playable stream (with its skip segments and
     * quality ladder) and plays the default quality. Returns false when the
     * video can't be resolved (private, removed, geo-blocked, …).
     *
     * [request] is the caller's claim on being the newest play, from [beginPlay]. Pass it when
     * the caller may reach the player by some *other* route as well — the queue does — so that
     * route also supersedes a resolve still in flight here. Defaulted for callers that cannot.
     */
    suspend fun play(
        listing: MediaItem,
        watchUrl: HttpUrl,
        startPositionMs: Long = 0,
        request: Long = beginPlay(),
    ): Boolean = play(listing, watchUrl, startPositionMs, request) {
        // `asked` names WHO wanted this, because a report showed one video extracted four
        // times in thirty seconds and the log could not say by whom.
        resolver.resolve(watchUrl, listing.sourceId, asked = "play")
    }

    /**
     * Plays [listing] using SABR, for when its ordinary stream URLs have been refused.
     *
     * The same play as any other — same claim on the newest request, same history, same watch-session
     * bookkeeping — differing only in HOW it resolves. Sharing one body rather than growing a second
     * play path is the point: a rescue that skipped the bookkeeping would leave the picture working and
     * the history, tracking and quality state quietly wrong.
     */
    suspend fun playAsRescue(listing: MediaItem, watchUrl: HttpUrl, startPositionMs: Long = 0): Boolean =
        play(listing, watchUrl, startPositionMs, beginPlay()) {
            resolver.resolveAsRescue(watchUrl, listing.sourceId)
        }

    private suspend fun play(
        listing: MediaItem,
        watchUrl: HttpUrl,
        startPositionMs: Long,
        request: Long,
        resolve: suspend () -> VideoResolver.Resolved?,
    ): Boolean {
        val extracted = resolve() ?: return false
        if (request != latestRequest.get()) {
            // True rather than false: something IS playing, just not this. Returning false makes
            // an auto-advance treat the item as unplayable and skip to the NEXT one, which would
            // have it fighting whatever the user just chose.
            Diag.log(
                "playback",
                "dropping the play of ${listing.id.value} — ${latestRequest.get() - request} newer " +
                    "request(s) arrived while it resolved",
            )
            return true
        }
        // The listing's facts kept, ONCE, here — so every path below (a quality switch, Listen
        // mode, a stall replay) carries the view count and publication date without knowing it
        // has to. A resolution has nothing to say about either, and all three resolver paths
        // build a fresh item with `publishedAt = null`, so this is where they would otherwise be
        // lost for good. See MediaItem.withStreamFrom.
        val resolved = extracted.copy(item = listing.withStreamFrom(extracted.item))
        current = resolved
        currentWatchUrl = watchUrl
        // Record the play against the stable watch URL (streaming URLs expire), so
        // a history replay re-resolves through this same launcher.
        playHistory.record(
            PlayableItem(resolved.item.copy(mediaUrl = watchUrl), PlayHandle.Video(watchUrl)),
        )
        // Fetches this video's account-bearing tracking URLs so progress can sync to
        // YouTube. Deliberately NOT the ones the extractor returned: those come from an
        // unauthenticated session and credit nobody (see HttpYouTubeWatchHistory).
        watchHistory.beginSession(resolved.item.id.value)
        // One place decides audio vs video, so the mode holds no matter which screen
        // started playback. A one-off "watch this" is expressed by [watch].
        if (audioPreferred() && resolved.audioOnlyUrl != null) listen() else playVideoQuality(resolved, startPositionMs)
        return true
    }

    /**
     * True when the item is ONE stream carrying everything — a torrent file, a podcast
     * enclosure — so there is no audio track to switch to and no quality ladder to move within.
     * Switching modes on one of these can only reload what is already playing.
     */
    private val VideoResolver.Resolved.isOneStream: Boolean
        get() = audioOnlyUrl == null && qualities.isEmpty()

    /**
     * The height you last picked by hand, within the network's cap; the best the cap allows if you
     * have not picked one. ONE call site for the ladder, so [urlThatWouldPlay] cannot drift from
     * what [playVideoQuality] does.
     */
    private fun chosenQuality(resolved: VideoResolver.Resolved): VideoQuality? =
        choices.qualityFrom(resolved.qualities, preferredMaxHeight())

    /**
     * The stream [resolved] would be played from if it were played right now.
     *
     * Exists for the preloader, which has to hold the bytes the player will ask for and was
     * guessing: it nominated `resolved.item.mediaUrl` while this class plays the ladder's pick, so
     * on any video with a ladder the two disagreed and the preload was thrown away. Report 0.1.390
     * counted `preloadsWasted = 12` out of twelve — around 30 seconds of 1080p fetched and dropped
     * per item — and 0.1.359 had already logged the mismatch as "itag 18 held, itag 399 played"
     * without it being read as a bug.
     *
     * A `null` means the resolution produced nothing playable, so there is nothing to hold.
     */
    fun urlThatWouldPlay(resolved: VideoResolver.Resolved): HttpUrl? = when {
        // The cheap stream when listening: an audio-only track is a fraction of the video's size,
        // and holding the picture for a mode that will not show it spends the data twice over.
        audioPreferred() && resolved.audioOnlyUrl != null -> resolved.audioOnlyUrl
        else -> chosenQuality(resolved)?.videoUrl ?: resolved.item.mediaUrl
    }

    /** Plays [resolved] as video at the best allowed quality — the shared play/"Watch" path. */
    private fun playVideoQuality(resolved: VideoResolver.Resolved, startPositionMs: Long = 0) {
        // Every hand-off to the player claims the newest play, so "Watch" and a quality change
        // supersede a resolve still in flight just as a fresh tap does.
        beginPlay()
        // Falls back to the reliable muxed default when nothing qualifies (or there are no ladders).
        val chosen = chosenQuality(resolved)
        val selected = chosen?.id ?: resolved.qualities.firstOrNull { it.videoUrl == resolved.item.mediaUrl }?.id
        _quality.value = QualityState(
            options = resolved.qualities,
            selectedId = selected,
            canListen = resolved.audioOnlyUrl != null,
            listening = false,
            audioTracks = resolved.audioTracks,
            audioLanguage = resolved.audioLanguage,
        )
        // The one line that says, after the fact, WHICH LANGUAGE was handed to the player. The
        // resolve line could not: it named the audio-only pick while a muxed stream chosen by a
        // different rule was what played (report 0.1.373, an English talk in German).
        Diag.log(
            "playback",
            "${resolved.item.id.value} stream ${chosen?.label ?: "default"}" +
                (chosen?.codec?.let { " $it" } ?: "") +
                " audio ${(chosen?.audio ?: AudioTrackTag.Unknown).label}" +
                (if (chosen?.audioUrl != null) " (merged)" else " (one stream)") +
                "; ${resolved.audioTracks.size} track(s) offered" +
                // Whether the URLs about to play have been through the `n` solve. Without this a
                // report cannot tell "it stopped a minute in" apart from "it was never going to
                // play past a minute", and those have completely different fixes. YouTube's
                // enforcement is inconsistent — the same video serves a deep range on one resolve
                // and refuses it on the next — so the only way to know which happened on his phone
                // is to record what we were handed. See MediaFormat.isDurable.
                " [durable video=${chosen?.videoUrl?.solvedN()} audio=${chosen?.audioUrl?.solvedN()}]",
        )
        if (chosen != null) {
            playback.play(
                resolved.item.copy(mediaUrl = chosen.videoUrl),
                skipSegments = resolved.skipSegments,
                audioUrl = chosen.audioUrl,
                subtitles = resolved.subtitles,
                startPositionMs = startPositionMs,
            )
        } else {
            playback.play(
                resolved.item,
                skipSegments = resolved.skipSegments,
                subtitles = resolved.subtitles,
                startPositionMs = startPositionMs,
            )
        }
    }

    /**
     * Switches the current video to audio-only ("Listen") — replays the same
     * item with just the audio stream, so there's no video track (the player
     * shows artwork) and far less data is used. Replays from the saved position.
     * No-op if nothing is resolved or there's no audio-only stream.
     */
    /**
     * Switches to the sound alone and says whether it could.
     *
     * Recovery needs the ANSWER, not just the attempt: with no audio-only stream there is nothing to
     * fall back to and the item has to be abandoned, and a `Unit`-returning [listen] cannot tell the
     * two apart. Same call, same rules — this only reports the outcome.
     */
    fun listenIfPossible(fromMs: Long): Boolean {
        if (current?.audioOnlyUrl == null) return false
        listen(fromMs)
        return true
    }

    fun listen(fromMs: Long = playback.state.value?.positionMs ?: 0) {
        val resolved = current ?: return
        val audio = resolved.audioOnlyUrl ?: run {
            // Said out loud rather than returning silently: "listen mode is a bit weird with
            // torrents" was exactly this — the control appeared to do nothing, and nothing in a
            // report explained why.
            Diag.log("playback", "${resolved.item.id.value} has no audio-only stream; cannot listen")
            return
        }
        // Claimed like any other play: switching to Listen by hand has to supersede a resolve
        // still in flight, or that resolve lands seconds later and puts the picture back.
        beginPlay()
        _quality.update { it.copy(selectedId = null, listening = true) }
        Diag.log(
            "playback",
            "${resolved.item.id.value} listening — audio track ${resolved.audioLanguage ?: "preferred"}"
        )
        // FROM WHERE YOU WERE. This passed no start position and began again at zero, while the
        // doc above claimed it replayed from the saved position — mild when toggling Listen on a
        // short video, and the reason a rescue of an hour-deep seek threw the hour away.
        playback.play(
            resolved.item.copy(mediaUrl = audio),
            skipSegments = resolved.skipSegments,
            startPositionMs = fromMs,
        )
    }

    /**
     * Switches the current video to another audio language, replaying from where it is.
     *
     * Every stream is re-picked for that language, not just the audio one: on YouTube's HLS
     * manifest the language is baked into the muxed variant, so choosing a track that only
     * swapped the merge partner would leave a muxed stream still speaking the old one.
     *
     * No-op — and said out loud — when nothing is playing or the video's formats are no longer
     * held, because a silent no-op on a menu tap is indistinguishable from a broken menu.
     */
    suspend fun selectAudioTrack(languageCode: String) {
        val request = beginPlay()
        val watchUrl = currentWatchUrl ?: run {
            Diag.log("playback", "no video is resolved; cannot switch to audio track $languageCode")
            return
        }
        val listing = current?.item
        // Remembered BEFORE the re-pick, so the next video resolves in this language too — the
        // resolver chooses streams before the launcher ever sees them.
        choices.chooseAudio(languageCode)
        val repicked = resolver.selectAudioLanguage(watchUrl, languageCode) ?: run {
            Diag.log("playback", "audio track $languageCode not applied; keeping the current track")
            return
        }
        if (request != latestRequest.get()) {
            // Re-picking a language re-extracts, so this waits as long as a first resolve does.
            Diag.log("playback", "dropping the $languageCode switch — something newer started while it re-picked")
            return
        }
        // The listing's facts survive the re-pick exactly as they do a first resolve.
        val resolved = if (listing == null) repicked else repicked.copy(item = listing.withStreamFrom(repicked.item))
        current = resolved
        Diag.log("playback", "${resolved.item.id.value} audio track -> $languageCode")
        if (_quality.value.listening) listen() else playVideoQuality(resolved)
    }

    /** Whether an address carries a solved `n`, in either of the two spellings YouTube uses. */
    private fun HttpUrl.solvedN(): Boolean =
        SOLVED_N.any { it.containsMatchIn(value) }

    /** Leaves "Listen" (audio-only) and returns to watching the video, at the saved position. */
    fun watch() {
        val resolved = current ?: return
        if (resolved.isOneStream) {
            // Nothing to switch TO. A torrent is a single file carrying both tracks, so
            // re-preparing it changes nothing except losing your place — measured 2026-08-02,
            // report 0.1.317: toggling the mode on a Peep Show episode restarted it at 20ms
            // from 5876ms, every time, and the video decoded on regardless because there is
            // only the one stream. Silence beats a pointless restart.
            Diag.log("playback", "${resolved.item.id.value} has one stream; staying where it is")
            return
        }
        playVideoQuality(resolved)
    }

    /**
     * Switches the current video to another quality, replaying from where it
     * is now (the playback controller restores the saved position). No-op if
     * nothing is playing or the id is unknown.
     */
    fun selectQuality(id: String) {
        val resolved = current ?: return
        val quality = resolved.qualities.firstOrNull { it.id == id } ?: return
        // Remembered, so the next video in the queue opens at the height you asked for.
        choices.chooseHeight(quality.height)
        _quality.update { it.copy(selectedId = id, listening = false) }
        playback.play(
            resolved.item.copy(mediaUrl = quality.videoUrl),
            skipSegments = resolved.skipSegments,
            audioUrl = quality.audioUrl,
        )
    }
}

/**
 * The two spellings of a solved `n`: a query parameter on a `videoplayback` URL, a path segment on an
 * HLS manifest. Duplicated from `MediaFormat.isDurable` deliberately — that one judges a FORMAT before
 * a quality is chosen, this one describes the URL actually handed to the player, and they are checked
 * at different moments for different reasons. If a third caller appears, factor it.
 */
private val SOLVED_N = listOf(Regex("""[?&]n=[^&]+"""), Regex("""/n/[^/?&]+"""))
