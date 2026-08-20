package com.dewijones92.totum.sabr

import com.dewijones92.totum.common.Diag
import com.dewijones92.totum.common.Vitals
import java.io.IOException

/**
 * Posts a SABR request body and returns the raw UMP response.
 *
 * **An empty return means the SERVER had nothing to send**, which is a thing SABR says routinely and
 * which [SabrStream] pays for with a thirty-second skip of the claimed time. A request that FAILED
 * must therefore throw an [IOException] rather than return nothing: the bytes are still there, we
 * simply never asked. Reporting a dropped connection as an empty answer cost a permanent hole in the
 * media, and four of them ended the stream and blacklisted SABR for the item.
 */
public fun interface SabrTransport {
    public suspend fun post(url: String, body: ByteArray): ByteArray
}

/**
 * One format of one video, as a sequential byte stream fetched over SABR.
 *
 * SABR is a conversation, not a URL: you say where playback is and it hands you the next
 * segments, framed in UMP and interleaved with whatever other formats it feels like sending.
 * This turns that into the one thing a player wants — bytes in order, from the start — so a
 * Media3 `DataSource` on top has nothing left to understand.
 *
 * **Only the requested itag is kept.** A response carrying audio and video is normal — see
 * [SabrTracks] for why asking for video alone is not built rather than not possible — so anything that
 * is not [format] is dropped by its [MediaHeader]. For audio there IS a bitfield that asks for audio
 * alone, which is why [tracks] exists.
 *
 * Progress is driven by `player_time_ms`, because that is what the server actually responds to:
 * `buffered_ranges` alone advanced twice and then stalled, while the same request with a larger
 * `player_time_ms` reached byte 8761825 instead of 1271335. So each fetch asks from a little
 * further on than the last, and the wall clock of the media — not our byte count — is what
 * moves.
 */
// One protocol conversation, and splitting it would separate the state each step mutates from the step
// that mutates it — which is how the run-attribution bug got in.
@Suppress("TooManyFunctions")
public class SabrStream(
    private val url: String,
    private val ustreamerConfig: ByteArray,
    private val format: SabrFormat,
    /** Whether [format] is the audio or the video track — which request field it belongs in. */
    private val kind: SabrTrackKind,
    private val transport: SabrTransport,
    /** Attestation for every request in this conversation. See [VideoPlaybackAbrRequest.poToken]. */
    private val poToken: ByteArray? = null,
    /** Who we say we are on every request. Null sends no client_info at all. */
    private val clientInfo: SabrClientInfo? = null,
    /** How much media time to advance per fetch. Segments observed at ~10s for audio. */
    /**
     * The format's TOTAL length from the player response, which is what "finished" means.
     *
     * A `MEDIA_HEADER` also carries a `contentLength`, but that is one RUN's length — using it
     * reported "432274B of 807B" and would have let the stream call itself complete on its first
     * init segment, ending a video seconds in.
     */
    private val totalBytes: Long? = null,
    /**
     * The media's length, so the time we claim to be at can be derived from the bytes we hold.
     *
     * Without it `playerTimeMs` only ever crept forward by [stepMs] a fetch, which for a long
     * video falls hopelessly behind the bytes already served — SABR then answers "you have
     * enough for that time", the stream reads it as the end, and a 1.19GB video stops at 7%.
     * Measured exactly that on 2026-07-31.
     */
    private val durationMs: Long? = null,
    private val stepMs: Long = DEFAULT_STEP_MS,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    /** Bytes gathered for [format], keyed by their offset in the whole stream. */
    private val chunks = sortedMapOf<Long, ByteArray>()

    /** Every run declared so far, by header id, because MEDIA parts name their own. */
    private val headers = mutableMapOf<Long, MediaHeader>()

    /** Where the next MEDIA part for each run belongs, since runs interleave. */
    private val writeAt = mutableMapOf<Long, Long>()

    /**
     * The furthest byte of [format] actually KEPT — the ceiling on any honest claimed time.
     *
     * Separate from [writeAt] on purpose. That is a per-run WRITE CURSOR and has to advance across
     * bytes we throw away, or an interleaved continuation of the same run lands at the wrong offset.
     * Deriving the claim from the cursor instead meant a RESEND carried the claim past the data — and a
     * resend is not rare: 52% of every byte fetched, before buffered ranges were sent at all. [noteHeader]
     * widened it further by recording a cursor for the OTHER track's itag, whose byte space this format
     * does not even use, so an audio conversation could claim the time of a video byte. Once the claim
     * is ahead of the data the server correctly answers "you already have enough for that time", which
     * [handleEmpty] reads as an empty response and punishes with another thirty-second skip: the
     * runaway of 2026-08-18 through a second door.
     */
    private var furthestHeld = 0L

    /** The next byte offset we have not yet served to a reader. */
    private var served = 0L

    /**
     * The byte just past what the last successful read handed out, or -1 before any.
     *
     * Needed because [contiguousFrom] CONSUMES the runs it returns, so `chunks` cannot answer "where
     * are we". Without it a mid-stream jump was indistinguishable from ordinary sequential reading, and
     * `aimAtByte` skipped the very case it exists for — a warm-jump probe on 2026-08-18 asked for
     * 130005ms when it meant to ask for 407499ms, and would have recorded a false conclusion.
     */
    private var handedThrough = -1L

    /**
     * The byte a sequential read would ask for next, or -1 before any read.
     *
     * Public so a caller can tell a CONTINUATION from a real jump. `SabrDataSource` warned "expect this
     * to stall" on every open at a non-zero position, which was true when every open built a cold
     * stream and became a lie the moment reopens started reusing a warm one — sixteen of those in ten
     * seconds of ordinary playback, all of them fine. A warning that cries wolf is worse than none.
     */
    public val readTo: Long get() = handedThrough

    /**
     * The session state the server last handed us, echoed on the next request.
     *
     * The server issues a `playback_cookie` in nearly every `NEXT_REQUEST_POLICY` and this app threw
     * every one of them away, so each request arrived as a conversation it had no memory of. Whether
     * that is what capped a stream at about 1.1MB is being measured; carrying it is correct regardless,
     * because a server that asks for something back and never gets it is being ignored.
     */
    private var playbackCookie: ByteArray? = null

    private var playerTimeMs = 0L
    private var exhausted = false

    /**
     * Whether the read that just finished gave up waiting for bytes that never came.
     *
     * Deliberately NOT [exhausted]. A stall is a fact about ONE read at ONE offset — the server
     * answered every time, just never with the byte this reader is sitting at — while [exhausted]
     * means the conversation itself has nothing left. Setting the second from the first poisoned a
     * stream that TWO consumers share: `sabrStreamFor` hands the same object to the player and to
     * the queue's auto-downloader (which reads from byte 0, and is sorted to the front of the pass),
     * so one unsatisfiable download read marked the PLAYER's live stream spent and premature and
     * tore down a track that was streaming fine. Cleared at the top of every [read], so the reader
     * that stalled is the only one that sees it.
     */
    private var stalled = false

    /** Failed round trips within the current read, so six of them cost one log line and not six. */
    private var failuresThisRead = 0

    /**
     * What we already hold, so a request can say so — the half of the SABR conversation that was
     * missing until 2026-08-18. See [HeldSegments] and [BufferedRange].
     */
    private val segmentsHeld = HeldSegments(format)

    /** Counted rather than logged per call: a read happens every few KB and would flood. */
    private var reads = 0
    private var fetches = 0
    private var bytesServed = 0L
    private var totalFetchMs = 0L

    /**
     * Bytes downloaded and thrown away, because a VIDEO request also returns audio we have not asked
     * the server to withhold — the bitfield alone does not, see [SabrTracks]. The audio track then
     * fetches that same audio again, so a video played this way costs noticeably more data than it needs
     * to — worth measuring rather than discovering on a phone bill.
     */
    private var bytesDiscarded = 0L

    /** Reads that had to WAIT on the network. The ones a listener hears as a gap. */
    private var readsThatFetched = 0
    private var emptyResponses = 0

    /** Round trips that never landed, which are a network's problem rather than the server's. */
    private var failedFetches = 0

    /** Total length of this format: the player response's figure, not a run's. */
    public val contentLength: Long? get() = totalBytes

    /**
     * Whether this stream has nothing left to give, prematurely or otherwise.
     *
     * Public so a CACHE can tell a stream worth continuing from a corpse. Reusing a spent one is an
     * infinite failure loop, measured 2026-08-19: the stream ended short at byte 979459, ExoPlayer
     * reopened, the cache handed back the same dead object, and it failed again — ten times in a row,
     * with the read count climbing and the byte count never moving.
     *
     * Set only where the server itself went quiet, never by a stalled read: a stall leaves megabytes
     * already fetched, the held segments and the whole `headers` map, and the next open on a dropped
     * stream is the cold mid-stream open YouTube answers with no media.
     */
    public val isSpent: Boolean get() = exhausted

    /**
     * Whether this stream stopped SHORT of the length it stated.
     *
     * The state behind the `PREMATURE END` warning, exposed so something can act on it. From a real
     * report (0.1.435, commit 3a31b58): itag 251 served 920030B of 53458433B — 1% of a 61-minute video
     * — and `SabrDataSource` reported plain end-of-input, so ExoPlayer believed the video had finished.
     * Every later seek then "succeeded" instantly into a stream that was not there.
     *
     * Null [contentLength] is never premature: a live stream states no length, and calling its natural
     * end a fault would be worse than the bug this fixes.
     */
    public val endedPrematurely: Boolean
        get() = exhausted && contentLength?.let { served < it } == true

    /**
     * Whether the LAST read gave up without its bytes — a fault, and never an ending.
     *
     * A caller that got nothing needs the two apart. An empty answer can mean the format really
     * finished, so it is judged against [contentLength]; a stall cannot mean that at any length,
     * because the true end of a format is the server answering with nothing (or with bytes already
     * served, which is the same thing here) and that goes through [handleEmpty]. So this stands on
     * its own, and a live stream — which states no length and therefore can never be "premature" —
     * still reports a stall rather than a silent end of input.
     *
     * Valid until the next [read] on this object, which clears it.
     */
    public val lastReadStalled: Boolean get() = stalled

    /**
     * Bytes starting at [from], or empty when the stream is finished.
     *
     * Fetches as needed. Returns what is contiguously available rather than a fixed size,
     * because SABR decides how much to send and pretending otherwise would mean buffering
     * whole megabytes to satisfy an arbitrary request length.
     */
    public suspend fun read(from: Long): ByteArray {
        aimAtByte(from)
        served = from
        reads++
        stalled = false
        failuresThisRead = 0
        var attempts = 0
        while (attempts < MAX_FETCHES_PER_READ) {
            contiguousFrom(from)?.let { held ->
                bytesServed += held.size
                handedThrough = from + held.size
                if (attempts > 0) readsThatFetched++
                return held
            }
            if (exhausted) {
                val length = contentLength
                Diag.log(
                    "sabr",
                    "itag ${format.itag} ended at $from — ${bytesServed}B of ${length ?: -1}B " +
                        "(${percentOf(from, length)}%) over $fetches fetches / $reads reads",
                )
                return ByteArray(0)
            }
            fetch()
            attempts++
        }
        // The shape of a stall: the network answered but never with the bytes at this offset.
        stalled = true
        Vitals.add("sabr.stuckReads")
        Diag.warn(
            "sabr",
            // ATTEMPTS, not fetches: the two differ exactly when it matters. Six attempts that all
            // failed to land is a dead network; six that landed with the wrong bytes is the server
            // ignoring where we said we are, and one line used to be printed for both.
            "STUCK: itag ${format.itag} has no bytes at offset $from after $attempts attempts " +
                "(${attempts - failuresThisRead} landed, $failuresThisRead never left) — recorded as a " +
                "FAULT for this read, not an ending, and the conversation is KEPT rather than spent: " +
                "premature=$endedPrematurely against ${contentLength ?: -1}B, holding ${chunks.size} " +
                "runs at ${chunks.keys.take(HELD_TO_NAME)}. ${describeProgress()}",
        )
        return ByteArray(0)
    }

    /** What a report needs to judge whether this felt fast: latency, throughput, and waits. */
    public fun describeProgress(): String {
        val averageMs = if (fetches == 0) 0 else totalFetchMs / fetches
        val wasted = if (bytesServed + bytesDiscarded == 0L) {
            0
        } else {
            bytesDiscarded * PERCENT / (bytesServed + bytesDiscarded)
        }
        // segments/described are here because their ABSENCE is invisible otherwise: a buffer we
        // never describe looks exactly like a server that ignores us, and on 2026-08-18 the two were
        // told apart only by adding this line.
        return "itag=${format.itag} fetches=$fetches failed=$failedFetches reads=$reads " +
            "waited=$readsThatFetched " +
            "served=${bytesServed}B discarded=${bytesDiscarded}B ($wasted% wasted) " +
            "avgFetch=${averageMs}ms mediaTime=${playerTimeMs}ms heldTo=${furthestHeld}B " +
            "segments=${segmentsHeld.count}${segmentsHeld.numbers} described=${bufferedRanges().size} " +
            "headers=${headers.values.joinToString("|") {
                "id${it.headerId}:itag${it.itag ?: "?"}:seq${it.sequenceNumber ?: "?"}:at${it.startMs ?: -1}"
            }.take(HEADERS_TO_NAME)}"
    }

    /**
     * Everything we hold that runs on unbroken from [from], or null when we hold nothing there.
     *
     * Coalesces, rather than handing back one stored run at a time. A run that resumes later in
     * the response lands under its own offset key, so without this a caller would be told
     * "nothing" at the join and a fetch would be spent re-asking for bytes already in hand —
     * and a stream can be declared finished while its next bytes are sitting in the map.
     */
    /**
     * Points the conversation at [from] when it is somewhere we have not been streaming toward.
     *
     * A units mismatch, fixed by the translation that already existed. ExoPlayer opens a track at a
     * BYTE offset; a SABR request asks for a media TIME. Nothing converted between them, so a resume
     * that opened a video track ~41MB in still asked for `player_time_ms = 0`, got the start of the
     * file, discarded every byte as already-passed, and the video track died at 16% while the audio
     * played on — a video with no picture (measured 2026-07-31). It is why SABR is confined to the
     * first ten seconds of an item.
     *
     * Only for a jump, never for sequential reading: [advanceClaimedTime] follows the bytes actually
     * held, which is more truthful than a ratio, and re-estimating per read could move the claim
     * BACKWARDS mid-stream. The server reads that as a seek and re-sends everything, which is the
     * 52%-wasted-bytes problem that sending buffered ranges exists to prevent.
     *
     * The estimate assumes a roughly constant bitrate, so it is good for audio and approximate for
     * video. That is fine: the response names the segments it really sent, and the stream then holds
     * real ranges instead of the guess. Landing near the target and correcting beats landing at zero
     * and never arriving.
     *
     * **Byte 0 is a jump like any other on a WARM stream**, and this used to open `if (from <= 0)
     * return` — right by accident on a cold stream, whose claim is already 0, and wrong on the normal
     * case, because `SabrDataSourceFactory` caches a stream per `videoId:itag`. So replaying a video
     * just watched, or downloading one that had played, asked the server about where playback had
     * reached while the reader waited at byte 0: every returned byte was past the offset being read,
     * which is the stall [read] then reports as the end of the video.
     */
    private fun aimAtByte(from: Long) {
        // A DISCONTINUITY, not merely a cold start. Sequential reading picks up exactly where the last
        // read left off; anything else is a seek and has to be aimed. Judged from `handedThrough`
        // because `contiguousFrom` consumes what it returns, so the held runs cannot say where we are.
        if (from == handedThrough) return
        if (from <= 0 && handedThrough < 0) return
        val target = timeToAimAt(from) ?: return
        val move = when {
            handedThrough < 0 -> "opening at"
            from < handedThrough -> "REWINDING to"
            else -> "seeking to"
        }
        Diag.log(
            "sabr",
            "itag ${format.itag} $move ${from}B (last handed through $handedThrough) — asking from " +
                "${target}ms instead of ${playerTimeMs}ms, holding to ${furthestHeld}B",
        )
        playerTimeMs = target
        // Lowered to the READER, never raised to it. A rewind is otherwise dragged straight back:
        // `advanceClaimedTime` takes `maxOf(playerTimeMs, …)`, so the first fetch after re-aiming
        // restores the old time from the byte a previous pass reached.
        furthestHeld = minOf(furthestHeld, from.coerceAtLeast(0))
        // The SAME lowering, said in the other half of the conversation. Re-aiming the claim alone
        // fixed only the first request of a rewind: the ranges still advertised the prefix this
        // reader had already consumed and can no longer serve, so the server did as it was asked and
        // sent the segment AFTER them — bytes ahead of the reader, which `storeMedia` keeps and
        // `advanceClaimedTime` then turns straight back into the old claim. A replay from the start
        // served nothing at all, and since a stall is now a raised fault, silently.
        segmentsHeld.forgetFrom(from.coerceAtLeast(0))
    }

    /**
     * The media time to ask about for [from], or null when there is no way to estimate one.
     *
     * Byte 0 is 0ms whatever the format's length is, which is what makes a REWIND answerable on a
     * stream that states neither length nor duration. Anything else needs the ratio.
     */
    private fun timeToAimAt(from: Long): Long? {
        if (from <= 0) return 0
        return segmentsHeld.timeOfByte(from, totalBytes, durationMs) ?: run {
            Diag.log(
                "sabr",
                "itag ${format.itag} opening at ${from}B but its length or duration is unknown, so the " +
                    "time cannot be estimated — asking from ${playerTimeMs}ms, which will not reach $from",
            )
            null
        }
    }

    private fun contiguousFrom(from: Long): ByteArray? {
        if (chunks[from] == null) return null
        var at = from
        var joined = ByteArray(0)
        while (true) {
            val next = chunks.remove(at) ?: break
            joined += next
            at += next.size
        }
        return joined.takeIf { it.isNotEmpty() }
    }

    private suspend fun fetch() {
        val body = VideoPlaybackAbrRequest(
            ustreamerConfig = ustreamerConfig,
            playerTimeMs = playerTimeMs,
            audio = format.takeIf { kind == SabrTrackKind.AUDIO },
            video = format.takeIf { kind == SabrTrackKind.VIDEO },
            // Audio alone is a tenth of the bytes. Asking for video accepts audio alongside it,
            // because the sentinel range that would suppress it is unbuilt — see SabrTracks.
            tracks = if (kind == SabrTrackKind.AUDIO) SabrTracks.AUDIO_ONLY else SabrTracks.AUDIO_AND_VIDEO,
            bufferedRanges = bufferedRanges(),
            poToken = poToken,
            clientInfo = clientInfo,
            playbackCookie = playbackCookie,
        ).encode()
        val startedAt = clock()
        val response = try {
            transport.post(url, body)
        } catch (failure: IOException) {
            noteRequestFailed(failure, clock() - startedAt)
            return
        }
        val elapsed = clock() - startedAt
        fetches++
        totalFetchMs += elapsed
        NextRequestPolicy.inResponse(response)?.let { policy ->
            policy.playbackCookie?.let { playbackCookie = it }
        }
        val added = absorb(response)
        bytesDiscarded += (response.size - added).coerceAtLeast(0)
        Vitals.add("sabr.fetches")
        Vitals.add("sabr.fetchMs", elapsed)
        Vitals.add("sabr.bytesKept", added.toLong())
        Vitals.add("sabr.bytesDiscarded", (response.size - added).coerceAtLeast(0).toLong())
        Vitals.set("sabr.lastFetch", "itag ${format.itag} +${added}B in ${elapsed}ms")
        // One line per network round trip, not per read: a fetch covers ~10s of media, so this
        // is a handful of lines a minute and the only place a stall's cause is visible.
        Diag.log(
            "sabr",
            "fetch #$fetches itag ${format.itag} at ${playerTimeMs}ms -> " +
                "${response.size}B response, ${added}B kept, ${elapsed}ms, holding to ${furthestHeld}B, " +
                "carried $carried" +
                if (elapsed > SLOW_FETCH_MS) " — SLOW" else "",
        )
        // Said on EVERY fetch that carries one, not only on an empty answer — see
        // [ResponseSummary.refusalIn] for why that distinction cost a day.
        ResponseSummary.refusalIn(response)?.let {
            Diag.warn("sabr", "itag ${format.itag} fetch #$fetches was refused: $it")
        }
        if (added > 0) {
            // CONSECUTIVE, not lifetime. Nothing reset this, so the fourth empty answer of a session
            // ended the stream however many healthy fetches sat between them — four unlucky moments
            // out of hundreds on one item, and a certainty across a four-hour listen. An empty
            // answer with bytes flowing either side of it is a hiccup; four in a row is a stop.
            emptyResponses = 0
        }
        // The claim is moved by exactly one of these: normally from the bytes that arrived, and on an
        // empty answer by a deliberate skip past whatever the server has nothing for. Doing both
        // would have the derived value quietly undo the skip.
        if (added == 0) handleEmpty(response) else advanceClaimedTime()
    }

    /**
     * Records a round trip that never landed, WITHOUT spending the empty-response budget.
     *
     * An empty answer and a failed request are byte-for-byte identical to a caller that swallows the
     * failure, and they call for opposite responses: an empty answer means the server has nothing for
     * this media time, so skipping past it is right; a failed request means we never asked, so the
     * media time is fine and only the request needs repeating. Conflating them cost a permanent
     * thirty-second hole per Wi-Fi handoff, and four in a row ended the stream.
     *
     * The repeating is the read's own fetch budget and **nothing else** — there is no backoff here, so
     * be precise about what that buys. A read timeout spends the budget over minutes; a refused
     * connection, a dead DNS or airplane mode fails in under a millisecond, so all six attempts are
     * gone before the outage has had time to end and the stall reaches [read] as one attempt in
     * practice. What the split fixes is the CLAIM, which is no longer corrupted by a failure; waiting
     * for a network to come back is the recovery ladder's job, and `recoveryReasonFrom` records how a
     * stall is classified when it gets there.
     *
     * Logged ONCE per read, then counted. Six identical warnings arrived simultaneously for one dead
     * read — twelve, with the transport's line — which is the per-event shape this repo reserves for
     * counting. The count reaches a report through the stuck line's `never left` and through
     * `describeProgress`'s `failed=`.
     */
    private fun noteRequestFailed(failure: IOException, elapsed: Long) {
        failedFetches++
        failuresThisRead++
        Vitals.add("sabr.failedFetches")
        if (failuresThisRead > 1) return
        Diag.warn(
            "sabr",
            "itag ${format.itag} fetch FAILED after ${elapsed}ms — the request did not land, so this is " +
                "NOT an empty answer: claim stays at ${playerTimeMs}ms, empty streak $emptyResponses, " +
                "held to ${furthestHeld}B, served ${bytesServed}B (failure #$failedFetches). Any further " +
                "failure in this read is counted, not logged",
            failure,
        )
    }

    /** What to do when a response carried nothing we wanted — the only place a stream ends. */
    private fun handleEmpty(response: ByteArray) {
        emptyResponses++
        // WHAT the server actually said, logged before deciding what to do about it — this
        // used to sit after the early return below, so the one case that needed explaining
        // was the one case it never explained. A 688B answer is not media: measured
        // 2026-07-31 a video stopped at 24% on exactly that, and without the part types
        // there was no way to tell a refusal from an end of stream.
        Diag.warn(
            "sabr",
            "itag ${format.itag} got no bytes at ${playerTimeMs}ms from ${response.size}B: " +
                ResponseSummary.of(response),
        )
        // NOT the end just because nothing came back. We know how long the format is, so a
        // stream that stops short of contentLength has STALLED, and calling that "finished"
        // makes a video end early and the queue advance — which is indistinguishable from
        // the video simply being short. Measured: itag 140 reported no bytes at 60000ms of a
        // much longer video, which under the old rule ended it there.
        val length = contentLength
        val complete = length != null && served >= length
        if (!complete && emptyResponses < MAX_EMPTY_RESPONSES) {
            // Skip further ahead rather than asking the same question again: the server
            // answers about a media TIME, so the same time returns the same nothing.
            playerTimeMs += stepMs * EMPTY_SKIP_STEPS
            Diag.warn(
                "sabr",
                "itag ${format.itag} gave nothing at ${playerTimeMs}ms but only ${served}B of " +
                    "${length ?: -1}B served — NOT ending, skipping ahead (empty #$emptyResponses)",
            )
            return
        }
        exhausted = true
        if (!complete) {
            // The line that says a video is about to end early, and by how much.
            Vitals.add("sabr.prematureEnds")
            Diag.warn(
                "sabr",
                "PREMATURE END: itag ${format.itag} served ${served}B of ${length ?: -1}B " +
                    "(${percentOf(served, length)}%) after $emptyResponses empty responses — " +
                    "the player will treat this as the end of the video",
            )
        }
        Vitals.add("sabr.emptyResponses")
    }

    /**
     * Files away every MEDIA run belonging to [format]. Returns how many bytes were added.
     *
     * **Routed by the header id INSIDE each MEDIA part, not by the last header seen** — this is
     * the whole difficulty of the format and what made video decode to corruption. Runs
     * interleave arbitrarily: measured 2026-07-31 on itag 134 with audio alongside it, a single
     * response went
     *
     * ```
     * MEDIA_HEADER id=3 ; MEDIA(3) ; MEDIA(1) ; MEDIA(1) ; MEDIA(1) ; MEDIA_END(1)
     * MEDIA_HEADER id=4 ; MEDIA(4) ; MEDIA(4) ; MEDIA(3) ; MEDIA_END(3) ; MEDIA(4)
     * ```
     *
     * — header 1's run resuming three parts after header 3 was declared, and header 3's
     * resuming inside header 4's. Attributing bytes to the most recent header therefore splices
     * one format's bytes into another's stream at the wrong offset, which decodes as
     * `Invalid NAL length` rather than failing outright. Audio-only survived it because a single
     * format's runs happen to arrive in order.
     *
     * The leading value is read as a UMP varint, so a header id above 127 works too — ids
     * observed so far are single-digit, which would have hidden a wrong choice indefinitely.
     */
    private fun absorb(response: ByteArray): Int {
        var added = 0
        carried.clear()
        unhandled.clear()
        UmpReader.read(response).parts.forEach { part ->
            when (part.type) {
                UmpPart.MEDIA_HEADER -> remember(MediaHeader.parse(part.payload))
                UmpPart.MEDIA -> added += storeMedia(part.payload)
                // Named, not silently dropped. A part this class ignores is indistinguishable from one
                // the server never sent, and telling those apart is the whole question for a LIVE
                // stream: its media arrives with no initialization segment, so whether the init data is
                // present in a part we skip decides whether live is buildable or refused.
                else -> unhandled += part.type
            }
        }
        if (unhandled.isNotEmpty()) {
            Diag.log("sabr", "ignored parts: " + unhandled.distinct().joinToString { "${UmpPart.nameOf(it)}($it)" })
        }
        return added
    }

    /** Part types this response carried and this class does nothing with. */
    private val unhandled = mutableListOf<Int>()

    /** What the last response carried, which is how the sharing question gets answered. */
    private val carried = CarriedItags()

    private fun remember(header: MediaHeader?) {
        val known = header ?: return
        // Every run announced, with the numbers that decide where its bytes land. A VOD starts at byte 0
        // and reads forward; a LIVE stream joins mid-broadcast, so its init segment and its first media
        // can be megabytes apart and the gap between them is invisible without this line.
        if (known.itag == format.itag) {
            Diag.log(
                "sabr",
                "run ${known.headerId} itag=${known.itag} init=${known.isInitSegment} " +
                    "startBytes=${known.startBytes} seq=${known.sequenceNumber} length=${known.contentLength}",
            )
        }
        headers[known.headerId] = known
        // Where this run starts in the whole format; every MEDIA part for it continues from here.
        writeAt[known.headerId] = known.startBytes
        segmentsHeld.record(known)
    }

    /** What we hold, as the server wants to hear it — see [HeldSegments.asRanges]. */
    private fun bufferedRanges(): List<BufferedRange> = segmentsHeld.asRanges(totalBytes, durationMs)

    /** Appends one MEDIA part to whichever run it names. Returns bytes kept. */
    private fun storeMedia(payload: ByteArray): Int {
        val id = UmpVarint.read(payload, 0) ?: return 0
        val header = headers[id.value] ?: return 0
        val bytes = payload.copyOfRange(id.next, payload.size)
        if (bytes.isEmpty()) return 0
        carried.add(header.itag, bytes.size)
        if (header.itag != format.itag) return 0
        val offset = writeAt[id.value] ?: header.startBytes
        writeAt[id.value] = offset + bytes.size
        // Already read past: a reader never goes backwards, so this is spent.
        if (offset < served) return 0
        chunks[offset] = (chunks[offset] ?: ByteArray(0)) + bytes
        furthestHeld = maxOf(furthestHeld, offset + bytes.size)
        return bytes.size
    }

    /**
     * Moves the time we claim to be at to match the bytes we now hold.
     *
     * SABR decides what to send from the player's reported position, so that position has to
     * reflect reality. Derived from bytes rather than counted in steps: a fetch returns however
     * much the server feels like, so a fixed step drifts from the truth immediately.
     *
     * **No step is added on top of the derived value**, which is the fix of 2026-08-18. It used to
     * read `.coerceAtLeast(playerTimeMs + stepMs)`, so the claim gained a full ten seconds on every
     * fetch *whatever* arrived — and once the claim is ahead of the bytes, the server answers quite
     * correctly that we already have enough for that time, which this class reads as an empty
     * response and punishes with another thirty-second skip. A runaway with its own accelerator.
     * Measured on a 37-minute video: 793KB of 31MB served, and the stream believing it was 160
     * seconds in when its bytes were worth about 50.
     *
     * Monotonic all the same — `maxOf`, not a bare assignment. Going backwards would re-ask for
     * bytes already spent, which [absorb] discards, which reads as empty: the same loop from the
     * other end. Never behind, never freely ahead.
     */
    /**
     * Only for a stream with nothing to derive a position FROM -- a live one, which states no length.
     *
     * Everything else has its claim set at request time from the reader's offset (see [fetch]). This
     * remains because stepping is genuinely all a live stream has.
     */
    private fun advanceClaimedTime() {
        // FURTHEST HELD, and that is knowingly not what the field means -- see
        // docs/todos/sabr-stops-at-one-megabyte.md. `player_time_ms` is where PLAYBACK is, and the
        // server serves
        // `target_audio_readahead_ms` beyond it -- 15000ms, which it states on nearly every response.
        // Deriving the claim from the furthest byte HELD therefore asks for fifteen seconds past what
        // we already have, and the honest answer to that is an initialization segment and nothing
        // else. Four of those end the stream, which is the entire ~1MB "wall": 968840-990078B across
        // eighteen streams on a device, 956KB on the JVM, and blamed on attestation for two days.
        // Sending a real position instead took a live probe from 1104KB to 1732KB, with a 332864B
        // response arriving exactly where an init-only reply had been, and nothing else changed.
        //
        // It is NOT fixed here, and that is deliberate. A DataSource is never told where playback is;
        // `served` is the LOADER's offset, and on a device the loader races through a megabyte in about
        // a second, so deriving from it claims forty-six seconds after one. Tried on totum-api35: no
        // improvement, and one rebuffer where there had been none. The number the server wants arrives
        // as `playbackPositionUs` in `ChunkSource.getNextChunk`, which is why the seam move fixes this
        // ceiling as well as seeking and ABR.
        val derived = segmentsHeld.timeOfByte(furthestHeld, totalBytes, durationMs)
        // Nothing to derive from — a live stream — leaves stepping as all there is, which is the
        // case the old floor was really written for.
        playerTimeMs = derived?.let { maxOf(playerTimeMs, it) } ?: (playerTimeMs + stepMs)
    }

    /** What a response actually contained, for when it contained nothing we wanted. */
    private companion object {
        const val DEFAULT_STEP_MS = 10_000L

        /** A read that cannot be satisfied in this many fetches is a stuck stream, not a slow one. */
        const val MAX_FETCHES_PER_READ = 6

        /** A fetch slower than this is a candidate cause for a gap the listener heard. */
        const val SLOW_FETCH_MS = 3_000L

        /** Enough held offsets to see the shape of a gap without printing a whole map. */
        const val HELD_TO_NAME = 4

        /** Enough header detail to see whether segments are being recognised at all. */
        const val HEADERS_TO_NAME = 200
        const val PERCENT = 100

        /**
         * Empty answers tolerated before the stream is called finished. A real end also looks
         * like an empty answer, so this cannot be infinite — but one is far too few.
         */
        const val MAX_EMPTY_RESPONSES = 4

        /** How far to jump when a time yields nothing; the same time yields the same nothing. */
        const val EMPTY_SKIP_STEPS = 3
    }
}

/**
 * What share of a known length has been served; -1 when there is no length to compare against.
 *
 * Top-level rather than a member: it is arithmetic about two numbers and holds none of the stream's
 * state, and [SabrStream] has quite enough responsibilities without it.
 */
private fun percentOf(served: Long, length: Long?): Long =
    if (length == null || length <= 0) -1 else served * PERCENT_SCALE / length

private const val PERCENT_SCALE = 100
