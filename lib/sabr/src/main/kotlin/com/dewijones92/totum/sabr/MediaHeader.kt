package com.dewijones92.totum.sabr

import com.dewijones92.totum.sabr.Protobuf.bytesAt
import com.dewijones92.totum.sabr.Protobuf.numberAt

/**
 * What a `MEDIA_HEADER` says about the bytes that follow it.
 *
 * This is how a SABR response is made sense of at all. One response interleaves several
 * formats — a real one carried audio (itag 249, WebM) and video (itag 396, fMP4) together —
 * and every `MEDIA` part belongs to whichever header last declared its [headerId]. Without
 * this, the bytes are one undifferentiated stream and unplayable.
 *
 * Field numbers were read off live responses on 2026-07-31 and cross-checked against the media
 * itself: header 0 declared itag 249 and its bytes began `1a45dfa3` (WebM/EBML), header 1
 * declared itag 396 and began `ftypdash` (fMP4 init) — so the mapping is confirmed by the
 * container magic, not just by a plausible-looking number.
 */
public data class MediaHeader(
    /** Ties the following `MEDIA` parts to this header. Sequential within a response. */
    public val headerId: Long,
    public val videoId: String?,
    /** The format these bytes belong to, matching a format's `itag` in the player response. */
    public val itag: Int?,
    /** Matches the format's `lastModified`; the two together identify a format exactly. */
    public val lastModified: Long?,
    /** Byte offset of these bytes within the whole format. Observed running cumulatively. */
    public val startBytes: Long,
    /** True for an initialisation segment — the container header, not playable media. */
    public val isInitSegment: Boolean,
    /** How many bytes this run declares. */
    public val contentLength: Long?,
    /**
     * Which segment of the format this run is, so a request can tell the server which segments it
     * already holds. Without it a `BufferedRange` cannot be built and the server re-sends from the
     * start of whatever segment covers the claimed time — measured 2026-08-18 as 52% of every byte
     * fetched being discarded, and a stream that could not get past ~800KB of a 31MB file.
     */
    public val sequenceNumber: Int?,
    /** Where this run sits in MEDIA time, which beats deriving a position from byte ratios. */
    public val startMs: Long?,
    public val durationMs: Long?,
) {
    public companion object {

        /** Null when the payload is not a readable header. */
        public fun parse(payload: ByteArray): MediaHeader? {
            val fields = Protobuf.read(payload)
            if (fields.isEmpty()) return null
            return MediaHeader(
                headerId = fields.numberAt(FIELD_HEADER_ID) ?: 0,
                videoId = fields.bytesAt(FIELD_VIDEO_ID)?.decodeToString(),
                itag = fields.numberAt(FIELD_ITAG)?.toInt(),
                lastModified = fields.numberAt(FIELD_LAST_MODIFIED),
                // Absent on the first run of a format, where it is zero by definition.
                startBytes = fields.numberAt(FIELD_START_BYTES) ?: 0,
                isInitSegment = fields.numberAt(FIELD_IS_INIT_SEGMENT) == 1L,
                contentLength = fields.numberAt(FIELD_CONTENT_LENGTH),
                sequenceNumber = fields.numberAt(FIELD_SEQUENCE_NUMBER)?.toInt(),
                startMs = fields.numberAt(FIELD_START_MS),
                durationMs = fields.numberAt(FIELD_DURATION_MS),
            )
        }

        private const val FIELD_HEADER_ID = 1
        private const val FIELD_VIDEO_ID = 2
        private const val FIELD_ITAG = 3
        private const val FIELD_LAST_MODIFIED = 4
        private const val FIELD_START_BYTES = 6
        private const val FIELD_IS_INIT_SEGMENT = 8
        private const val FIELD_SEQUENCE_NUMBER = 9
        private const val FIELD_START_MS = 11
        private const val FIELD_DURATION_MS = 12
        private const val FIELD_CONTENT_LENGTH = 14
    }
}
