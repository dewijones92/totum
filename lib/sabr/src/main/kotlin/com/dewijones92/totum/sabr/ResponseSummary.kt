package com.dewijones92.totum.sabr

import com.dewijones92.totum.sabr.Protobuf.numberAt

/**
 * What one SABR response actually said, in a line short enough to log every time.
 *
 * Exists because a response carrying no media is the single hardest thing to diagnose here:
 * the bytes stop, the player treats it as the end of the video, and nothing distinguishes "the
 * file finished" from "YouTube refused". Measured 2026-07-31, a 1080p video stopped at 23% on a
 * 658B answer whose only content was `STREAM_PROTECTION_STATUS` — a refusal, and the reason
 * SABR cannot yet be more than a beta.
 */
public object ResponseSummary {

    /**
     * A description of the refusal in [response], or null when there is nothing of the kind in it.
     *
     * Separate from [of] because the interesting case is a response carrying media AND a refusal:
     * on 2026-08-18 YouTube served the app its first minute of every stream and declined the rest,
     * which the old code could not report because it only summarised responses that were completely
     * empty. A stream that stops after a minute is indistinguishable from our own bug without this.
     */
    public fun refusalIn(response: ByteArray): String? {
        val parts = UmpReader.read(response).parts
        val refused = parts.any { it.type in REFUSAL_PARTS }
        return if (refused) of(response) else null
    }

    /** Part types that mean the server is declining rather than simply having nothing to send. */
    private val REFUSAL_PARTS = setOf(
        UmpPart.STREAM_PROTECTION_STATUS,
        UmpPart.SABR_ERROR,
        UmpPart.RELOAD_PLAYER_RESPONSE,
        UmpPart.SABR_CONTEXT_UPDATE,
    )

    public fun of(response: ByteArray): String {
        val parts = UmpReader.read(response).parts
        val itags = parts.filter { it.type == UmpPart.MEDIA_HEADER }
            .mapNotNull { MediaHeader.parse(it.payload)?.itag }
            .distinct()
        val reasons = parts.filter { it.type == UmpPart.SABR_ERROR || it.type == UmpPart.RELOAD_PLAYER_RESPONSE }
            .map { part -> part.payload.decodeToString().filter { it.code in PRINTABLE }.take(REASON_CHARS) }
        return "parts=${parts.map { it.name }.distinct()} itags=$itags reasons=$reasons " +
            "protection=${protectionStatus(parts)}"
    }

    /**
     * Every numeric field of `STREAM_PROTECTION_STATUS`, verbatim.
     *
     * Deliberately NOT interpreted. Field 1 was assumed to be the status enum (1 ok, 2 pending,
     * 3 required) and on the wire it reads 9000 then 8000 — millisecond-looking values, so that
     * assumption was simply wrong, and a log that translated it would have stated a confident
     * falsehood. The part's presence with no media alongside it is the finding; naming which
     * field means what can wait until something has actually decoded it.
     */
    private fun protectionStatus(parts: List<UmpReader.Part>): String {
        val payload = parts.lastOrNull { it.type == UmpPart.STREAM_PROTECTION_STATUS }?.payload
            ?: return "none"
        val fields = Protobuf.read(payload).keys.sorted()
            .mapNotNull { field -> Protobuf.read(payload).numberAt(field)?.let { "$field=$it" } }
        return fields.joinToString(",").ifEmpty { "no numeric fields" }
    }

    private val PRINTABLE = 32..126
    private const val REASON_CHARS = 60
}
