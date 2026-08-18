package com.dewijones92.totum.sabr

import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * "The server refused this" must mean the server refused it.
 *
 * `UmpPart`'s id table was wrong in **10 of its 16 entries**, checked against `UMPPartId` in
 * `LuanRT/googlevideo`'s `protos/video_streaming/ump_part_id.proto`. The six correct ones happened to be
 * the ones that drive behaviour (`MEDIA_HEADER`, `MEDIA`, `MEDIA_END`, the onesie pair, `LIVE_METADATA`),
 * which is exactly why nothing played wrongly and nobody noticed.
 *
 * What it broke is the diagnostics, in both directions at once:
 *
 * | we called it | id | what actually lives there |
 * |---|---|---|
 * | `STREAM_PROTECTION_STATUS` | 35 | `NEXT_REQUEST_POLICY` — on essentially every response |
 * | `SABR_ERROR` | 42 | `FORMAT_INITIALIZATION_METADATA` — one per format |
 * | `RELOAD_PLAYER_RESPONSE` | 44 | `SABR_ERROR` — right part, wrong name |
 * | `SABR_CONTEXT_UPDATE` | 55 | `TIMELINE_CONTEXT` — ordinary context |
 *
 * So `REFUSAL_PARTS` matched part 35, which is present on nearly every response, and the app logged
 * `fetch #N was refused` at WARN on healthy fetches — directly underneath the line saying how many bytes
 * it had just kept. Two contradictory lines about one fetch. It is visible in this repo's own evidence
 * from 2026-08-18: `301126B response, 256607B kept` followed immediately by `was refused`. Meanwhile the
 * REAL `STREAM_PROTECTION_STATUS` is 58 — which the old table labelled `LAWNMOWER_POLICY` and left out of
 * `REFUSAL_PARTS` entirely, so a genuine attestation refusal produced no refusal line at all.
 *
 * It also explains a false conclusion recorded in `ResponseSummary`: field 1 "reads 9000 then 8000, so
 * that assumption was simply wrong". It was decoding `NextRequestPolicy`, whose fields are backoff
 * milliseconds. The status enum was never being read.
 *
 * Both directions are asserted here, because a fix that merely stopped over-reporting would leave the
 * real refusal invisible — and that is the half that matters.
 */
class ARefusalIsNotEveryResponseTest {

    private val audio = SabrFormat(itag = 251, lastModified = 42L, xtags = null)

    /** A healthy media response, carrying the two parts the old table mistook for refusals. */
    private fun healthyResponse(): ByteArray =
        UmpFraming.part(UmpPart.NEXT_REQUEST_POLICY, Protobuf.number(1, 9000)) +
            UmpFraming.part(UmpPart.FORMAT_INITIALIZATION_METADATA, Protobuf.number(1, 251)) +
            UmpFraming.run(audio, offset = 0, size = CHUNK)

    /** Attestation being demanded: the real part, with the real status enum set to "required". */
    private fun attestationRequired(): ByteArray =
        UmpFraming.part(
            UmpPart.STREAM_PROTECTION_STATUS,
            Protobuf.number(STATUS_FIELD, STATUS_REQUIRED) + Protobuf.number(2, MAX_RETRIES),
        )

    /** THE over-reporting half: a fetch that delivered media is not a refusal. */
    @Test
    fun `a healthy media response is not a refusal`() {
        assertNull(
            "a response carrying NEXT_REQUEST_POLICY and media is the ORDINARY case, not a refusal",
            ResponseSummary.refusalIn(healthyResponse()),
        )
    }

    /** The under-reporting half, and the one that matters: a real refusal must be reported. */
    @Test
    fun `attestation being required is a refusal`() {
        val summary = ResponseSummary.refusalIn(attestationRequired())

        assertNotNull("a STREAM_PROTECTION_STATUS of 3 is exactly what a refusal looks like", summary)
        assertTrue(
            "and the summary must say the status, which is the one datum that proves attestation: $summary",
            summary!!.contains("status=$STATUS_REQUIRED"),
        )
    }

    /** A protection part that says "fine" is not a refusal either — status is the discriminator. */
    @Test
    fun `a protection status of ok is not a refusal`() {
        val ok = UmpFraming.part(UmpPart.STREAM_PROTECTION_STATUS, Protobuf.number(STATUS_FIELD, 1))

        assertNull("status 1 means attestation is not required", ResponseSummary.refusalIn(ok))
    }

    /** A genuine SABR_ERROR is a refusal whatever else is in the response. */
    @Test
    fun `a sabr error is a refusal`() {
        val errored = UmpFraming.run(audio, offset = 0, size = CHUNK) +
            UmpFraming.part(UmpPart.SABR_ERROR, "sabr.malformed_config".encodeToByteArray())

        assertNotNull(ResponseSummary.refusalIn(errored))
    }

    private companion object {
        const val CHUNK = 4096

        /** `StreamProtectionStatus.status`, and 3 is "attestation required". */
        const val STATUS_FIELD = 1
        const val STATUS_REQUIRED = 3L
        const val MAX_RETRIES = 10L
    }
}
