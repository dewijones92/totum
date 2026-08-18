package com.dewijones92.totum.sabr

/**
 * The UMP part types, named because a hex id in a log tells nobody anything.
 *
 * **Generated from `UMPPartId`** in `LuanRT/googlevideo`'s `protos/video_streaming/ump_part_id.proto`,
 * not written by hand. The hand-written version was wrong in **10 of its 16 entries**, and the six it got
 * right happened to be the ones that drive behaviour ([MEDIA_HEADER], [MEDIA], [MEDIA_END], the onesie
 * pair and [LIVE_METADATA]) — which is exactly why nothing ever played wrongly and nobody noticed. What it
 * broke was every diagnostic: [NEXT_REQUEST_POLICY] (35) was labelled `STREAM_PROTECTION_STATUS` and is on
 * essentially every response, so "the server refused this" was logged at WARN on healthy fetches, right
 * under the line saying how many bytes had just arrived. Meanwhile the real [STREAM_PROTECTION_STATUS]
 * (58) was labelled `LAWNMOWER_POLICY`, so a genuine attestation refusal produced no refusal line at all.
 *
 * ⚠️ Part names in older diagnostics reports and in the SABR docs use the OLD, wrong labels. A report from
 * before 2026-08-18 saying `STREAM_PROTECTION_STATUS` almost certainly means `NEXT_REQUEST_POLICY`.
 *
 * Only a handful matter to a player — [MEDIA_HEADER] says which format and offset the bytes that follow
 * belong to, [MEDIA] carries them, [MEDIA_END] closes a run — but the rest are named so a response can be
 * read at a glance. [SABR_ERROR] and [RELOAD_PLAYER_RESPONSE] carry a machine-readable reason and are how
 * a malformed request announces itself: the first probe of this endpoint answered
 * `RELOAD_PLAYER_RESPONSE: sabr.malformed_config`, which is what pointed at the missing config field.
 */
public object UmpPart {

    public const val ONESIE_HEADER: Int = 10
    public const val ONESIE_DATA: Int = 11
    public const val ONESIE_ENCRYPTED_MEDIA: Int = 12
    public const val MEDIA_HEADER: Int = 20
    public const val MEDIA: Int = 21
    public const val MEDIA_END: Int = 22
    public const val CONFIG: Int = 30
    public const val LIVE_METADATA: Int = 31
    public const val HOSTNAME_CHANGE_HINT_DEPRECATED: Int = 32
    public const val LIVE_METADATA_PROMISE: Int = 33
    public const val LIVE_METADATA_PROMISE_CANCELLATION: Int = 34
    public const val NEXT_REQUEST_POLICY: Int = 35
    public const val USTREAMER_VIDEO_AND_FORMAT_METADATA: Int = 36
    public const val FORMAT_SELECTION_CONFIG: Int = 37
    public const val USTREAMER_SELECTED_MEDIA_STREAM: Int = 38
    public const val FORMAT_INITIALIZATION_METADATA: Int = 42
    public const val SABR_REDIRECT: Int = 43
    public const val SABR_ERROR: Int = 44
    public const val SABR_SEEK: Int = 45
    public const val RELOAD_PLAYER_RESPONSE: Int = 46
    public const val PLAYBACK_START_POLICY: Int = 47
    public const val ALLOWED_CACHED_FORMATS: Int = 48
    public const val START_BW_SAMPLING_HINT: Int = 49
    public const val PAUSE_BW_SAMPLING_HINT: Int = 50
    public const val SELECTABLE_FORMATS: Int = 51
    public const val REQUEST_IDENTIFIER: Int = 52
    public const val REQUEST_CANCELLATION_POLICY: Int = 53
    public const val ONESIE_PREFETCH_REJECTION: Int = 54
    public const val TIMELINE_CONTEXT: Int = 55
    public const val REQUEST_PIPELINING: Int = 56
    public const val SABR_CONTEXT_UPDATE: Int = 57
    public const val STREAM_PROTECTION_STATUS: Int = 58
    public const val SABR_CONTEXT_SENDING_POLICY: Int = 59
    public const val LAWNMOWER_POLICY: Int = 60
    public const val SABR_ACK: Int = 61
    public const val END_OF_TRACK: Int = 62
    public const val CACHE_LOAD_POLICY: Int = 63
    public const val LAWNMOWER_MESSAGING_POLICY: Int = 64
    public const val PREWARM_CONNECTION: Int = 65
    public const val PLAYBACK_DEBUG_INFO: Int = 66
    public const val SNACKBAR_MESSAGE: Int = 67

    /** Every known part id to its name, for reading a response at a glance. */
    public val names: Map<Int, String> = mapOf(
        ONESIE_HEADER to "ONESIE_HEADER",
        ONESIE_DATA to "ONESIE_DATA",
        ONESIE_ENCRYPTED_MEDIA to "ONESIE_ENCRYPTED_MEDIA",
        MEDIA_HEADER to "MEDIA_HEADER",
        MEDIA to "MEDIA",
        MEDIA_END to "MEDIA_END",
        CONFIG to "CONFIG",
        LIVE_METADATA to "LIVE_METADATA",
        HOSTNAME_CHANGE_HINT_DEPRECATED to "HOSTNAME_CHANGE_HINT_DEPRECATED",
        LIVE_METADATA_PROMISE to "LIVE_METADATA_PROMISE",
        LIVE_METADATA_PROMISE_CANCELLATION to "LIVE_METADATA_PROMISE_CANCELLATION",
        NEXT_REQUEST_POLICY to "NEXT_REQUEST_POLICY",
        USTREAMER_VIDEO_AND_FORMAT_METADATA to "USTREAMER_VIDEO_AND_FORMAT_METADATA",
        FORMAT_SELECTION_CONFIG to "FORMAT_SELECTION_CONFIG",
        USTREAMER_SELECTED_MEDIA_STREAM to "USTREAMER_SELECTED_MEDIA_STREAM",
        FORMAT_INITIALIZATION_METADATA to "FORMAT_INITIALIZATION_METADATA",
        SABR_REDIRECT to "SABR_REDIRECT",
        SABR_ERROR to "SABR_ERROR",
        SABR_SEEK to "SABR_SEEK",
        RELOAD_PLAYER_RESPONSE to "RELOAD_PLAYER_RESPONSE",
        PLAYBACK_START_POLICY to "PLAYBACK_START_POLICY",
        ALLOWED_CACHED_FORMATS to "ALLOWED_CACHED_FORMATS",
        START_BW_SAMPLING_HINT to "START_BW_SAMPLING_HINT",
        PAUSE_BW_SAMPLING_HINT to "PAUSE_BW_SAMPLING_HINT",
        SELECTABLE_FORMATS to "SELECTABLE_FORMATS",
        REQUEST_IDENTIFIER to "REQUEST_IDENTIFIER",
        REQUEST_CANCELLATION_POLICY to "REQUEST_CANCELLATION_POLICY",
        ONESIE_PREFETCH_REJECTION to "ONESIE_PREFETCH_REJECTION",
        TIMELINE_CONTEXT to "TIMELINE_CONTEXT",
        REQUEST_PIPELINING to "REQUEST_PIPELINING",
        SABR_CONTEXT_UPDATE to "SABR_CONTEXT_UPDATE",
        STREAM_PROTECTION_STATUS to "STREAM_PROTECTION_STATUS",
        SABR_CONTEXT_SENDING_POLICY to "SABR_CONTEXT_SENDING_POLICY",
        LAWNMOWER_POLICY to "LAWNMOWER_POLICY",
        SABR_ACK to "SABR_ACK",
        END_OF_TRACK to "END_OF_TRACK",
        CACHE_LOAD_POLICY to "CACHE_LOAD_POLICY",
        LAWNMOWER_MESSAGING_POLICY to "LAWNMOWER_MESSAGING_POLICY",
        PREWARM_CONNECTION to "PREWARM_CONNECTION",
        PLAYBACK_DEBUG_INFO to "PLAYBACK_DEBUG_INFO",
        SNACKBAR_MESSAGE to "SNACKBAR_MESSAGE",
    )

    /** [names] if we know it, else `UNKNOWN_<id>` so an unmapped part is still identifiable. */
    public fun nameOf(type: Int): String = names[type] ?: "UNKNOWN_$type"
}
