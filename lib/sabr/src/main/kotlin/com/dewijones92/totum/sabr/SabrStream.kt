package com.dewijones92.totum.sabr

import com.dewijones92.totum.common.Diag
import com.dewijones92.totum.common.Vitals

/** Posts a SABR request body and returns the raw UMP response. */
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
 * **Only the requested itag is kept.** A response carrying audio and video is normal (no track
 * bitfield was found that returns video alone), so anything that is not [format] is dropped by
 * its [MediaHeader]. For audio there IS a bitfield that asks for audio alone, which is why
 * [tracks] exists.
 *
 * Progress is driven by `player_time_ms`, because that is what the server actually responds to:
 * `buffered_ranges` alone advanced twice and then stalled, while the same request with a larger
 * `player_time_ms` reached byte 8761825 instead of 1271335. So each fetch asks from a little
 * further on than the last, and the wall clock of the media — not our byte count — is what
 * moves.
 */
public class SabrStream(
    private val url: String,
    private val ustreamerConfig: ByteArray,
    private val format: SabrFormat,
    /** Whether [format] is the audio or the video track — which request field it belongs in. */
    private val kind: SabrTrackKind,
    private val transport: SabrTransport,
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

    /** The next byte offset we have not yet served to a reader. */
    private var served = 0L
    private var playerTimeMs = 0L
    private var exhausted = false

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
     * Bytes downloaded and thrown away, because a VIDEO request also returns audio and no track
     * bitfield was found that suppresses it. The audio track then fetches that same audio again,
     * so a video played this way costs noticeably more data than it needs to — worth measuring
     * rather than discovering on a phone bill.
     */
    private var bytesDiscarded = 0L

    /** Reads that had to WAIT on the network. The ones a listener hears as a gap. */
    private var readsThatFetched = 0
    private var emptyResponses = 0

    /** Total length of this format: the player response's figure, not a run's. */
    public val contentLength: Long? get() = totalBytes

    /**
     * Bytes starting at [from], or empty when the stream is finished.
     *
     * Fetches as needed. Returns what is contiguously available rather than a fixed size,
     * because SABR decides how much to send and pretending otherwise would mean buffering
     * whole megabytes to satisfy an arbitrary request length.
     */
    public suspend fun read(from: Long): ByteArray {
        served = from
        reads++
        var attempts = 0
        while (attempts < MAX_FETCHES_PER_READ) {
            contiguousFrom(from)?.let { held ->
                bytesServed += held.size
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
        Diag.warn(
            "sabr",
            "STUCK: itag ${format.itag} has no bytes at offset $from after $attempts fetches " +
                "(held ${chunks.size} runs at ${chunks.keys.take(HELD_TO_NAME)}, served ${bytesServed}B)",
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
        return "itag=${format.itag} fetches=$fetches reads=$reads waited=$readsThatFetched " +
            "served=${bytesServed}B discarded=${bytesDiscarded}B ($wasted% wasted) " +
            "avgFetch=${averageMs}ms mediaTime=${playerTimeMs}ms " +
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
            // Audio alone is the one selection the server honours, and it is a tenth of the
            // bytes; asking for video means accepting audio alongside it.
            tracks = if (kind == SabrTrackKind.AUDIO) SabrTracks.AUDIO_ONLY else SabrTracks.AUDIO_AND_VIDEO,
            bufferedRanges = bufferedRanges(),
        ).encode()
        val startedAt = clock()
        val response = transport.post(url, body)
        val elapsed = clock() - startedAt
        fetches++
        totalFetchMs += elapsed
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
                "${response.size}B response, ${added}B kept, ${elapsed}ms, " +
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
        UmpReader.read(response).parts.forEach { part ->
            when (part.type) {
                UmpPart.MEDIA_HEADER -> remember(MediaHeader.parse(part.payload))
                UmpPart.MEDIA -> added += storeMedia(part.payload)
                else -> Unit
            }
        }
        return added
    }

    /** What the last response carried, which is how the sharing question gets answered. */
    private val carried = CarriedItags()

    private fun remember(header: MediaHeader?) {
        val known = header ?: return
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
    private fun advanceClaimedTime() {
        val furthest = writeAt.values.maxOrNull() ?: 0
        val derived = segmentsHeld.timeOfByte(furthest, totalBytes, durationMs)
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
