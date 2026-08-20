package com.dewijones92.totum.innertube.player

import com.dewijones92.totum.common.Diag
import com.dewijones92.totum.common.HttpUrl

/**
 * Deobfuscates YouTube's `n` throttling parameter.
 *
 * Every stream URL from a signed-in TV player response carries one, and the URL returns 403
 * until it is transformed — measured 2026-08-01, including with the parameter stripped
 * entirely, which fails the same way. Solving it means running a function out of YouTube's
 * player JavaScript, so this is a port rather than an implementation: the JS engine is a
 * platform concern and does not belong in a pure-JVM library.
 *
 * The app satisfies it with the QuickJS it already bundles for yt-dlp. See
 * docs/todos/age-restricted-videos.md.
 */
public fun interface NSolver {
    /**
     * Maps each obfuscated parameter to its solved form.
     *
     * Unsolvable ones are ABSENT rather than passed through unchanged. That distinction is
     * the whole reason this returns a map: NewPipe's solver returns the input on failure,
     * which is indistinguishable from success and yields a URL that 403s at playback time
     * instead of an error at resolve time.
     */
    public suspend fun solve(challenges: List<String>, playerUrl: String): Map<String, String>
}

/**
 * The same streams with playable URLs, or fewer streams.
 *
 * A format whose `n` could not be solved is DROPPED, because keeping it would offer the
 * player a URL that is certain to 403 — and a missing quality is a far better failure than a
 * stall part-way through. Formats carrying no `n` at all pass through untouched.
 */
public suspend fun StreamingData.withSolvedN(solver: NSolver, playerUrl: String): StreamingData {
    // The SABR ENDPOINT counts too, and for a long time it did not. This walked `formats` and left
    // `serverAbrStreamingUrl` exactly as it arrived -- harmless on the ANDROID client, whose URLs
    // carry no `n`, and fatal on WEB, whose endpoint does. Measured 2026-08-20: a WEB endpoint with
    // `n` still obfuscated answered HTTP 403 with a zero-byte body, which had been read for hours as
    // a proof-of-origin token failing to help. The request never reached a server willing to look.
    val challenges = (formats.mapNotNull { it.url?.nParameter() } + listOfNotNull(serverAbrStreamingUrl?.nParameter()))
        .distinct()
    if (challenges.isEmpty()) return this

    val solved = runCatching { solver.solve(challenges, playerUrl) }.getOrElse { failure ->
        Diag.warn("resolve", "could not solve ${challenges.size} n parameter(s)", failure)
        emptyMap()
    }

    val playable = formats.mapNotNull { format ->
        val obfuscated = format.url?.nParameter() ?: return@mapNotNull format
        val answer = solved[obfuscated] ?: return@mapNotNull null
        format.copy(url = format.url?.withN(answer))
    }
    // Said out loud with both numbers: "8 formats" and "8 formats, 3 playable" are different
    // situations that otherwise produce an identical-looking resolve.
    Diag.log(
        "resolve",
        "solved ${solved.size}/${challenges.size} n parameter(s) — " +
            "${playable.size} of ${formats.size} format(s) playable",
    )
    // An endpoint whose `n` will not solve is left ALONE rather than dropped. A format that cannot be
    // solved is dropped, because a 403 URL is worse than a missing quality -- but an endpoint is not a
    // quality, and dropping it would take the whole SABR path away on one failed solve when the caller
    // can still refuse it by name and fall back.
    val endpoint = serverAbrStreamingUrl?.let { url ->
        url.nParameter()?.let { solved[it] }?.let(url::withN) ?: url
    }
    return copy(formats = playable, serverAbrStreamingUrl = endpoint)
}

/** The value of the `n` query parameter, or null when the URL carries none. */
internal fun HttpUrl.nParameter(): String? = N_PARAMETER.find(value)?.groupValues?.get(2)

/** The same URL with its `n` parameter replaced. */
internal fun HttpUrl.withN(solved: String): HttpUrl? =
    HttpUrl.parse(N_PARAMETER.replace(value) { match -> match.groupValues[1] + "n=" + solved })

/**
 * Matched rather than parsed: these URLs are long, already percent-encoded, and round-tripping
 * them through a URI builder risks re-encoding something the signature covers.
 */
private val N_PARAMETER = Regex("""([?&])n=([^&]*)""")
