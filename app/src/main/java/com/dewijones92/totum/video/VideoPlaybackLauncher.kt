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

/**
 * The one place a video goes from "a watch URL" to "playing", and the owner of
 * the current video's selectable qualities. Streaming URLs expire, so a video
 * is resolved through the shared [VideoResolver] at play time; a downloaded
 * copy skips resolution entirely. Every entry point that plays a video — the
 * Videos feed, Search, a channel page — uses this, so the resolve-then-play
 * decision and quality switching live once.
 */
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

    /** Plays an already-downloaded file — no re-resolution, and no quality choice (it's one merged file). */
    /** Drops any cached resolution for [watchUrl] — see [VideoResolver.forget]. */
    fun forgetResolved(watchUrl: HttpUrl) {
        resolver.forget(watchUrl)
    }

    fun playLocal(item: MediaItem, localPath: String) {
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
     */
    suspend fun play(listing: MediaItem, watchUrl: HttpUrl, startPositionMs: Long = 0): Boolean {
        // `asked` names WHO wanted this, because a report showed one video extracted four
        // times in thirty seconds and the log could not say by whom.
        val extracted = resolver.resolve(watchUrl, listing.sourceId, asked = "play") ?: return false
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

    /** Plays [resolved] as video at the best allowed quality — the shared play/"Watch" path. */
    private fun playVideoQuality(resolved: VideoResolver.Resolved, startPositionMs: Long = 0) {
        // The height you last picked by hand, within the network's cap; the best the cap allows
        // if you have not picked one. Falls back to the reliable muxed default when nothing
        // qualifies at all (or there are no ladders).
        val chosen = choices.qualityFrom(resolved.qualities, preferredMaxHeight())
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
                "; ${resolved.audioTracks.size} track(s) offered",
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
    fun listen() {
        val resolved = current ?: return
        val audio = resolved.audioOnlyUrl ?: run {
            // Said out loud rather than returning silently: "listen mode is a bit weird with
            // torrents" was exactly this — the control appeared to do nothing, and nothing in a
            // report explained why.
            Diag.log("playback", "${resolved.item.id.value} has no audio-only stream; cannot listen")
            return
        }
        _quality.update { it.copy(selectedId = null, listening = true) }
        Diag.log(
            "playback",
            "${resolved.item.id.value} listening — audio track ${resolved.audioLanguage ?: "preferred"}"
        )
        playback.play(resolved.item.copy(mediaUrl = audio), skipSegments = resolved.skipSegments)
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
        // The listing's facts survive the re-pick exactly as they do a first resolve.
        val resolved = if (listing == null) repicked else repicked.copy(item = listing.withStreamFrom(repicked.item))
        current = resolved
        Diag.log("playback", "${resolved.item.id.value} audio track -> $languageCode")
        if (_quality.value.listening) listen() else playVideoQuality(resolved)
    }

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
