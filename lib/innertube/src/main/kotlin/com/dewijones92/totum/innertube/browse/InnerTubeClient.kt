package com.dewijones92.totum.innertube.browse

import com.dewijones92.totum.innertube.auth.AccessToken
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonPrimitive
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException

/**
 * Minimal client for YouTube's private InnerTube API. Two shapes:
 *
 * - [browse] — authenticated, impersonating the living-room TV app (the same
 *   client our device-code OAuth authenticates as; the WEB client rejects a TV
 *   token, verified against YouTube). Serves every account feed, first page or
 *   later, depending on its [BrowseTarget].
 * - [next] — the watch-page endpoint used unauthenticated with the WEB client
 *   for public data like comments (no token needed, and the WEB comment format
 *   is far simpler than the TV one).
 *
 * Both go through one [execute] so the HTTP + error mapping lives in one place.
 */
// The count is InnerTube's endpoint surface — browse, next, search, player, the write
// actions — plus three small body builders. Splitting it would scatter the one place that
// knows how to talk to InnerTube, which is the point of the class.
@Suppress("TooManyFunctions")
public class InnerTubeClient(
    private val client: OkHttpClient,
    private val browseUrl: String = BROWSE_URL,
    private val nextUrl: String = NEXT_URL,
    private val searchUrl: String = SEARCH_URL,
    private val playerUrl: String = PLAYER_URL,
    private val tvClientVersion: String = TV_CLIENT_VERSION,
    private val webClientVersion: String = WEB_CLIENT_VERSION,
    private val androidClientVersion: String = ANDROID_CLIENT_VERSION,
    private val musicSearchUrl: String = MUSIC_SEARCH_URL,
    private val musicClientVersion: String = MUSIC_CLIENT_VERSION,
    /**
     * The account's token, for every request whose client can carry one — see [Identity].
     *
     * The "global middleware" Dewi asked for on 2026-08-11, in the one place every request already
     * passes through. Null (the default) leaves everything anonymous, which is what tests and a
     * signed-out app want.
     */
    private val accountToken: suspend () -> AccessToken? = { null },
) {

    public suspend fun browse(target: BrowseTarget, accessToken: AccessToken): InnerTubeResponse =
        execute(browseUrl, tvContext(target.fields()), Identity.TV, accessToken)

    /**
     * A video's streaming data, as the ANDROID client.
     *
     * That client specifically, because it is the only one YouTube still serves playable
     * streams to for restricted content — measured across all twelve of yt-dlp's clients on
     * 2026-07-30. It is also the response that carries `serverAbrStreamingUrl`, which is how
     * the formats WITHOUT a plain URL are fetched (see docs/todos/sabr-streaming.md).
     *
     * Unauthenticated: the TV client refuses with "Sign in to confirm you're not a bot"
     * unless it can present a full session, which a bearer token alone is not.
     */
    public suspend fun player(videoId: String): InnerTubeResponse =
        execute(playerUrl, androidContext(videoId), Identity.ANDROID)

    /**
     * The player response as the SIGNED-IN account, which is the only way to reach an
     * age-restricted video.
     *
     * Measured from report 0.1.289: three items failed with *"Sign in to confirm your age… rated
     * 15… use --cookies"*. yt-dlp has no credentials and cannot be given any here, but the app
     * already holds a YouTube account for the TV device-code flow, and YouTube will serve a
     * rated video to a signed-in adult.
     *
     * It does NOT help with members-only videos, which failed in the same report with "join this
     * channel to get access". That is a genuine paywall rather than a missing credential, and no
     * token this app can hold will open it.
     *
     * Identical on the wire to [playerTracking] — same endpoint, same TV context, same
     * `racyCheckOk` — so this is a second NAME rather than a second request. Kept separate
     * because the two callers want different halves of one response, and a method called
     * "tracking" being used to fetch streams would mislead every reader after this one.
     */
    public suspend fun playerAsAccount(
        videoId: String,
        signatureTimestamp: Int,
        accessToken: AccessToken,
    ): InnerTubeResponse = playerTracking(videoId, signatureTimestamp, accessToken)

    /**
     * The player response as the **DOWNGRADED** TV client, signed in — the age-restricted path.
     *
     * The client VERSION is the whole trick, and it was measured rather than guessed
     * (2026-08-01, against a rated video with a control alongside it). The same request at the
     * current [tvClientVersion] comes back SABR-only — one fetchable URL out of seven. At
     * [TV_DOWNGRADED_VERSION] it comes back with **all seven carrying plain URLs and no SABR**.
     * SmartTube keeps exactly this client and tries it BEFORE the current one; this is why.
     *
     * Authenticated, and only a TV client may be: SmartTube's own `isAuthSupported` is the five
     * TV identities and nothing else, which is what the `HTTP 400` from every bearer-plus-
     * ANDROID/VR/embedded attempt was telling us.
     *
     * The URLs still carry an obfuscated `n` and 403 until it is solved — see
     * [com.dewijones92.totum.innertube.player.withSolvedN]. Resolving is not enough on its own.
     */
    public suspend fun playerDowngradedTv(
        videoId: String,
        signatureTimestamp: Int,
        accessToken: AccessToken,
    ): InnerTubeResponse = execute(
        playerUrl,
        """{"context":{"client":{"clientName":"TVHTML5",""" +
            """"clientVersion":"$TV_DOWNGRADED_VERSION","hl":"en","gl":"GB"}},""" +
            """"videoId":"$videoId","contentCheckOk":true,"racyCheckOk":true,""" +
            """"playbackContext":{"contentPlaybackContext":""" +
            """{"html5Preference":"HTML5_PREF_WANTS","signatureTimestamp":$signatureTimestamp}}}""",
        Identity.TV,
        accessToken,
        clientHeaders = mapOf(
            "X-Youtube-Client-Name" to TV_CLIENT_ID,
            "X-Youtube-Client-Version" to TV_DOWNGRADED_VERSION,
            "User-Agent" to TV_DOWNGRADED_USER_AGENT,
        ),
    )

    /**
     * A video's **playback-tracking** URLs, as the signed-in TV client.
     *
     * A separate call from [player] on purpose: this one is authenticated and returns no
     * fetchable stream URLs at all (the TV client is SABR-only — 27 formats, none with a
     * url, measured 2026-07-31), while [player] is anonymous and exists purely for streams.
     * One request cannot be both, and the app needs both.
     *
     * [signatureTimestamp] is not optional in practice. Without it — or with a stale value —
     * YouTube answers UNPLAYABLE "The page needs to be reloaded" even with a valid token,
     * which is why the app's watch-history sync silently credited nobody for so long.
     */
    public suspend fun playerTracking(
        videoId: String,
        signatureTimestamp: Int,
        accessToken: AccessToken,
    ): InnerTubeResponse =
        execute(
            playerUrl,
            tvContext(
                """ "videoId":"$videoId","contentCheckOk":true,"racyCheckOk":true,""" +
                    """"playbackContext":{"contentPlaybackContext":""" +
                    """{"html5Preference":"HTML5_PREF_WANTS","signatureTimestamp":$signatureTimestamp}} """,
            ),
            Identity.TV,
            accessToken,
        )

    /** Watch-page data for a video (WEB client, no auth). */
    public suspend fun next(videoId: String): InnerTubeResponse =
        execute(nextUrl, webContext(""" "videoId":"$videoId" """), Identity.WEB)

    /** Browses public content (WEB client, no auth) — e.g. a channel's tabs. */
    public suspend fun browseWeb(target: BrowseTarget): InnerTubeResponse =
        execute(browseUrl, webContext(target.fields()), Identity.WEB)

    /**
     * Public video search (WEB client, no auth). The WEB response carries each
     * result's upload date, which yt-dlp's flat `ytsearch` does not.
     */
    public suspend fun search(target: SearchTarget): InnerTubeResponse =
        execute(searchUrl, webContext(target.fields()), Identity.WEB)

    /**
     * Public search, but AS THE ACCOUNT, so the query joins your search history and feeds the
     * recommendations that follow from it.
     *
     * The TV client, because only the TV identities accept a bearer token — a bearer with an
     * ANDROID or WEB context is answered `HTTP 400`, InnerTube cross-checking the declared client
     * against the headers. Same lesson `playerDowngradedTv` already carries.
     *
     * Whether the TV client answers search with renderers this app can parse is not something we
     * can know from here, so nothing depends on it: the caller falls back to the anonymous WEB
     * search when this returns nothing usable, and logs which one answered.
     */
    public suspend fun searchAsAccount(target: SearchTarget, accessToken: AccessToken): InnerTubeResponse =
        execute(
            searchUrl,
            tvContext(target.fields()),
            Identity.TV,
            accessToken,
            clientHeaders = mapOf(
                "X-Youtube-Client-Name" to TV_CLIENT_ID,
                "X-Youtube-Client-Version" to tvClientVersion,
            ),
        )

    /**
     * Song search on YouTube Music: the same InnerTube API, as the `WEB_REMIX` client.
     *
     * The songs FILTER is what makes this worth having. Verified live 2026-08-11: unfiltered, the
     * music endpoint answers with a mixed bag — 4 videos, 3 albums, 3 artists, 3 playlists, 3
     * podcasts and only 5 songs — while the filter returns twenty songs each with an artist, an
     * album and an exact duration. A continuation carries the filter forward itself, so it is only
     * sent with a fresh query.
     */
    public suspend fun searchMusic(target: SearchTarget): InnerTubeResponse {
        val fields = when (target) {
            is SearchTarget.Query -> target.fields() + ", \"params\":\"$MUSIC_SONGS_FILTER\""
            is SearchTarget.Continuation -> target.fields()
        }
        return execute(musicSearchUrl, musicContext(fields), Identity.MUSIC, clientHeaders = MUSIC_HEADERS)
    }

    /** Follows a continuation token (e.g. loading comments; WEB client, no auth). */
    public suspend fun nextContinuation(continuation: String): InnerTubeResponse =
        execute(nextUrl, webContext(""" "continuation":"$continuation" """), Identity.WEB)

    /**
     * Authenticated write action (like, subscribe, comment, …) as the TV
     * client. [fieldsJson] is the request body minus the context (e.g.
     * `"target":{"videoId":"…"}`).
     */
    public suspend fun action(url: String, fieldsJson: String, accessToken: AccessToken): InnerTubeResponse =
        execute(url, tvContext(fieldsJson), Identity.TV, accessToken)

    private fun tvContext(fields: String): String =
        """{"context":{"client":{"clientName":"TVHTML5","clientVersion":"$tvClientVersion"}},$fields}"""

    private fun androidContext(videoId: String): String =
        clientContext(
            """"clientName":"ANDROID","clientVersion":"$androidClientVersion","androidSdkVersion":34,"hl":"en"""",
            """"videoId":"$videoId","contentCheckOk":true,"racyCheckOk":true""",
        )

    private fun musicContext(fields: String): String =
        clientContext(
            "\"clientName\":\"WEB_REMIX\",\"clientVersion\":\"$musicClientVersion\",\"hl\":\"en\",\"gl\":\"GB\"",
            fields,
        )

    private fun webContext(field: String): String =
        clientContext(""""clientName":"WEB","clientVersion":"$webClientVersion"""", field)

    private fun clientContext(client: String, fields: String): String =
        """{"context":{"client":{$client}},$fields}"""

    /**
     * One place attaches the token, and only where the client can take one — see [Identity].
     *
     * [bearer] is an explicit token for the calls that already hold one (they fetch it themselves,
     * often alongside a signature timestamp). Where it is null and the identity is a TV client, the
     * account's own token is used if there is one, which is what makes a new TV call authenticated
     * without anybody remembering to make it so.
     */
    private suspend fun execute(
        url: String,
        jsonBody: String,
        identity: Identity,
        bearer: AccessToken? = null,
        /**
         * Client identity as HEADERS, not just in the body.
         *
         * Measured 2026-08-01: `ANDROID_VR` answers `LOGIN_REQUIRED` anonymously — the one
         * client that asks to be identified rather than refusing outright — but a plain
         * `Authorization: Bearer` gets HTTP 400. InnerTube cross-checks the declared client
         * against `X-YouTube-Client-Name`/`-Version` and the user agent, and rejects an
         * authenticated request whose headers do not agree with its body.
         */
        clientHeaders: Map<String, String> = emptyMap(),
    ): InnerTubeResponse {
        // The rule, applied once: a token only where the declared client will accept one.
        val token = bearer ?: if (identity.acceptsBearer) accountToken() else null
        return withContext(Dispatchers.IO) {
            val builder = Request.Builder()
                .url(url)
                .addHeader("Content-Type", "application/json")
                .post(jsonBody.toRequestBody(JSON))
            clientHeaders.forEach { (name, value) -> builder.addHeader(name, value) }
            if (token != null) builder.addHeader("Authorization", "Bearer ${token.value}")
            try {
                client.newCall(builder.build()).execute().use { response ->
                    val body = response.body.string()
                    when {
                        response.isSuccessful && body.isNotBlank() -> InnerTubeResponse.Success(body)
                        response.code == HTTP_UNAUTHORIZED || response.code == HTTP_FORBIDDEN ->
                            InnerTubeResponse.Unauthorized
                        else -> InnerTubeResponse.Failure("HTTP ${response.code}")
                    }
                }
            } catch (e: IOException) {
                InnerTubeResponse.Failure(e.message ?: "network error")
            }
        }
    }

    public companion object {
        private const val BASE: String = "https://www.youtube.com/youtubei/v1"
        public const val BROWSE_URL: String = "$BASE/browse?prettyPrint=false"
        public const val NEXT_URL: String = "$BASE/next?prettyPrint=false"
        public const val SEARCH_URL: String = "$BASE/search?prettyPrint=false"
        public const val PLAYER_URL: String = "$BASE/player?prettyPrint=false"

        /**
         * YouTube Music has its own host, and it matters: the `WEB_REMIX` client is only served
         * music renderers there.
         */
        public const val MUSIC_SEARCH_URL: String = "https://music.youtube.com/youtubei/v1/search?prettyPrint=false"
        public const val MUSIC_CLIENT_VERSION: String = "1.20240101.01.00"

        /**
         * "Songs only", as YouTube Music's own filter chip sends it.
         *
         * Opaque protobuf, so it is pinned rather than constructed, and verified live rather than
         * copied: sent with the query it returns twenty songs, and omitted it returns a mixed bag
         * that is mostly not music (see [searchMusic]).
         */
        internal const val MUSIC_SONGS_FILTER: String = "EgWKAQIIAWoKEAoQCRADEAQQBQ%3D%3D"

        /**
         * InnerTube cross-checks the declared client against these headers and rejects a request
         * whose body and headers disagree — the lesson already paid for with `ANDROID_VR`.
         */
        private val MUSIC_HEADERS: Map<String, String> = mapOf(
            "X-Youtube-Client-Name" to "67",
            "X-Youtube-Client-Version" to MUSIC_CLIENT_VERSION,
        )

        /** Matches yt-dlp's android client; YouTube rejects a stale one. */
        public const val ANDROID_CLIENT_VERSION: String = "20.10.38"
        public const val LIKE_URL: String = "$BASE/like/like?prettyPrint=false"
        public const val DISLIKE_URL: String = "$BASE/like/dislike?prettyPrint=false"
        public const val REMOVE_LIKE_URL: String = "$BASE/like/removelike?prettyPrint=false"
        public const val SUBSCRIBE_URL: String = "$BASE/subscription/subscribe?prettyPrint=false"
        public const val UNSUBSCRIBE_URL: String = "$BASE/subscription/unsubscribe?prettyPrint=false"
        public const val CREATE_COMMENT_URL: String = "$BASE/comment/create_comment?prettyPrint=false"
        public const val EDIT_PLAYLIST_URL: String = "$BASE/browse/edit_playlist?prettyPrint=false"
        public const val TV_CLIENT_VERSION: String = "7.20240401.10.00"

        /** InnerTube's numeric id for TVHTML5, which must agree with the declared client. */
        public const val TV_CLIENT_ID: String = "7"

        /**
         * The TV client version that still hands out plain URLs instead of SABR.
         *
         * Not a stale constant left behind — the OLD version is the point, and raising it to
         * something current would silently reintroduce the SABR-only response this exists to
         * avoid. SmartTube pins the same idea as its `TV_DOWNGRADED` client.
         */
        public const val TV_DOWNGRADED_VERSION: String = "5.20260707"

        /** The stripped Cobalt agent that goes with [TV_DOWNGRADED_VERSION]. */
        public const val TV_DOWNGRADED_USER_AGENT: String =
            "Mozilla/5.0 (ChromiumStylePlatform) Cobalt/Version"
        public const val WEB_CLIENT_VERSION: String = "2.20240726.00.00"
        private const val HTTP_UNAUTHORIZED = 401
        private const val HTTP_FORBIDDEN = 403
        private val JSON = "application/json".toMediaType()
    }
}

/**
 * What to browse: a feed/channel id, or a continuation token for a later page. A sealed
 * pair rather than two nullable parameters, because sending both is meaningless — a
 * continuation already encodes what it continues — and this makes that unrepresentable.
 */
public sealed interface BrowseTarget {
    /**
     * [params] selects a channel tab (Videos/Shorts/Playlists); omit for the default.
     * [query] is only meaningful with the channel's Search tab params, which is the one
     * tab that takes an argument — searching within a channel is a browse, not a search.
     */
    public data class Id(
        public val browseId: String,
        public val params: String? = null,
        public val query: String? = null,
    ) : BrowseTarget

    public data class Continuation(public val token: String) : BrowseTarget
}

/**
 * What a search request asks for: a query, or the next page of one. Modelled exactly like
 * [BrowseTarget] and for the same reason — a continuation already encodes what it
 * continues, so a query alongside it would be meaningless.
 */
/**
 * Which YouTube client a request declares itself as — and therefore whether it may carry the
 * account's token.
 *
 * **Only the TV identities accept a bearer.** A bearer with an ANDROID, WEB or WEB_REMIX context is
 * answered `HTTP 400`: InnerTube cross-checks the declared client against the
 * `X-YouTube-Client-Name`/`-Version` headers and the user agent, and refuses an authenticated
 * request whose headers and body disagree. That was measured twice the hard way — once on
 * `ANDROID_VR`, once while making search attributed — so the rule lives here rather than in the
 * memory of whoever adds the next endpoint.
 *
 * Dewi, 2026-08-11: *"make sure we have as much as possible auth requests to YouTube. Maybe some
 * global middleware"*. This is that: [InnerTubeClient.execute] attaches the token to every request
 * whose client can take one, so a new TV-client call is authenticated by default and a new WEB one
 * cannot accidentally be.
 */
internal enum class Identity(val acceptsBearer: Boolean) {
    /** The living-room app. The only family YouTube will authenticate. */
    TV(acceptsBearer = true),

    /** Public web endpoints — comments, channel tabs, the anonymous search. */
    WEB(acceptsBearer = false),

    /** The streams client. Refuses a bearer outright; use the TV player calls instead. */
    ANDROID(acceptsBearer = false),

    /** YouTube Music. A web client, so the same refusal applies. */
    MUSIC(acceptsBearer = false),
}

public sealed interface SearchTarget {
    public data class Query(public val text: String) : SearchTarget
    public data class Continuation(public val token: String) : SearchTarget
}

/**
 * The request-body fields that select this target. A query is arbitrary user text, so it
 * is JSON-encoded rather than interpolated; a token is YouTube's own opaque string.
 */
internal fun SearchTarget.fields(): String = when (this) {
    is SearchTarget.Query -> " \"query\":" + JsonPrimitive(text)
    is SearchTarget.Continuation -> """ "continuation":"$token" """
}

/** The request-body fields that select this target. */
internal fun BrowseTarget.fields(): String = when (this) {
    is BrowseTarget.Id -> buildString {
        append(""" "browseId":"$browseId" """)
        if (params != null) append(""", "params":"$params" """)
        // Arbitrary user text, so encoded rather than interpolated.
        if (query != null) append(", \"query\":" + JsonPrimitive(query))
    }
    is BrowseTarget.Continuation -> """ "continuation":"$token" """
}

/** Result of an InnerTube POST (browse or next). */
public sealed interface InnerTubeResponse {
    public data class Success(val body: String) : InnerTubeResponse

    /** The token was rejected — treat as signed out. */
    public data object Unauthorized : InnerTubeResponse

    public data class Failure(val detail: String) : InnerTubeResponse
}
