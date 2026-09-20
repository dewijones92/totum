package com.dewijones92.totum.innertube.player

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * Where a video's watch-progress pings must be sent for YouTube to credit the account.
 *
 * These URLs are **identity-bearing**, which is the whole reason this type exists. The app
 * used to take them from yt-dlp's player response, and yt-dlp extracts unauthenticated — so
 * every ping went to an anonymous session's URL and credited nobody, while still answering
 * HTTP 204. Measured 2026-07-31: watching a video reported five successful pings and left the
 * account's history byte-identical.
 *
 * The same pings against a URL from an AUTHENTICATED `/player` call put the video at the top
 * of the account's history within twenty seconds — verified twice, on two videos, by reading
 * `FEhistory` back before and after. The authenticated response's URL differs by exactly one
 * parameter, `uga`, which is the signed-in signal.
 */
public data class PlaybackTracking(
    /** Opens the record. Absent on some responses, so pings still work without it. */
    public val playbackUrl: String?,
    /** Carries the position updates; without this there is nothing to report to. */
    public val watchtimeUrl: String,
)

/** Reads [PlaybackTracking] out of a `/player` response, or null when it carries none. */
public object PlaybackTrackingParser {

    private val json = Json { ignoreUnknownKeys = true }

    public fun parse(body: String): PlaybackTracking? {
        val root = runCatching { json.parseToJsonElement(body) as? JsonObject }.getOrNull() ?: return null
        val tracking = root["playbackTracking"] as? JsonObject ?: return null
        val watchtime = tracking.baseUrlAt("videostatsWatchtimeUrl") ?: return null
        return PlaybackTracking(tracking.baseUrlAt("videostatsPlaybackUrl"), watchtime)
    }

    /**
     * Why a response carries no tracking — and crucially **whose fault that is**.
     *
     * An InnerTube refusal is HTTP 200 with a `playabilityStatus`, so this is the only thing that
     * separates "this video" from "this app, right now":
     *
     * | status | verdict about | [Refusal] |
     * |---|---|---|
     * | `OK`, or absent | nothing is wrong; the video simply has no tracking | null |
     * | `ERROR` ("Video unavailable") | the video, permanently — deleted, private | [Refusal.ThisVideo] |
     * | anything else (`UNPLAYABLE`, `LOGIN_REQUIRED`, …) | possibly the client | [Refusal.MaybeUsAll] |
     *
     * The last row is the one that matters. A **stale signature timestamp** makes YouTube answer
     * `UNPLAYABLE — "The page needs to be reloaded"` for every video at once, and this repository
     * has had exactly that twice; reading it as a per-video verdict would let a backlog write
     * itself off. `ERROR` cannot mean that — a missing video is missing for one video only — so
     * treating it as a client fault instead made four deleted videos truncate every drain and put
     * "the sender is down" in a report that was wrong about it.
     *
     * Reads defensively: this is third-party JSON on a path with no `runCatching` above it, and
     * `jsonPrimitive` throws on an object where a string was expected.
     */
    public fun refusalReason(body: String): Refusal? {
        val root = runCatching { json.parseToJsonElement(body) as? JsonObject }.getOrNull() ?: return null
        val status = (root["playabilityStatus"] as? JsonObject) ?: return null
        val state = (status["status"] as? JsonPrimitive)?.contentOrNull ?: return null
        if (state == OK) return null
        val detail = (status["reason"] as? JsonPrimitive)?.contentOrNull
        val said = if (detail.isNullOrBlank()) state else "$state: $detail"
        return if (state in ABOUT_ONE_VIDEO) Refusal.ThisVideo(said) else Refusal.MaybeUsAll(said)
    }

    private const val OK: String = "OK"

    /**
     * Statuses that cannot possibly be about the client, so one video carrying one must not make
     * the whole queue look broken. Everything else stays ambiguous on purpose — `UNPLAYABLE` is
     * what a stale signature timestamp looks like, and `LOGIN_REQUIRED` is what a bot check does.
     */
    private val ABOUT_ONE_VIDEO = setOf("ERROR", "AGE_VERIFICATION_REQUIRED", "LIVE_STREAM_OFFLINE")

    private fun JsonObject.baseUrlAt(key: String): String? =
        ((this[key] as? JsonObject)?.get("baseUrl") as? JsonPrimitive)?.contentOrNull?.ifBlank { null }
}

/**
 * A `/player` response that refused, and how far the refusal reaches.
 *
 * The distinction decides whether one row is skipped or the whole pass gives up, so it is a type
 * rather than a string somebody has to remember to inspect.
 */
public sealed interface Refusal {
    /** What YouTube said, for a log line. */
    public val said: String

    /** True of this video alone — deleted, private. Nothing else in the queue is affected. */
    public data class ThisVideo(override val said: String) : Refusal

    /** Might be this whole client: a stale signature timestamp looks exactly like this. */
    public data class MaybeUsAll(override val said: String) : Refusal
}
