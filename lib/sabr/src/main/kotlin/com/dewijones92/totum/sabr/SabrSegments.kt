package com.dewijones92.totum.sabr

import com.dewijones92.totum.common.Diag

/** One complete segment of a format: what it covers, and its bytes. */
public class SabrSegment(
    public val sequenceNumber: Int,
    public val startMs: Long,
    public val durationMs: Long,
    public val isInitSegment: Boolean,
    public val bytes: ByteArray,
) {
    override fun toString(): String =
        "seq=$sequenceNumber ${startMs}ms+${durationMs}ms ${bytes.size}B" + if (isInitSegment) " init" else ""
}

/**
 * The SABR conversation, addressed by SEGMENT and TIME rather than by byte.
 *
 * This exists because the byte-addressed shape cannot obey the protocol. The server states
 * `target_audio_readahead_ms` on nearly every response — 15000ms — and serves that far beyond the
 * **playback position**. A `DataSource` is never told where playback is, and worse, `SabrStream.read`
 * fetches in a loop inside one blocking call, so a single read pulls a megabyte and races the claimed
 * position to fifty-seven seconds in about a second of real time. Measured on totum-api35, repeatedly:
 * ten fetches inside a handful of reads, then an initialization segment and nothing else, at 979459B.
 * Capping ExoPlayer's buffer by duration AND by bytes changed neither number, because ExoPlayer never
 * sees those requests and so never gets to say "that is enough".
 *
 * So: ONE request per call, at a time the caller supplies, returning whole segments. The caller is a
 * `ChunkSource`, which Media3 hands `playbackPositionUs` and asks for one chunk at a time — the only
 * shape in which the server's rule is expressible. See `docs/todos/sabr-as-a-chunk-source.md`.
 */
public class SabrSegments(
    private val url: String,
    private val ustreamerConfig: ByteArray,
    private val format: SabrFormat,
    private val kind: SabrTrackKind,
    private val transport: SabrTransport,
    private val clientInfo: SabrClientInfo? = null,
    private val poToken: ByteArray? = null,
) {
    private val held = sortedMapOf<Int, SabrSegment>()
    private var initSegment: SabrSegment? = null
    private var playbackCookie: ByteArray? = null
    private var requests = 0

    /** The initialization segment, once any response has carried one. */
    public val initialization: SabrSegment? get() = initSegment

    /** What the server last told us about pacing, for a caller that wants to honour it. */
    public var policy: NextRequestPolicy? = null
        private set

    /**
     * The segment covering [atMs], fetching at that position if we do not already hold it.
     *
     * The position is passed IN rather than derived, which is the whole point: it is the playback
     * position, and it is the only thing the server bases its answer on.
     */
    public suspend fun covering(atMs: Long): SabrSegment? {
        held.values.firstOrNull { !it.isInitSegment && atMs >= it.startMs && atMs < it.startMs + it.durationMs }
            ?.let { return it }
        fetch(atMs)
        return held.values.firstOrNull {
            !it.isInitSegment && atMs >= it.startMs && atMs < it.startMs + it.durationMs
        } ?: held.values.firstOrNull { !it.isInitSegment && it.startMs >= atMs }
    }

    /** The segment after [sequenceNumber], fetching from the end of it when we do not hold it. */
    public suspend fun after(sequenceNumber: Int): SabrSegment? {
        held.values.firstOrNull { !it.isInitSegment && it.sequenceNumber == sequenceNumber + 1 }
            ?.let { return it }
        val previous = held[sequenceNumber] ?: return covering(0)
        fetch(previous.startMs + previous.durationMs)
        return held.values.firstOrNull { !it.isInitSegment && it.sequenceNumber > sequenceNumber }
    }

    /** Segments before [beforeMs] are dropped: a chunk source keeps its own queue. */
    public fun forgetBefore(beforeMs: Long) {
        val gone = held.entries.filter { !it.value.isInitSegment && it.value.startMs + it.value.durationMs < beforeMs }
        gone.forEach { held.remove(it.key) }
    }

    private suspend fun fetch(atMs: Long) {
        val body = VideoPlaybackAbrRequest(
            ustreamerConfig = ustreamerConfig,
            playerTimeMs = atMs,
            audio = format.takeIf { kind == SabrTrackKind.AUDIO },
            video = format.takeIf { kind == SabrTrackKind.VIDEO },
            tracks = if (kind == SabrTrackKind.AUDIO) SabrTracks.AUDIO_ONLY else SabrTracks.AUDIO_AND_VIDEO,
            bufferedRanges = emptyList(),
            poToken = poToken,
            clientInfo = clientInfo,
            playbackCookie = playbackCookie,
        ).encode()
        val response = transport.post(url, body)
        requests++
        NextRequestPolicy.inResponse(response)?.let { seen ->
            policy = seen
            seen.playbackCookie?.let { playbackCookie = it }
        }
        val added = absorb(response)
        // When nothing new arrives, say what the response DID contain. A response carrying no media is
        // the only interesting one, and naming its parts is the difference between "the server stopped"
        // and knowing why -- SABR_ERROR and RELOAD_PLAYER_RESPONSE both say so outright, and this class
        // otherwise reads only MEDIA_HEADER and MEDIA.
        if (added == 0) {
            val parts = UmpReader.read(response).parts
            Diag.warn(
                "sabr",
                "segments: nothing new at ${atMs}ms from ${response.size}B — parts " +
                    parts.joinToString { "${it.name}(${it.payload.size}B)" } +
                    (ResponseSummary.refusalIn(response)?.let { " REFUSAL: $it" } ?: "") +
                    " summary ${ResponseSummary.of(response)}",
            )
        }
        // One line per request, carrying what was ASKED as well as what came back: a response with no
        // new media is meaningful only alongside the position that asked for it.
        Diag.log(
            "sabr",
            "segments: request #$requests itag ${format.itag} at ${atMs}ms -> ${response.size}B, " +
                "$added new segment(s), holding ${held.size}${policy?.let { " [$it]" } ?: ""}",
        )
    }

    /** Collects whole segments from one response. Returns how many new ones arrived. */
    private fun absorb(response: ByteArray): Int {
        val headers = mutableMapOf<Long, MediaHeader>()
        val bodies = mutableMapOf<Long, ByteArray>()
        UmpReader.read(response).parts.forEach { part -> collect(part, headers, bodies) }
        return headers.entries.count { (id, header) -> keep(header, bodies[id]) }
    }

    private fun collect(
        part: UmpReader.Part,
        headers: MutableMap<Long, MediaHeader>,
        bodies: MutableMap<Long, ByteArray>,
    ) {
        when (part.type) {
            UmpPart.MEDIA_HEADER -> MediaHeader.parse(part.payload)?.let { headers[it.headerId] = it }
            UmpPart.MEDIA -> {
                // A MEDIA part names its run in its first bytes. Runs interleave, so attributing by
                // "the last header seen" splices one format's bytes into another's.
                val id = UmpVarint.read(part.payload, 0) ?: return
                val payload = part.payload.copyOfRange(id.next, part.payload.size)
                bodies[id.value] = (bodies[id.value] ?: ByteArray(0)) + payload
            }
            else -> Unit
        }
    }

    /** Keeps one segment if it is ours and new, and says whether it was new. */
    private fun keep(header: MediaHeader, body: ByteArray?): Boolean {
        if (body == null || body.isEmpty()) return false
        if (header.itag != null && header.itag != format.itag) return false
        val segment = SabrSegment(
            sequenceNumber = header.sequenceNumber ?: 0,
            startMs = header.startMs ?: 0,
            durationMs = header.durationMs ?: 0,
            isInitSegment = header.isInitSegment,
            bytes = body,
        )
        if (!segment.isInitSegment) return held.put(segment.sequenceNumber, segment) == null
        if (initSegment != null) return false
        initSegment = segment
        return true
    }
}
