package com.dewijones92.totum.common

/**
 * A proof-of-origin token: YouTube's attestation that a request comes from a real client.
 *
 * Without one, a stream is served a trial window of roughly a megabyte and then refused. Measured on
 * `totum-api35`, 2026-08-20, across eighteen independent SABR conversations: every one ended between
 * 968840B and 990078B, after which the server answered with the initialization segment and nothing
 * else. At 1080p that ceiling is about four seconds of video, which is why no amount of work on our
 * byte bookkeeping or our Media3 seam could lift it. See `docs/todos/sabr-stops-at-one-megabyte.md`.
 *
 * Redacted in [toString] for the same reason the OAuth tokens are: a credential that reaches a log
 * reaches a diagnostics report, and these are sent off the device.
 *
 * Lives in `:lib:common` because THREE call sites need the same token and must not disagree about it:
 * the SABR request's `streamer_context`, yt-dlp's `po_token` extractor argument, and the `pot` query
 * parameter on a direct URL.
 */
@JvmInline
public value class PoToken(public val value: String) {
    override fun toString(): String = "PoToken(redacted, ${value.length} chars)"
}

/**
 * What a token is bound TO, which YouTube treats as two different things.
 *
 * The distinction is not cosmetic: a token minted for the wrong binding is refused exactly like no
 * token at all, which is indistinguishable from the wall it is meant to lift.
 */
public sealed interface PoTokenBinding {
    /** Bound to one video, and what a media request (DASH/SABR) has to carry. */
    public data class Content(public val videoId: String) : PoTokenBinding

    /** Bound to the session's `visitorData`, and what a player request has to carry. */
    public data class Session(public val visitorData: String) : PoTokenBinding
}

/**
 * Mints proof-of-origin tokens, or admits it cannot.
 *
 * A port rather than a class because minting needs a JavaScript runtime with browser globals — a
 * WebView on Android — and neither `:lib:sabr` nor `:lib:common` may depend on Android. The same
 * reason `:lib:ytdlp` is pure and `:lib:ytdlp-chaquopy` holds the engine.
 *
 * Returning null rather than throwing: an app with no token still works, it simply stops after the
 * trial window, and the recovery ladder falls back to extraction. Treating "no token" as a failure
 * would turn a degraded path into a broken one.
 */
public fun interface PoTokenSource {
    public suspend fun tokenFor(binding: PoTokenBinding): PoToken?
}

/** A source that never mints anything, for tests and for a build with no WebView available. */
public object NoPoTokens : PoTokenSource {
    override suspend fun tokenFor(binding: PoTokenBinding): PoToken? = null
}
