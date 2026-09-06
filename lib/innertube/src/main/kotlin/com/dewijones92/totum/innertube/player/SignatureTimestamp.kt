package com.dewijones92.totum.innertube.player

import com.dewijones92.totum.common.Diag
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException

/**
 * YouTube's current signature timestamp — the version number of the player JavaScript that
 * a client claims to be running.
 *
 * This one integer is the difference between a `/player` call that works and one that does
 * not. An authenticated TV request without it is refused with "The page needs to be
 * reloaded", and so is one carrying a *stale* value: measured 2026-07-31, a timestamp four
 * releases old was rejected exactly like none at all, while the current one returned OK and
 * an account-bearing tracking URL. It was the missing piece behind the whole "watch history
 * never reaches the account" bug — not the bearer token, which was correct all along.
 */
public fun interface SignatureTimestampSource {
    /** Null when it cannot be determined; the caller then skips the request that needs it. */
    public suspend fun current(): SignatureTimestamp?
}

/**
 * The one number, on both scales YouTube speaks — so a TV request cannot be handed the web one.
 *
 * Every player script carries `signatureTimestamp:20697`, and that is what a WEB client declares. A TV
 * client must declare **20697001**: since about 2026-08-18 the same request with 20697 is refused
 * `UNPLAYABLE — "The page needs to be reloaded"`, which sat behind three weeks of dead progress sync
 * and every "TV /player is refused" probe. Found by capturing SmartTube on the emulator
 * (2026-09-06) and varying one field at a time; SmartTube's own source says the same
 * (`QueryBuilder.kt`: *"Web and TV timestamps now differs. TV one should have 001 suffix"*).
 */
@JvmInline
public value class SignatureTimestamp(public val web: Int) {
    public val tv: Int get() = web * TV_SCALE + TV_SUFFIX

    override fun toString(): String = "$web (tv $tv)"

    public companion object {
        private const val TV_SCALE = 1000
        private const val TV_SUFFIX = 1
    }
}

/**
 * Reads the timestamp out of YouTube's own player JavaScript.
 *
 * Two fetches — `iframe_api` names the current player build, and the build's script carries
 * the number — then cached for the process's lifetime. YouTube ships a new player perhaps
 * weekly, so refetching per video would be thousands of pointless requests; a value that
 * goes stale mid-session costs one failed sync and is corrected at next launch.
 */
public class HttpSignatureTimestampSource(
    private val client: OkHttpClient,
    private val iframeApiUrl: String = IFRAME_API_URL,
    private val playerScriptUrl: (build: String) -> String = ::tvPlayerScriptUrl,
) : SignatureTimestampSource {

    private val lock = Mutex()
    private var cached: SignatureTimestamp? = null
    private var cachedBuild: String? = null

    override suspend fun current(): SignatureTimestamp? = lock.withLock {
        cached ?: fetch()?.also {
            cached = it
            Diag.log("yt-sync", "player signature timestamp is $it")
        }
    }

    /**
     * The classic player script for the build [current] found, which is what an [NSolver] runs.
     *
     * Shares the build discovery rather than repeating it — the same `iframe_api` fetch answers
     * both questions, and doing it twice would mean the timestamp and the script could come
     * from different builds, which is precisely the mismatch YouTube rejects.
     *
     * Deliberately the `base.js` player, not the TV one [current] reads: the classic script is
     * the one proven to carry a solvable `n` function (2026-08-01). The build id is shared; the
     * script is not.
     */
    public suspend fun playerScriptUrl(): String? {
        current()
        // Null here silently disables `n` solving, which in turn silently makes every stream
        // URL 403 — so it says so. It happens when [current] never reached a player: the
        // timestamp is cached for the process, so a first fetch failing offline leaves this
        // null for the whole session even once the network returns.
        return lock.withLock { cachedBuild?.let(::basePlayerScriptUrl) }
            ?: null.also { Diag.warn("yt-sync", "no player build known yet; n parameters cannot be solved") }
    }

    private suspend fun fetch(): SignatureTimestamp? {
        val iframe = get(iframeApiUrl) ?: return null
        val build = PLAYER_BUILD.find(iframe)?.groupValues?.get(1) ?: run {
            Diag.warn("yt-sync", "iframe_api named no player build; watch history cannot sync")
            return null
        }
        cachedBuild = build
        val script = get(playerScriptUrl(build)) ?: return null
        return TIMESTAMP.find(script)?.groupValues?.get(1)?.toIntOrNull()?.let(::SignatureTimestamp)
            ?: null.also { Diag.warn("yt-sync", "player $build carried no signatureTimestamp") }
    }

    private suspend fun get(url: String): String? = withContext(Dispatchers.IO) {
        try {
            client.newCall(Request.Builder().url(url).get().build()).execute().use { response ->
                if (response.isSuccessful) {
                    response.body.string()
                } else {
                    Diag.warn("yt-sync", "$url -> HTTP ${response.code}")
                    null
                }
            }
        } catch (e: IOException) {
            Diag.warn("yt-sync", "$url could not be fetched", e)
            null
        }
    }

    public companion object {
        public const val IFRAME_API_URL: String = "https://www.youtube.com/iframe_api"

        /** The TV player, to match the client the tracking request impersonates. */
        public fun tvPlayerScriptUrl(build: String): String =
            "https://www.youtube.com/s/player/$build/tv-player-ias.vflset/tv-player-ias.js"

        /** The classic web player, which is the script an `n` solver is proven to work against. */
        public fun basePlayerScriptUrl(build: String): String =
            "https://www.youtube.com/s/player/$build/player_ias.vflset/en_US/base.js"

        private val PLAYER_BUILD = Regex("""player\\?/([0-9a-fA-F]{8})\\?/""")
        private val TIMESTAMP = Regex("""signatureTimestamp[=:](\d+)""")
    }
}
