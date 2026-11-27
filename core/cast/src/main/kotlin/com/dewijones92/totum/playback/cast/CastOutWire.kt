package com.dewijones92.totum.playback.cast

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
public data class CastOutStart(
    val mediaId: String,
    val uri: String,
    val separateAudioUrl: String? = null,
    val listening: Boolean = false,
    val skipSilence: Boolean? = null,
    val boost: String? = null,
    val durationMs: Long? = null,
    val model: String? = null,
)

@Serializable
public data class CastOutAnswer(
    val route: String,
    val url: String? = null,
    val sessionId: String? = null,
    val expiresAtMs: Long? = null,
    val why: String? = null,
) {
    public companion object {
        public const val PUBLIC: String = "public"
        public const val PHONE: String = "phone"

        public fun phone(why: String): CastOutAnswer = CastOutAnswer(PHONE, why = why)
    }
}

public object CastOutJson {
    private val json = Json { ignoreUnknownKeys = true }

    public fun start(text: String): CastOutStart? = runCatching {
        json.decodeFromString(CastOutStart.serializer(), text)
    }.getOrNull()

    public fun answer(text: String): CastOutAnswer? = runCatching {
        json.decodeFromString(CastOutAnswer.serializer(), text)
    }.getOrNull()

    public fun encode(start: CastOutStart): String = json.encodeToString(CastOutStart.serializer(), start)

    public fun encode(answer: CastOutAnswer): String = json.encodeToString(CastOutAnswer.serializer(), answer)
}

public object CastOutPaths {
    public const val SESSION: String = "/cast-out/session"
    public const val PUBLIC_PREFIX: String = "/cast-out/"
    public const val LAN_CHECK: String = "/lan-check"
    public const val LAN_CHECK_ANSWER: String = "totum-home"
    public const val LIFETIME_MS: Long = 6L * 60 * 60 * 1000
    public const val TOKEN_HEX_LENGTH: Int = 64
}
