package com.dewijones92.totum.video

import com.dewijones92.totum.common.AudioTrackTag
import com.dewijones92.totum.common.Diag
import com.dewijones92.totum.common.HttpUrl
import com.dewijones92.totum.common.SubtitleTrack
import com.dewijones92.totum.common.Vitals
import com.dewijones92.totum.common.youTubeVideoId
import com.dewijones92.totum.data.sponsorblock.SkipSegmentSource
import com.dewijones92.totum.domain.Chapter
import com.dewijones92.totum.domain.MediaItem
import com.dewijones92.totum.domain.MediaItemId
import com.dewijones92.totum.domain.SkipSegment
import com.dewijones92.totum.domain.SourceId
import com.dewijones92.totum.innertube.player.chaptersFromDescription
import com.dewijones92.totum.ytdlp.ExtractionResult
import com.dewijones92.totum.ytdlp.MediaMetadata
import com.dewijones92.totum.ytdlp.YtDlpEngine
import com.dewijones92.totum.ytdlp.audioTracks
import com.dewijones92.totum.ytdlp.bestAudioUrl
import com.dewijones92.totum.ytdlp.bestPlayableFormat
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.time.Duration.Companion.seconds

/**
 * Turns a video watch URL into a directly-playable [MediaItem] plus its
 * SponsorBlock segments, resolving the stream through the engine. Shared by
 * search and channel playback so the resolve-then-play logic lives once.
 */
// One method per resolve strategy plus their small helpers; the count tracks how many ways
// there are to get a stream, which is the domain rather than any complexity.
@Suppress("TooManyFunctions")
class VideoResolver(
    private val engine: YtDlpEngine,
    private val skipSegments: SkipSegmentSource,
    /** Keeps undecodable streams out of the quality ladder (see [VideoCodecSupport]). */
    private val codecSupport: VideoCodecSupport = VideoCodecSupport.Permissive,
    /**
     * YouTube's own player response — asked FIRST, because it is ~0.2s against extraction's
     * 13.9s, and also consulted when an extraction comes back with a degraded ladder.
     * Null disables both, which is what tests and previews want.
     */
    private val playerStreams: PlayerStreams? = null,
    /**
     * Whether to resolve over SABR instead of extracting. Experimental and off by default —
     * ~150ms against 2-4s, but it cannot seek yet. See `SabrResolve`.
     */
    private val sabrEnabled: () -> Boolean = { false },
    /**
     * Where this video would resume, so SABR can decline a video that is part-watched.
     *
     * Resuming IS a seek, and SABR cannot seek: measured 2026-07-31, a video resumed at
     * 367799ms opened its video track ~41MB in, SABR answered with bytes from the start
     * of the file, all of them were discarded as already-passed, and the video track died
     * at 16% while the audio carried on — a video playing with no picture. Extraction can
     * seek, so a part-watched video takes the slow path and keeps working.
     */
    private val resumePositionMs: suspend (MediaItemId) -> Long? = { null },
    /**
     * Audio languages to prefer, best first — the device's language in the app, empty in tests.
     *
     * Consulted by every stream picker. YouTube publishes automatic dubs alongside the original
     * and offers them as ordinary formats, so without this the choice comes down to whichever
     * happened to be tallest: report 0.1.373 watched an English conference talk in German.
     */
    private val preferredAudioLanguages: () -> List<String> = { emptyList() },
    private val now: () -> Long = System::currentTimeMillis,
) {
    /**
     * The most recently resolved video, reused until it goes stale.
     *
     * Extraction measured 7.2 SECONDS on a real device, and because videos resolve
     * just-in-time that was seven seconds of silence between every track. Prefetching only
     * helps if the answer survives to be used, hence this.
     *
     * Exactly one entry: what is being prefetched is the ONE item playing next, and a larger
     * cache would hold URLs long enough to expire — which is the failure this must not cause.
     * The single entry is also why the TTL stays short: a stale URL 403s, and
     * ExpiredStreamRecovery only re-resolves, so serving one from here would loop.
     */
    private data class Cached(
        val resolved: Resolved,
        val at: Long,
        /** Kept so switching audio track re-picks streams without paying for another extraction. */
        val metadata: MediaMetadata? = null,
        val sourceId: SourceId? = null,
    )

    /**
     * Recently resolved videos, least-recently-resolved first, [CACHE_SIZE] at most.
     *
     * It held exactly ONE, which was enough when it only handed a prefetched result to the
     * next play. Tapping through a few videos then evicted each on the next, so going back to
     * one just played re-extracted it: a real report (0.1.227) shows the same video extracted
     * three times in under a minute at 20-26s each. The TTL is what keeps a URL from going
     * stale, not the count, so holding a handful is no less safe than holding one.
     */
    private val cache = LinkedHashMap<HttpUrl, Cached>()

    /**
     * The extraction currently running, if any, and what it is for. Concurrent callers for
     * the same video await this rather than starting a second one.
     */
    private var inFlight: Pair<HttpUrl, CompletableDeferred<Resolved?>>? = null
    private val inFlightMutex = Mutex()

    /** One resolve's inputs, together because they travel together through both player paths. */
    private data class PlayerRequest(
        val id: String,
        val sourceId: SourceId,
        val watchUrl: HttpUrl,
        val asked: String,
        val startedAt: Long,
    )

    data class Resolved(
        val item: MediaItem,
        val skipSegments: List<SkipSegment>,
        /** Selectable streaming qualities, highest first; empty for audio-only. */
        val qualities: List<VideoQuality> = emptyList(),
        /** Best audio-only stream, for "Listen" mode; null if none is available. */
        val audioOnlyUrl: HttpUrl? = null,
        /** YouTube watch-progress stats URLs (from yt-dlp's player response), null for non-YouTube. */
        /** Renderable caption tracks; empty when the video has none we can use. */
        val subtitles: List<SubtitleTrack> = emptyList(),
        /**
         * Selectable audio tracks, best-first; empty when the video offers no choice.
         *
         * Empty rather than one-entry for a single-track video, so the menu appears only where
         * there is something to decide — which is the same rule the quality menu follows.
         */
        val audioTracks: List<AudioTrackTag> = emptyList(),
        /** The language these streams were picked for, or null for "whatever was preferred". */
        val audioLanguage: String? = null,
    )

    /**
     * Null when the video can't be resolved (private, removed, geo-blocked, …).
     *
     * Each way of failing is logged distinctly. They used to share one silent `return
     * null`, so "it wouldn't play" gave no clue whether extraction failed or succeeded
     * with nothing playable in it — a difference between a YouTube change and a codec
     * problem.
     */
    suspend fun resolve(watchUrl: HttpUrl, sourceId: SourceId, asked: String = "play"): Resolved? {
        // Share one extraction between concurrent askers.
        //
        // The cache only helps a caller arriving AFTER the first has finished. A real report
        // (0.1.226) shows the same video resolved twice, overlapping: the second started
        // while the first was still running, saw an empty cache, and did the whole 10s
        // extraction again. Two callers wanting the same video at the same moment should
        // wait on one answer.
        //
        // The FIRST caller does the work on its own dispatcher and the others await it —
        // rather than handing it to a scope this class owns, which would take the work off
        // whatever dispatcher the caller (or a test) is driving.
        val joined = inFlightMutex.withLock {
            inFlight?.takeIf { it.first == watchUrl && it.second.isActive }?.second
        }
        if (joined != null) {
            Diag.log(
                "resolve",
                "joining the extraction already running for " +
                    "${watchUrl.value.takeLast(ID_CHARS)} ($asked)",
            )
            return joined.await()
        }

        val mine = CompletableDeferred<Resolved?>()
        inFlightMutex.withLock { inFlight = watchUrl to mine }
        return try {
            extractAndCache(watchUrl, sourceId, asked).also(mine::complete)
        } finally {
            // Always settled, never left hanging: whoever joined must not wait forever
            // because the caller that started the work threw or was cancelled.
            mine.complete(null)
            inFlightMutex.withLock { if (inFlight?.second === mine) inFlight = null }
        }
    }

    private suspend fun extractAndCache(watchUrl: HttpUrl, sourceId: SourceId, asked: String): Resolved? {
        fresh(watchUrl)?.let { hit ->
            // Kept, not consumed. It used to be cleared on first use, which was right when an
            // extraction cost ~1.7s and the cache existed only to hand a prefetched result to
            // the next play. With a JS runtime an extraction costs 10-14s on a real phone, so
            // every replay, seek-triggered re-resolve and quality change was paying it again:
            // one video was extracted FOUR times in 30 seconds on Dewi's Pixel.
            Diag.log("resolve", "cache hit for ${watchUrl.value.takeLast(ID_CHARS)} ($asked), skipped extraction")
            return hit
        }
        val startedAt = now()
        overSabr(watchUrl, sourceId, asked, startedAt)?.let { return it }
        // Extraction FIRST, and the player response only as a fallback. Asking YouTube first was
        // tried and reverted on the evidence of report 0.1.312, which measured it on a real
        // phone rather than the emulator where it looked wonderful:
        //
        //   - it was not fast. 15-37 SECONDS per resolve, not the 0.2s seen on the emulator,
        //     because the emulator's URLs happened to carry no `n` and the phone's all do — so
        //     every resolve paid for a QuickJS solve.
        //   - it was not reliable. Thirteen fast resolves in one session, nearly all ending in
        //     "Source error / stream failed — Unreachable", with 7 playback errors and 17 stalls.
        //
        // Slower AND broken, against an extraction path that works. The machinery it added is
        // kept and still earns its place — [fromDirectStreams] is what finally made
        // age-restricted videos play, via the recovery path below.
        return byExtraction(watchUrl, sourceId, asked, startedAt)
    }

    /** The long way round: yt-dlp, ~2-4s on a phone, and what everything falls back to. */
    private suspend fun byExtraction(
        watchUrl: HttpUrl,
        sourceId: SourceId,
        asked: String,
        startedAt: Long,
    ): Resolved? {
        val extraction = engine.extract(watchUrl)
        val metadata = (extraction as? ExtractionResult.Success)?.metadata ?: run {
            Vitals.add("resolve.extractFailures")
            Diag.warn("resolve", "extract failed for ${watchUrl.value} ($asked): $extraction")
            // Age restriction is the case worth retrying: yt-dlp has no credentials and says so
            // ("Sign in to confirm your age"), while this app holds a YouTube account that
            // YouTube will serve a rated video to. Tried for ANY extraction failure rather than
            // by matching the message, because parsing yt-dlp's prose to decide would break the
            // day it is reworded — and a pointless retry costs one request on a video that was
            // not going to play anyway.
            return fromPlayerResponse(
                watchUrl,
                sourceId,
                asked,
                startedAt,
                // yt-dlp's own words, used only to LABEL the failure — never to decide whether
                // to retry, which happens regardless so a reworded message cannot break it.
                extractionSaidAgeRestricted = extraction.toString().contains("confirm your age", true),
            )
        }
        val wanted = wantedAudio(chosen = null)
        val resolved = pickStreams(metadata, sourceId, wanted, chosen = null) ?: return null
        Vitals.add("resolve.successes")
        Diag.log(
            "resolve",
            "${metadata.id} in ${now() - startedAt}ms for $asked — " +
                "${resolved.qualities.size} qualities, ${metadata.subtitles.size} subtitle tracks, " +
                "audioOnly=${resolved.audioOnlyUrl != null}" +
                metadata.audioChoice(wanted, metadata.bestPlayableFormat(wanted)),
        )
        // Remembered on EVERY resolve, not only when prefetched: a replay, a seek that
        // forces a re-resolve or a quality change all ask for the same video again, and each
        // one used to pay the full extraction.
        remember(watchUrl, resolved, metadata, sourceId)
        return resolved
    }

    /**
     * Re-picks every stream for [languageCode] — the audio-track menu's whole job.
     *
     * Off the cached metadata, so switching track costs nothing: the languages differ only in
     * which of the formats already extracted gets chosen. Null when the video is not cached
     * (it expired, or it came from a path that has no format list), and the caller leaves the
     * current track alone rather than restarting playback for nothing.
     */
    suspend fun selectAudioLanguage(watchUrl: HttpUrl, languageCode: String): Resolved? {
        val cached = cache[watchUrl]
        val metadata = cached?.metadata ?: run {
            Diag.log("resolve", "no formats held for ${watchUrl.value.takeLast(ID_CHARS)}; cannot switch audio track")
            return null
        }
        val wanted = wantedAudio(chosen = languageCode)
        val resolved = pickStreams(
            metadata,
            cached.sourceId ?: cached.resolved.item.sourceId,
            wanted,
            languageCode,
            // Already known, and a second SponsorBlock lookup would buy nothing: the segments
            // belong to the video, not to whichever language track you are listening to.
            knownSegments = cached.resolved.skipSegments,
        ) ?: return null
        Diag.log(
            "resolve",
            "${metadata.id} re-picked for audio $languageCode — ${resolved.qualities.size} qualities" +
                metadata.audioChoice(wanted, metadata.bestPlayableFormat(wanted)),
        )
        remember(watchUrl, resolved, metadata, cached.sourceId)
        return resolved
    }

    /** The languages to prefer: an explicit [chosen] track, else whatever the app prefers. */
    private fun wantedAudio(chosen: String?): List<String> =
        chosen?.let(::listOf) ?: preferredAudioLanguages()

    /**
     * Everything that depends on which audio language you want, in one place — so an initial
     * resolve and a track switch cannot pick by different rules.
     */
    private suspend fun pickStreams(
        metadata: MediaMetadata,
        sourceId: SourceId,
        wanted: List<String>,
        chosen: String?,
        knownSegments: List<SkipSegment>? = null,
    ): Resolved? {
        // Default stream stays the best muxed format (one stream, reliable, data-friendly);
        // the quality menu offers higher, merged qualities on demand.
        val streamUrl = metadata.bestPlayableFormat(wanted)?.url?.let(HttpUrl::parse) ?: run {
            Vitals.add("resolve.noPlayableFormat")
            Diag.warn(
                "resolve",
                "no playable format for ${metadata.id} (${metadata.formats.size} formats offered)",
            )
            return null
        }
        return Resolved(
            item = MediaItem(
                id = MediaItemId(metadata.id),
                sourceId = sourceId,
                title = metadata.title,
                publishedAt = null,
                duration = metadata.durationSeconds?.seconds,
                author = metadata.uploader,
                description = metadata.description,
                thumbnailUrl = metadata.thumbnailUrl?.let(HttpUrl::parse),
                mediaUrl = streamUrl,
                chapters = metadata.chapters.mapNotNull { chapter ->
                    val title = chapter.title.trim().ifBlank { null } ?: return@mapNotNull null
                    val start = chapter.startSeconds.takeIf { it.isFinite() && it >= 0 } ?: return@mapNotNull null
                    Chapter(start.seconds, title)
                },
            ),
            skipSegments = knownSegments ?: skipSegments.segmentsFor(metadata.id),
            qualities = betterQualities(metadata.id, metadata.videoQualities(codecSupport, wanted)),
            audioOnlyUrl = metadata.bestAudioUrl(wanted),
            subtitles = metadata.subtitles,
            audioTracks = metadata.audioTracks(wanted),
            audioLanguage = chosen,
        )
    }

    /**
     * Replaces a degraded ladder with a better one, when YouTube will give us one.
     *
     * A single 360p quality is the signature of yt-dlp having lost the rest: it has
     * deprecated extraction without a JavaScript runtime, and Chaquopy cannot give it one,
     * so on a phone it quietly drops formats. Asking `/player` ourselves as the ANDROID
     * client gets them back — 32 formats to 1080p where yt-dlp offered one at 360p, with no
     * `n` parameter to decipher and so no runtime implied. Proven on the same video from
     * which yt-dlp on a laptop WITH node also gets the full ladder, which is what identified
     * the missing runtime as the cause.
     *
     * Only on the degraded case, deliberately. yt-dlp handles a great deal this does not —
     * age gates, region locks, signature ciphers, non-YouTube sources — so it stays the
     * primary and this is the second opinion, asked when the first is visibly poor.
     */
    private suspend fun betterQualities(id: String, qualities: List<VideoQuality>): List<VideoQuality> {
        val fallback = playerStreams ?: return qualities.also { reportIfDegraded(id, it) }
        val best = qualities.maxOfOrNull { it.height } ?: 0
        if (qualities.size > 1 || best > DEGRADED_HEIGHT) return qualities
        Diag.log("resolve", "$id offered one quality at ${best}p — asking YouTube directly")

        // Guarded like the fast path: this is an OPTIMISATION — asking whether YouTube offers
        // better than yt-dlp did — and an optimisation must never be able to fail a resolve that
        // has already succeeded. Caught here after a test proved it could.
        val streams = runCatching { fallback.playerFor(id) }.getOrNull()?.streaming
            ?: return qualities.also { reportIfDegraded(id, it) }
        val better = streams.videoQualities()
        val betterBest = better.maxOfOrNull { it.height } ?: 0
        if (betterBest <= best) {
            Diag.log("resolve", "$id: the direct ask offered no better (${betterBest}p) — keeping yt-dlp's")
            reportIfDegraded(id, qualities)
            return qualities
        }
        Vitals.add("resolve.playerFallbackWins")
        Diag.log("resolve", "$id: direct ask gave ${better.size} qualities to ${betterBest}p, up from ${best}p")
        return better
    }

    /**
     * Resolves by asking YouTube and streaming over SABR, in about 150ms.
     *
     * Off unless [sabrEnabled], and null for anything less than a complete answer, so the
     * extraction path is reached exactly as before. Every refusal is logged by [SabrResolve]:
     * "SABR did not happen" with no reason would be the hardest kind of bug to chase.
     */
    /**
     * Resolves entirely from a `/player` response, for videos extraction could not touch.
     *
     * Reuses the SABR path's preparation because that is exactly what this is: streams YouTube
     * will only serve over its own protocol, plus the details needed to describe the video.
     */
    private suspend fun fromPlayerResponse(
        watchUrl: HttpUrl,
        sourceId: SourceId,
        asked: String,
        startedAt: Long,
        extractionSaidAgeRestricted: Boolean,
    ): Resolved? {
        val id = watchUrl.youTubeVideoId() ?: return null
        val fast = playerStreams ?: return null
        // Guarded: this is a LAST resort after extraction already failed, so a throwing player
        // must leave the honest "could not resolve" rather than replace it with a crash.
        val response = runCatching { fast.playerFor(id) }.getOrNull() ?: run {
            // Name the cause. Two client identities were tried against a rated video with a
            // valid signed-in token on 2026-08-01 — TVHTML5 and the embedded player — and both
            // were refused, so age restriction is a wall rather than a missing credential.
            // Saying "unavailable" for something YouTube plays perfectly well in a browser
            // invites another day of chasing it.
            val why = if (extractionSaidAgeRestricted) {
                "age-restricted — YouTube refuses it to this app even signed in"
            } else {
                "genuinely unavailable (private, removed, geo-blocked or members-only)"
            }
            Diag.warn("resolve", "$id could not be resolved by the player either — $why")
            return null
        }
        Diag.log("resolve", "$id recovered by the player response after extraction failed ($asked)")
        val request = PlayerRequest(id, sourceId, watchUrl, asked, startedAt)
        // SABR first when it is enabled and usable, then the plain URLs. Falling through matters:
        // recovery used to hand the response to SABR alone, so with SABR off — the default —
        // every recovered response was discarded and the video failed anyway. That is precisely
        // how the age-restricted work came to reach the streams and still not play.
        return overSabrFrom(request, response) ?: fromDirectStreams(request, response)
    }

    /**
     * A result built straight from the response's own URLs — the ordinary way to play.
     *
     * Only reached when yt-dlp could not extract, which today means an age-restricted video that
     * the signed-in TV client served instead. Those URLs are directly fetchable once their `n`
     * has been solved (done before this, in the account player), so nothing here is special —
     * it is the same shape [byExtraction] produces, from a different source.
     */
    private suspend fun fromDirectStreams(
        request: PlayerRequest,
        response: com.dewijones92.totum.innertube.player.PlayerResult.Success,
    ): Resolved? {
        val wanted = wantedAudio(chosen = null)
        val streamUrl = response.streaming.bestMuxedUrl(wanted) ?: response.streaming.bestAudioUrl(wanted) ?: run {
            Diag.warn(
                "resolve",
                "${request.id} recovered but has no fetchable stream " +
                    "(${response.streaming.formats.size} format(s), " +
                    "${response.streaming.directlyPlayable.size} with a URL)",
            )
            return null
        }
        val details = response.details
        // NOT through betterQualities: that exists to ask YouTube whether it can beat a degraded
        // yt-dlp ladder, and this ladder IS YouTube's answer. Routing it through anyway fetched
        // the same player response a second time on every single play — caught by a test
        // asserting one fetch and seeing two.
        val qualities = response.streaming.videoQualities(wanted)
        Vitals.add("resolve.successes")
        Diag.log(
            "resolve",
            "${request.id} in ${now() - request.startedAt}ms for ${request.asked} from the player " +
                "response — ${qualities.size} qualities, ${response.subtitles.size} subtitle tracks",
        )
        val resolved = Resolved(
            item = MediaItem(
                id = MediaItemId(request.id),
                sourceId = request.sourceId,
                // The id is a poor title and a deliberate one: it is recognisable, and it makes a
                // missing description obvious rather than inventing a plausible name for it.
                title = details?.title ?: request.id,
                publishedAt = null,
                duration = details?.lengthSeconds?.seconds,
                author = details?.author,
                description = details?.description,
                thumbnailUrl = details?.thumbnailUrl,
                mediaUrl = streamUrl,
                chapters = chaptersFromDescription(details?.description)
                    .map { (at, title) -> Chapter(at.seconds, title) },
                sourceUrl = details?.channelId?.let { HttpUrl.parse("https://www.youtube.com/channel/$it") },
            ),
            skipSegments = skipSegments.segmentsFor(request.id),
            qualities = qualities,
            audioOnlyUrl = response.streaming.bestAudioUrl(wanted),
            subtitles = response.subtitles,
        )
        remember(request.watchUrl, resolved)
        return resolved
    }

    /**
     * Resolves over SABR **as a rescue**, ignoring the experimental setting.
     *
     * The setting governs whether SABR is the PRIMARY route, which it should not be by default: it
     * caps the picture at 1080p30 and cannot seek. This entry is for the other situation entirely —
     * the ordinary stream URLs have been refused and the choice is a capped picture or none. See
     * `StreamRecovery.playOverSabr`.
     *
     * Nothing is cached: a rescue result is the answer to "what can play right now", and caching it
     * would quietly turn the next ordinary play of the same item into the capped route.
     */
    suspend fun resolveAsRescue(watchUrl: HttpUrl, sourceId: SourceId): Resolved? {
        val id = watchUrl.youTubeVideoId() ?: return null
        val fast = playerStreams ?: run {
            Diag.log("resolve", "$id cannot be rescued over SABR: there is no player-response client")
            return null
        }
        Diag.log("resolve", "$id trying SABR as a rescue — the ordinary streams were refused")
        val response = runCatching { fast.playerFor(id) }.getOrNull() ?: run {
            Diag.warn("resolve", "$id could not be rescued over SABR: no player response")
            return null
        }
        return overSabrFrom(PlayerRequest(id, sourceId, watchUrl, "rescue", now()), response)
    }

    private suspend fun overSabr(
        watchUrl: HttpUrl,
        sourceId: SourceId,
        asked: String,
        startedAt: Long,
    ): Resolved? {
        if (!sabrEnabled()) return null
        val fast = playerStreams ?: return null
        val id = watchUrl.youTubeVideoId() ?: return null
        val resumeAt = resumePositionMs(MediaItemId(id)) ?: 0
        if (resumeAt > 0) {
            Diag.log(
                "resolve",
                "$id resumes at ${resumeAt}ms, so extracting rather than using SABR — " +
                    "a resume is a seek, and the SABR path cannot seek yet",
            )
            return null
        }
        val response = fast.playerFor(id) ?: return null
        return overSabrFrom(PlayerRequest(id, sourceId, watchUrl, asked, startedAt), response)
    }

    /**
     * Builds a playable result from a `/player` response.
     *
     * Shared by the SABR fast path and by the extraction-failure fallback, because they want the
     * same thing from the same response — one is chosen for speed, the other because nothing
     * else will play the video at all.
     */
    private suspend fun overSabrFrom(
        request: PlayerRequest,
        response: com.dewijones92.totum.innertube.player.PlayerResult.Success,
    ): Resolved? {
        val id = request.id
        val sourceId = request.sourceId
        val watchUrl = request.watchUrl
        val asked = request.asked
        val startedAt = request.startedAt
        val wanted = wantedAudio(chosen = null)
        val prepared = SabrResolve.prepare(id, response.streaming, response.details, wanted) ?: return null
        val qualities = response.streaming.videoQualities(wanted)
        Vitals.add("resolve.sabrSuccesses")
        Diag.log(
            "resolve",
            "$id in ${now() - startedAt}ms for $asked OVER SABR — " +
                "${response.subtitles.size} subtitle tracks",
        )
        val resolved = Resolved(
            item = MediaItem(
                id = MediaItemId(id),
                sourceId = sourceId,
                title = prepared.details.title,
                publishedAt = null,
                duration = prepared.details.lengthSeconds?.seconds,
                author = prepared.details.author,
                description = prepared.details.description,
                thumbnailUrl = prepared.details.thumbnailUrl,
                // The sabr:// URL the data source resolves; audio plays alone in Listen mode.
                mediaUrl = prepared.videoUrl ?: prepared.audioUrl,
                chapters = chaptersFromDescription(prepared.details.description)
                    .map { (at, title) -> Chapter(at.seconds, title) },
                sourceUrl = prepared.details.channelId
                    ?.let { HttpUrl.parse("https://www.youtube.com/channel/$it") },
            ),
            skipSegments = skipSegments.segmentsFor(id),
            // Quality switching is not offered over SABR yet: the ladder is real but the
            // adaptive half of "ABR" is unimplemented, so pretending to switch would lie.
            qualities = emptyList<VideoQuality>().also {
                if (qualities.size > 1) Diag.log("sabr", "$id has ${qualities.size} qualities; not switchable yet")
            },
            audioOnlyUrl = prepared.audioUrl,
            subtitles = response.subtitles,
        )
        remember(watchUrl, resolved)
        return resolved
    }

    /**
     * Forgets any cached resolution for [watchUrl], so the next resolve genuinely re-resolves.
     *
     * Recovery is the only caller and it is the reason this exists. A stream that dies mid-play
     * is usually a URL that has expired, and re-playing it means asking for a NEW one — but the
     * replay went back through [resolve], hit this cache, and got the same dead address. A real
     * report (0.1.277) shows all three recovery attempts logging "cache hit … skipped
     * extraction" against one URL, failing identically eight seconds apart, and the video then
     * being skipped as unplayable when a fresh URL would have played it.
     *
     * The class comment predicted exactly this — "a stale URL 403s, and recovery only
     * re-resolves, so serving one from here would loop" — and the TTL alone was not enough,
     * because ten minutes is far longer than a stream takes to die.
     */
    fun forget(watchUrl: HttpUrl) {
        if (cache.remove(watchUrl) != null) {
            Diag.log("resolve", "forgot the cached URL for ${watchUrl.value.takeLast(ID_CHARS)}; it will re-resolve")
        }
    }

    /** A cached entry still inside its TTL, or null. */
    private fun fresh(watchUrl: HttpUrl): Resolved? =
        cache[watchUrl]?.takeIf { now() - it.at < CACHE_TTL_MS }?.resolved

    private fun remember(
        watchUrl: HttpUrl,
        resolved: Resolved,
        metadata: MediaMetadata? = null,
        sourceId: SourceId? = null,
    ) {
        // Re-inserting makes a re-resolved video the newest, so LinkedHashMap's insertion
        // order is a least-recently-resolved order and the first key is the one to drop.
        val kept = metadata ?: cache[watchUrl]?.metadata
        cache.remove(watchUrl)
        cache[watchUrl] = Cached(resolved, now(), kept, sourceId ?: resolved.item.sourceId)
        while (cache.size > CACHE_SIZE) cache.remove(cache.keys.first())
    }

    /**
     * Says so when a video came back with nothing but the legacy 360p stream.
     *
     * That is the signature of YouTube serving a video SABR-only: the higher formats are
     * listed in the player response but carry no URL, so yt-dlp drops them and format 18 —
     * the old progressive muxed stream — is all that survives. Today it happens on
     * made-for-kids videos; the experiment has been widening, and the point of counting it
     * is to learn that from a diagnostics report rather than from Dewi noticing a video
     * looks soft. See docs/todos/sabr-streaming.md.
     */
    private fun reportIfDegraded(id: String, qualities: List<VideoQuality>) {
        val best = qualities.maxOfOrNull { it.height } ?: return
        if (qualities.size > 1 || best > DEGRADED_HEIGHT) return
        Vitals.add("resolve.sabrDegraded")
        Diag.warn(
            "resolve",
            "$id offered ONE quality at ${best}p — YouTube is almost certainly serving this " +
                "SABR-only, so the higher formats exist but have no URL to fetch",
        )
    }

    /**
     * Resolves [watchUrl] and keeps the answer for the next [resolve] of the same URL.
     *
     * Called shortly before the current item ends, so the seven seconds of extraction happen
     * while something is still playing rather than in the silence afterwards. Failure is
     * deliberately swallowed: a prefetch that does not work must cost nothing, and the real
     * resolve will run and report properly when the item is actually needed.
     */
    suspend fun prefetch(watchUrl: HttpUrl, sourceId: SourceId): Resolved? {
        fresh(watchUrl)?.let { return it }
        Diag.log("resolve", "prefetching ${watchUrl.value.takeLast(ID_CHARS)}")
        val resolved = runCatching { resolve(watchUrl, sourceId, asked = "prefetch") }.getOrNull() ?: run {
            Diag.log("resolve", "prefetch produced nothing; the real resolve will try again")
            return null
        }
        remember(watchUrl, resolved)
        // Returned, not just cached: the caller preloads BYTES from the stream URL this produced,
        // which is the one thing that could not be known before the resolution happened.
        return resolved
    }

    private companion object {
        /**
         * Far below a signed URL's lifetime. A prefetch is used within a minute or two, so this
         * only has to outlive that — holding one longer risks handing back a URL that has already
         * expired, which is a worse failure than the wait it saves.
         */
        const val CACHE_TTL_MS = 10 * 60 * 1000L

        /** Enough of a watch URL to recognise the video in a log line. */
        const val ID_CHARS = 11

        /**
         * Enough to cover flicking between a few videos without holding URLs so long that
         * the TTL stops being the thing that governs staleness.
         */
        const val CACHE_SIZE = 8

        /** Format 18's height — the only stream that survives a SABR-only response. */
        const val DEGRADED_HEIGHT = 360
    }
}
