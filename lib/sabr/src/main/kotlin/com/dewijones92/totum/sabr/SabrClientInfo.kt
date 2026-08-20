package com.dewijones92.totum.sabr

/**
 * Who we are, as `StreamerContext.client_info` — field 1 inside field 19 of the request.
 *
 * Field numbers are from `LuanRT/googlevideo`'s `streamer_context.proto`, read rather than guessed.
 * Only the identity fields are here; the schema also carries screen sizes, a chipset string and GL
 * renderer details, and none of them are ours to invent.
 *
 * Sent because a request that names no client may be one the server declines to serve. It is a
 * CANDIDATE, not a proven fix: on 2026-08-20 a token in `po_token` alone changed nothing on the
 * ANDROID endpoint (956KB with and without) and the WEB endpoint served nothing either way, which is
 * the shape of a request being rejected rather than a stream being walled.
 */
public data class SabrClientInfo(
    /** InnerTube's numeric client id — 1 is WEB, 3 is ANDROID, 7 is TVHTML5, 28 is ANDROID_VR. */
    public val clientName: Int,
    public val clientVersion: String,
    public val osName: String? = null,
    public val osVersion: String? = null,
    public val androidSdkVersion: Int? = null,
) {
    internal fun encode(): ByteArray {
        var out = Protobuf.number(CLIENT_NAME, clientName.toLong()) +
            Protobuf.bytes(CLIENT_VERSION, clientVersion.encodeToByteArray())
        osName?.let { out += Protobuf.bytes(OS_NAME, it.encodeToByteArray()) }
        osVersion?.let { out += Protobuf.bytes(OS_VERSION, it.encodeToByteArray()) }
        androidSdkVersion?.let { out += Protobuf.number(ANDROID_SDK_VERSION, it.toLong()) }
        return out
    }

    public companion object {
        /** What `InnerTubeClient.player` presents, which is the client our SABR endpoint comes from. */
        public val ANDROID: SabrClientInfo = SabrClientInfo(
            clientName = ANDROID_CLIENT_NAME,
            clientVersion = "19.09.37",
            osName = "Android",
            osVersion = "14",
            androidSdkVersion = 34,
        )

        /** What `InnerTubeClient.playerAsWeb` presents. The client the references attest as. */
        public val WEB: SabrClientInfo = SabrClientInfo(
            clientName = WEB_CLIENT_NAME,
            clientVersion = "2.20240726.00.00",
            osName = "Windows",
            osVersion = "10.0",
        )

        private const val ANDROID_CLIENT_NAME = 3
        private const val WEB_CLIENT_NAME = 1
        private const val CLIENT_NAME = 16
        private const val CLIENT_VERSION = 17
        private const val OS_NAME = 18
        private const val OS_VERSION = 19
        private const val ANDROID_SDK_VERSION = 64
    }
}
