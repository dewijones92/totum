package com.dewijones92.totum.video.live

import androidx.test.platform.app.InstrumentationRegistry
import com.dewijones92.totum.TotumApplication
import com.dewijones92.totum.innertube.auth.AccessTokenResult
import com.dewijones92.totum.innertube.browse.InnerTubeClient
import com.dewijones92.totum.innertube.browse.InnerTubeResponse
import com.dewijones92.totum.innertube.player.HttpSignatureTimestampSource
import com.dewijones92.totum.innertube.player.PlayerResponseParser
import com.dewijones92.totum.innertube.player.PlayerResult
import com.dewijones92.totum.innertube.player.StreamingData
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Does signing in change what YouTube is willing to serve? Measured, on one video, three ways.
 *
 * `docs/todos/youtube-requires-attestation.md` calls this its "most promising lead, unverified", and
 * says in as many words: **test it on a signed-in device before building anything on it**. It could not
 * be tested when it was written because no device here was signed in. One now is.
 *
 * The comparison is deliberately ONE axis: the same video, the same moment, the same broadband, asked
 * anonymously and then as the account. Permuting several things at once is how this investigation
 * produced two confidently wrong theories already.
 *
 * REPORTS rather than asserts what YouTube does — the repository has gone red six times for asserting
 * someone else's policy, and a test that fails because YouTube changed its mind teaches everyone to
 * ignore red. The only assertion is our own half: that we could ask at all, signed in, with a token.
 * What YouTube answered is printed for a human to read.
 */
class SignedInVersusAnonymousPlayerTest {

    private val app = InstrumentationRegistry.getInstrumentation()
        .targetContext.applicationContext as TotumApplication
    private val http = OkHttpClient()
    private val client = InnerTubeClient(http)

    private data class Verdict(
        val label: String,
        val ok: Boolean,
        val videoFormats: Int,
        val durable: Int,
        val tallest: Int,
        val deepFetch: String,
    )

    @Test
    fun signedInAndAnonymousComparedOnOneVideo() = runBlocking {
        val account = app.container.youTubeAccount
        val token = (account.accessToken() as? AccessTokenResult.Available)?.token
        assertTrue(
            "this device is not signed in, so the comparison cannot be made — run " +
                "SignInOnThisDeviceTest first (see CLAUDE.md)",
            token != null,
        )
        val stamp = HttpSignatureTimestampSource(http).current()
        println("[signin-vs-anon] signature timestamp = $stamp")

        val verdicts = listOf(
            judge("anonymous") { client.player(VIDEO_ID) },
            judge("signed-in TV") { client.playerAsAccount(VIDEO_ID, stamp ?: 0, token!!) },
            judge("signed-in downgraded TV") { client.playerDowngradedTv(VIDEO_ID, stamp ?: 0, token!!) },
        )
        println("[signin-vs-anon] video $VIDEO_ID")
        verdicts.forEach {
            println(
                "[signin-vs-anon]   ${it.label.padEnd(24)} ok=${it.ok} videoFormats=${it.videoFormats} " +
                    "durable=${it.durable} tallest=${it.tallest}p deepFetch=${it.deepFetch}",
            )
        }
        println("[signin-vs-anon] durable = the URL carries a solved n, so it survives past ~1MB")
    }

    private suspend fun judge(label: String, request: suspend () -> InnerTubeResponse): Verdict {
        val response = runCatching { request() }.getOrNull()
            ?: return Verdict(label, ok = false, videoFormats = 0, durable = 0, tallest = 0, deepFetch = "not asked")
        // The response is an envelope, not a body: Unauthorized and Failure are answers too, and reading
        // them as "no streams" would hide a 401 behind the same word as an empty success.
        val body = (response as? InnerTubeResponse.Success)?.body
            ?: return Verdict(label, ok = false, videoFormats = 0, durable = 0, tallest = 0, deepFetch = "$response")
        val streams = (PlayerResponseParser.parse(body) as? PlayerResult.Success)?.streaming
            ?: return Verdict(label, ok = false, videoFormats = 0, durable = 0, tallest = 0, deepFetch = "no streams")
        val video = streams.videoFormatsWithUrls()
        val durable = video.filter { DURABLE.containsMatchIn(it.second) }
        val best = durable.maxByOrNull { it.first } ?: video.maxByOrNull { it.first }
        return Verdict(
            label = label,
            ok = true,
            videoFormats = video.size,
            durable = durable.size,
            tallest = best?.first ?: 0,
            deepFetch = best?.let { deepFetch(it.second) } ?: "nothing to fetch",
        )
    }

    /** Height to URL, for every format that has both. */
    private fun StreamingData.videoFormatsWithUrls(): List<Pair<Int, String>> =
        directlyPlayable.mapNotNull { format ->
            val height = format.height ?: return@mapNotNull null
            val url = format.url?.value ?: return@mapNotNull null
            if (format.mimeType?.startsWith("video/") == true) height to url else null
        }

    /**
     * The wall itself: a range well past the first megabyte. This is the measurement that matters —
     * a URL that exists and a URL that serves are different things, and only the second plays a video.
     */
    private fun deepFetch(url: String): String = runCatching {
        http.newCall(
            Request.Builder().url(url).header("Range", "bytes=$DEEP_OFFSET-${DEEP_OFFSET + PROBE_BYTES}").build(),
        ).execute().use { "HTTP ${it.code} (${it.body?.bytes()?.size ?: 0}B)" }
    }.getOrElse { "threw ${it::class.simpleName}" }

    private companion object {
        /** NASA's "Cosmic Dawn" — public domain, and long enough that an 8MB offset is real content. */
        const val VIDEO_ID = "uSMGENDH_QI"
        const val DEEP_OFFSET = 8_000_000L
        const val PROBE_BYTES = 102_399L
        val DURABLE = Regex("""[?&]n=[^&]+""")
    }
}
