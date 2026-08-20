package com.dewijones92.totum.sabr

/**
 * The body of a SABR media request.
 *
 * **Proven minimal**: on 2026-07-31 a POST to `serverAbrStreamingUrl` carrying nothing but
 * [ustreamerConfig] returned 212246 bytes of UMP containing WebM and fMP4 initialisation
 * segments and `moof` fragments for audio and video at once. Sending an EMPTY body instead
 * answers `RELOAD_PLAYER_RESPONSE: sabr.malformed_config`, which is how the required field was
 * identified in the first place.
 *
 * Field numbers are from the reverse-engineered schema; only the ones we have a use for are
 * here, and each is named for what it does rather than for its number.
 */
public class VideoPlaybackAbrRequest(
    /**
     * `playerConfig.mediaCommonConfig.mediaUstreamerRequestConfig.videoPlaybackUstreamerConfig`
     * from the player response, base64url-DECODED. It is the one field the server insists on.
     */
    private val ustreamerConfig: ByteArray,
    /**
     * Where playback is. **This is what advances the stream**, and it has to go inside
     * `ClientAbrState` — the top-level `player_time_ms` (field 4) is ignored outright.
     * Measured 2026-07-31: at 0ms the response reached byte 1271335 of the video, and at
     * 30000ms it reached 8761825, from the same request in every other respect.
     */
    private val playerTimeMs: Long = 0,
    private val audio: SabrFormat? = null,
    private val video: SabrFormat? = null,
    private val tracks: SabrTracks = SabrTracks.AUDIO_AND_VIDEO,
    /**
     * What the client already holds. Empty means "nothing yet", and is sent as nothing at all.
     *
     * The other half of what the server decides from — see [BufferedRange]. Without it the answers
     * repeat the same segments for ever, whatever [playerTimeMs] says.
     */
    private val bufferedRanges: List<BufferedRange> = emptyList(),
    /**
     * The proof-of-origin token, or null when we have none.
     *
     * **The field whose absence stops SABR after about a minute.** Measured on totum-api35,
     * 2026-08-20: eighteen conversations, every one ending between 968840B and 990078B, after which
     * the server answered with the initialization segment and nothing else. At 1080p that is roughly
     * four seconds. Nothing about our byte bookkeeping or our Media3 seam could lift it, because the
     * refusal is attestation and this request had nowhere to put an attestation.
     *
     * It also makes the earlier "PO token: not needed" note unsafe: that was measured with a request
     * that could not carry one, so it could only find that adding nothing changed nothing.
     */
    private val poToken: ByteArray? = null,
) {
    public fun encode(): ByteArray {
        val abrState = Protobuf.number(STATE_PLAYER_TIME_MS, playerTimeMs) +
            Protobuf.number(STATE_ENABLED_TRACKS, tracks.bitfield.toLong())
        var body = Protobuf.bytes(FIELD_CLIENT_ABR_STATE, abrState)
        // Preferred rather than "selected": selected_format_ids (field 2) was ignored, while
        // these are what the server actually honoured.
        audio?.let { body += Protobuf.bytes(FIELD_PREFERRED_AUDIO, it.encode()) }
        video?.let { body += Protobuf.bytes(FIELD_PREFERRED_VIDEO, it.encode()) }
        bufferedRanges.forEach { body += Protobuf.bytes(FIELD_BUFFERED_RANGES, it.encode()) }
        body += Protobuf.bytes(FIELD_USTREAMER_CONFIG, ustreamerConfig)
        // Only when there IS one. An empty context is a change to the request the server accepts
        // today, and a request it dislikes answers `sabr.malformed_config` — which looks exactly like
        // the wall this field exists to lift, so it would hide its own failure.
        poToken?.let {
            body += Protobuf.bytes(FIELD_STREAMER_CONTEXT, Protobuf.bytes(CONTEXT_PO_TOKEN, it))
        }
        return body
    }

    private companion object {
        const val FIELD_CLIENT_ABR_STATE = 1
        const val FIELD_BUFFERED_RANGES = 3
        const val FIELD_USTREAMER_CONFIG = 5
        const val FIELD_PREFERRED_AUDIO = 16
        const val FIELD_PREFERRED_VIDEO = 17
        const val STATE_PLAYER_TIME_MS = 28
        const val FIELD_STREAMER_CONTEXT = 19
        const val CONTEXT_PO_TOKEN = 2
        const val STATE_ENABLED_TRACKS = 40
    }
}
