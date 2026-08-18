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
        val refused = parts.any { it.type in REFUSAL_PARTS } || attestationRequired(parts)
        return if (refused) of(response) else null
    }

    /**
     * Part types that mean the server is declining rather than simply having nothing to send.
     *
     * Two of the four used to be misidentified ids -- `NEXT_REQUEST_POLICY` (on nearly every response) and
     * `FORMAT_INITIALIZATION_METADATA` (one per format) -- so this reported a refusal on healthy fetches
     * while the real refusal went unreported. See [UmpPart].
     *
     * `STREAM_PROTECTION_STATUS` is deliberately NOT in here: it appears on ordinary responses too and only
     * means a refusal when its status says attestation is required, which [attestationRequired] judges.
     */
    private val REFUSAL_PARTS = setOf(
        UmpPart.SABR_ERROR,
        UmpPart.RELOAD_PLAYER_RESPONSE,
    )

    /**
     * Whether the response demands attestation — `StreamProtectionStatus.status == 3`.
     *
     * The one datum that proves the attestation wall, and it never reached a report before: the part was
     * being read at the wrong id, so what got decoded was `NextRequestPolicy`'s backoff milliseconds. That
     * is the "field 1 reads 9000 then 8000" puzzle this file used to record as an unexplained oddity.
     */
    private fun attestationRequired(parts: List<UmpReader.Part>): Boolean =
        parts.filter { it.type == UmpPart.STREAM_PROTECTION_STATUS }
            .any { Protobuf.read(it.payload).numberAt(STATUS_FIELD) == ATTESTATION_REQUIRED }

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
     * Every numeric field of `STREAM_PROTECTION_STATUS`, verbatim, with the status named.
     *
     * The old note here said field 1 "reads 9000 then 8000, so that assumption was simply wrong" and left
     * the fields uninterpreted as a result. The assumption was right and the ID was wrong: this was
     * decoding `NextRequestPolicy`, whose fields are backoff milliseconds. Read at the correct id (58),
     * field 1 IS the status enum -- 1 ok, 2 pending, 3 attestation required.
     */
    private fun protectionStatus(parts: List<UmpReader.Part>): String {
        val payload = parts.lastOrNull { it.type == UmpPart.STREAM_PROTECTION_STATUS }?.payload
            ?: return "none"
        val read = Protobuf.read(payload)
        val fields = read.keys.sorted().mapNotNull { field ->
            // The status field is NAMED, because it is the one datum that proves the attestation wall and
            // "1=3" tells a reader nothing months later.
            read.numberAt(field)?.let { if (field == STATUS_FIELD) "status=$it" else "$field=$it" }
        }
        return fields.joinToString(",").ifEmpty { "no numeric fields" }
    }

    private val PRINTABLE = 32..126
    private const val REASON_CHARS = 60

    /** `StreamProtectionStatus.status`, and the value that means the wall is up. */
    private const val STATUS_FIELD = 1
    private const val ATTESTATION_REQUIRED = 3L
}
