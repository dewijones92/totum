package com.dewijones92.totum.sabr

/**
 * The server's instructions about the NEXT request — sent on nearly every response, and discarded
 * by this app until 2026-08-20.
 *
 * This is the half of the SABR conversation where the server says how far ahead it is willing to
 * serve and when to come back. Ignoring it is a plausible explanation for a ceiling that has resisted
 * every other theory: a stream stops at about 1.1MB, roughly fifty seconds of Opus, with the
 * protection status that means "not refusing you" — which is what a **readahead limit** looks like
 * rather than a wall.
 *
 * Field numbers from SmartTube's `next_request_policy.proto`, read rather than guessed.
 */
public data class NextRequestPolicy(
    /** How far ahead of the playback position the server will serve audio. */
    public val targetAudioReadaheadMs: Long? = null,
    public val targetVideoReadaheadMs: Long? = null,
    /** Come back within this, or the session is treated as gone. */
    public val maxTimeSinceLastRequestMs: Long? = null,
    /** Wait this long before asking again. */
    public val backoffTimeMs: Long? = null,
    public val minAudioReadaheadMs: Long? = null,
    /**
     * Opaque session state to echo back on the next request.
     *
     * Not sent by us yet. Every reference implementation carries it, and a server that issues one and
     * never sees it again is entitled to treat each request as a new conversation.
     */
    public val playbackCookie: ByteArray? = null,
) {
    override fun equals(other: Any?): Boolean =
        this === other || (
            other is NextRequestPolicy &&
                targetAudioReadaheadMs == other.targetAudioReadaheadMs &&
                targetVideoReadaheadMs == other.targetVideoReadaheadMs &&
                maxTimeSinceLastRequestMs == other.maxTimeSinceLastRequestMs &&
                backoffTimeMs == other.backoffTimeMs &&
                minAudioReadaheadMs == other.minAudioReadaheadMs &&
                playbackCookie.contentEquals(other.playbackCookie)
            )

    override fun hashCode(): Int = targetAudioReadaheadMs.hashCode()

    /** Readable in one line of a report, which is where this has to earn its place. */
    override fun toString(): String = listOfNotNull(
        targetAudioReadaheadMs?.let { "targetAudioReadahead=${it}ms" },
        targetVideoReadaheadMs?.let { "targetVideoReadahead=${it}ms" },
        maxTimeSinceLastRequestMs?.let { "maxSinceLastRequest=${it}ms" },
        backoffTimeMs?.let { "backoff=${it}ms" },
        minAudioReadaheadMs?.let { "minAudioReadahead=${it}ms" },
        playbackCookie?.let { "cookie=${it.size}B" },
    ).joinToString(" ").ifEmpty { "empty" }

    public companion object {
        /** The policy in a response, or null when it carried none. */
        public fun inResponse(response: ByteArray): NextRequestPolicy? =
            UmpReader.read(response).parts
                .lastOrNull { it.type == UmpPart.NEXT_REQUEST_POLICY }
                ?.let { parse(it.payload) }

        internal fun parse(payload: ByteArray): NextRequestPolicy {
            val fields = Protobuf.read(payload)
            fun number(field: Int) = (fields[field]?.firstOrNull() as? Protobuf.Value.Number)?.value
            return NextRequestPolicy(
                targetAudioReadaheadMs = number(TARGET_AUDIO_READAHEAD_MS),
                targetVideoReadaheadMs = number(TARGET_VIDEO_READAHEAD_MS),
                maxTimeSinceLastRequestMs = number(MAX_TIME_SINCE_LAST_REQUEST_MS),
                backoffTimeMs = number(BACKOFF_TIME_MS),
                minAudioReadaheadMs = number(MIN_AUDIO_READAHEAD_MS),
                playbackCookie = (fields[PLAYBACK_COOKIE]?.firstOrNull() as? Protobuf.Value.Bytes)?.value,
            )
        }

        private const val TARGET_AUDIO_READAHEAD_MS = 1
        private const val TARGET_VIDEO_READAHEAD_MS = 2
        private const val MAX_TIME_SINCE_LAST_REQUEST_MS = 3
        private const val BACKOFF_TIME_MS = 4
        private const val MIN_AUDIO_READAHEAD_MS = 5
        private const val PLAYBACK_COOKIE = 7
    }
}
