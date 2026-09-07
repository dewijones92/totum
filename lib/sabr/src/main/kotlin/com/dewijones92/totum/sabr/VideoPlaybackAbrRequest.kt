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
    /** Who we are, alongside the token. See [SabrClientInfo]. */
    private val clientInfo: SabrClientInfo? = null,
    /**
     * The session state the server handed us on the previous response, echoed back.
     *
     * `streamer_context.playback_cookie`, field 3. The server issues one in every
     * [NextRequestPolicy] -- 77 bytes of it, measured 2026-08-20 -- and this app had never sent one
     * back, so every request arrived as a conversation the server had no memory of agreeing to.
     */
    private val playbackCookie: ByteArray? = null,
    /** Session state the server told us to send back. See [SabrContext]. */
    private val sabrContexts: List<SabrContext> = emptyList(),
    /**
     * Formats we have already initialised — `selected_format_ids`, field 2.
     *
     * yt-dlp omits this on a FRESH conversation and sends it once a format's initialization segment
     * has arrived, which is a documented difference from what this app did: it never sent it at all.
     * Worth trying because the response at the ceiling hands back the initialization segment and
     * nothing else, which is what a server tells a client that looks uninitialised.
     */
    private val selectedFormats: List<SabrFormat> = emptyList(),
    /** The client's own bandwidth estimate in bits per second, which SmartTube always sends. */
    private val bandwidthEstimate: Long? = null,
    /**
     * The resolution the user last chose, in fields 16 and 21 as SmartTube sends them (1080 on the
     * emulator capture of 2026-09-06). Absent by default; an experiment input until it is understood.
     */
    private val stickyResolution: Int? = null,
) {
    private fun sticky(resolution: Int): ByteArray =
        Protobuf.number(STATE_LAST_MANUAL_RESOLUTION, resolution.toLong()) +
            Protobuf.number(STATE_STICKY_RESOLUTION, resolution.toLong())

    /**
     * The rest of ClientAbrState as SmartTube sends it. Read from its SabrManifest rather than guessed:
     * bandwidth estimate (23), viewport flexibility (22), playback rate (35, a float), DRC (46) and the
     * network-interruption cap (68); the sticky resolution (16/21) only when a caller sets one.
     */
    private fun abrState(): ByteArray =
        Protobuf.number(STATE_PLAYER_TIME_MS, playerTimeMs) +
            Protobuf.number(STATE_ENABLED_TRACKS, tracks.bitfield.toLong()) +
            Protobuf.number(STATE_CLIENT_VIEWPORT_IS_FLEXIBLE, 0) +
            Protobuf.float(STATE_PLAYBACK_RATE, 1.0f) +
            (stickyResolution?.let { sticky(it) } ?: ByteArray(0)) +
            Protobuf.number(STATE_DRC_ENABLED, 0) +
            Protobuf.number(STATE_MAX_NETWORK_INTERRUPTION_MS, 0) +
            (bandwidthEstimate?.let { Protobuf.number(STATE_BANDWIDTH_ESTIMATE, it) } ?: ByteArray(0))

    /**
     * `streamer_context`, or empty when there is nothing to say. An empty context is a change to the
     * request the server accepts today, and a request it dislikes answers `sabr.malformed_config` —
     * which looks exactly like the wall this field exists to lift, so it would hide its own failure.
     */
    private fun streamerContext(): ByteArray =
        (clientInfo?.let { Protobuf.bytes(CONTEXT_CLIENT_INFO, it.encode()) } ?: ByteArray(0)) +
            (poToken?.let { Protobuf.bytes(CONTEXT_PO_TOKEN, it) } ?: ByteArray(0)) +
            (playbackCookie?.let { Protobuf.bytes(CONTEXT_PLAYBACK_COOKIE, it) } ?: ByteArray(0)) +
            sabrContexts.fold(ByteArray(0)) { all, context ->
                all + Protobuf.bytes(CONTEXT_SABR_CONTEXTS, context.encode())
            }

    public fun encode(): ByteArray {
        var body = Protobuf.bytes(FIELD_CLIENT_ABR_STATE, abrState())
        selectedFormats.forEach { body += Protobuf.bytes(FIELD_SELECTED_FORMATS, it.encode()) }
        // Preferred rather than "selected": selected_format_ids (field 2) was ignored, while
        // these are what the server actually honoured.
        audio?.let { body += Protobuf.bytes(FIELD_PREFERRED_AUDIO, it.encode()) }
        video?.let { body += Protobuf.bytes(FIELD_PREFERRED_VIDEO, it.encode()) }
        bufferedRanges.forEach { body += Protobuf.bytes(FIELD_BUFFERED_RANGES, it.encode()) }
        body += Protobuf.bytes(FIELD_USTREAMER_CONFIG, ustreamerConfig)
        val context = streamerContext()
        if (context.isNotEmpty()) body += Protobuf.bytes(FIELD_STREAMER_CONTEXT, context)
        return body
    }

    private companion object {
        const val FIELD_CLIENT_ABR_STATE = 1
        const val FIELD_SELECTED_FORMATS = 2
        const val FIELD_BUFFERED_RANGES = 3
        const val FIELD_USTREAMER_CONFIG = 5
        const val FIELD_PREFERRED_AUDIO = 16
        const val FIELD_PREFERRED_VIDEO = 17
        const val STATE_PLAYER_TIME_MS = 28
        const val STATE_LAST_MANUAL_RESOLUTION = 16
        const val STATE_STICKY_RESOLUTION = 21
        const val FIELD_STREAMER_CONTEXT = 19
        const val CONTEXT_CLIENT_INFO = 1
        const val CONTEXT_PO_TOKEN = 2
        const val CONTEXT_PLAYBACK_COOKIE = 3
        const val CONTEXT_SABR_CONTEXTS = 5
        const val STATE_ENABLED_TRACKS = 40
        const val STATE_CLIENT_VIEWPORT_IS_FLEXIBLE = 22
        const val STATE_BANDWIDTH_ESTIMATE = 23
        const val STATE_PLAYBACK_RATE = 35
        const val STATE_DRC_ENABLED = 46
        const val STATE_MAX_NETWORK_INTERRUPTION_MS = 68
    }
}
