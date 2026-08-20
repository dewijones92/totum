package com.dewijones92.totum.sabr

/**
 * Identifies one format to SABR — and `xtags` is not optional in practice.
 *
 * A real player response carried **22 entries for each audio itag**, one per dubbed language
 * track: itag 251 alone appeared 22 times, distinguished only by `xtags` (`acont=original`
 * versus `acont=dubbed-auto`, plus `lang`). Selecting by itag and `lastModified` alone
 * therefore matches an arbitrary one of them — measured 2026-07-31, doing exactly that made
 * the server answer `RELOAD_PLAYER_RESPONSE: sabr.no_audio_selected`, because the pair
 * identified nothing it was willing to serve.
 *
 * Values come straight from the player response's format object, `xtags` included, unchanged.
 */
public data class SabrFormat(
    public val itag: Int,
    public val lastModified: Long,
    /** The player response's `xtags`, base64 exactly as YouTube wrote it; null when absent. */
    public val xtags: String? = null,
    /** The format's TOTAL length, which is what "the stream finished" means. */
    public val contentLength: Long? = null,
) {
    internal fun encode(): ByteArray {
        var out = Protobuf.number(FIELD_ITAG, itag.toLong()) +
            Protobuf.number(FIELD_LAST_MODIFIED, lastModified)
        xtags?.let { out += Protobuf.bytes(FIELD_XTAGS, it.encodeToByteArray()) }
        return out
    }

    private companion object {
        const val FIELD_ITAG = 1
        const val FIELD_LAST_MODIFIED = 2
        const val FIELD_XTAGS = 3
    }
}

/**
 * Which track types the server should send.
 *
 * Found by probing on 2026-07-31, because the meaning of the bits is not documented anywhere
 * we can rely on: **1 gives audio alone** (167876 bytes, itag 251 only), while 0, 2, 3, 6 and 7
 * all return audio AND video interleaved. That single value is what makes "Listen" mode a
 * clean single-stream fetch rather than a video download with the picture thrown away.
 *
 * **No value asks for video ALONE, and that is OURS rather than YouTube's** — 2 and 6 both still sent
 * audio. The bitfield is only half of the mechanism: the references pair it with a full-buffer sentinel
 * [BufferedRange] for the audio format, so the server has nothing left to send. We do not send that, so
 * a video request costs 40-60% extra bytes we then drop by [MediaHeader] itag. See
 * `docs/todos/sabr-streaming.md`.
 */
public enum class SabrTracks(internal val bitfield: Int) {
    /** Audio and video together, which is what every value except [AUDIO_ONLY] produced. */
    AUDIO_AND_VIDEO(0),

    /** Audio alone. Verified: one itag in the response, and a tenth of the bytes. */
    AUDIO_ONLY(1),
}

/** Which half of a playback a format is. Decides the request field and the track bitfield. */
public enum class SabrTrackKind { AUDIO, VIDEO }
