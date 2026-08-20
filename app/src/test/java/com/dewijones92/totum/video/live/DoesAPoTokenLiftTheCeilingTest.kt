package com.dewijones92.totum.video.live

import com.dewijones92.totum.innertube.browse.InnerTubeClient
import com.dewijones92.totum.innertube.browse.InnerTubeResponse
import com.dewijones92.totum.innertube.player.HttpSignatureTimestampSource
import com.dewijones92.totum.innertube.player.PlayerResponseParser
import com.dewijones92.totum.innertube.player.PlayerResult
import com.dewijones92.totum.sabr.SabrClientInfo
import com.dewijones92.totum.sabr.SabrSession
import com.dewijones92.totum.sabr.SabrSessions
import com.dewijones92.totum.sabr.SabrStream
import com.dewijones92.totum.sabr.SabrTrackKind
import com.dewijones92.totum.sabr.SabrTransport
import com.dewijones92.totum.video.SabrResolve
import kotlinx.coroutines.runBlocking
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.util.Base64
import java.util.concurrent.TimeUnit

/**
 * Does a proof-of-origin token lift the ~1MB ceiling? One axis, two arms, one run.
 *
 * This is the question the whole SABR effort turns on. Measured without a token on `totum-api35`,
 * 2026-08-20: eighteen independent conversations, every one ending between 968840B and 990078B, after
 * which the server answered with the initialization segment and nothing else. At 1080p that is about
 * four seconds of video. Nothing about our byte bookkeeping or our Media3 seam can move it.
 *
 * **Skips unless a token is supplied**, so it never runs in CI and never spends CI's address on live
 * requests. Minting one needs a browser (BotGuard wants browser globals), so it cannot be done from
 * here — `tools/potoken/mint.mjs` does it and prints the token:
 *
 * ```
 * node tools/potoken/mint.mjs "<identifier>"
 * ./gradlew :app:test --tests '*DoesAPoTokenLiftTheCeilingTest*' \
 *     -DpoToken=<token> -DpoTokenBinding=<what it was minted against>
 * ```
 *
 * REPORTS rather than asserts what YouTube does, like its neighbours: the only assertion is our own
 * half — that the no-token arm really does hit a ceiling, without which the comparison means nothing.
 *
 * ⚠️ The binding matters and is the likeliest way to get a false negative. A token minted against the
 * wrong identifier is refused exactly like no token at all, which is indistinguishable from the wall.
 * The two references disagree because they use different clients: NewPipe's streaming token is minted
 * from `visitorData`, while SmartTube's `PoTokenGate` describes a CONTENT token minted from the
 * `videoId` as the one used in DASH/SABR requests. So the binding is a parameter here, not a guess.
 */
class DoesAPoTokenLiftTheCeilingTest {

    private val http = OkHttpClient.Builder()
        .callTimeout(CALL_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .build()

    private val transport = SabrTransport { url, body ->
        val request = Request.Builder().url(url).post(body.toRequestBody(PROTOBUF)).build()
        http.newCall(request).execute().use { it.body.bytes() }
    }

    @Test
    fun aTokenIsMeasuredAgainstTheCeiling() {
        val token = System.getProperty("poToken")
        assumeTrue(
            "no -DpoToken was supplied, so there is nothing to compare — see this test's KDoc",
            !token.isNullOrBlank(),
        )
        val binding = System.getProperty("poTokenBinding") ?: "unstated"

        // SEPARATE sessions, because the ceiling is a property of a conversation: running both arms
        // through one session would let the first spend the trial window the second is measuring.
        // Both arms ask the SAME client with the SAME visitorData, so the token is the only difference.
        // An earlier version varied the client too, which is how two arms measuring 956KB said nothing.
        val visitorData = System.getProperty("visitorData")
        val without = ceilingOf(freshSession(visitorData), null)
        val with = ceilingOf(freshSession(visitorData), Base64.getUrlDecoder().decode(token), token)

        val client = if (visitorData == null) "android" else "web+visitorData"
        println("[potoken] binding=$binding client=$client token=${token!!.length} chars")
        println("[potoken] WITHOUT a token: ${without / KB}KB served")
        println("[potoken] WITH    a token: ${with / KB}KB served")
        assertTrue(
            "the no-token arm served ${without / KB}KB and did not stop, so there is no ceiling here " +
                "to lift and this run says nothing about the token",
            without < CEILING_MUST_BE_BELOW,
        )
        println(
            if (with > without * LIFTED_BY) {
                "[potoken] ✅ THE TOKEN LIFTS THE CEILING — ${with / KB}KB against ${without / KB}KB. " +
                    "This is the unlock: wire minting into the app."
            } else {
                "[potoken] ✗ no better than without one (${with / KB}KB vs ${without / KB}KB). Either the " +
                    "binding is wrong — try the other identifier — or the token is not what is refused."
            },
        )
    }

    /**
     * Reads a stream to its end, and reports how far it got.
     *
     * The token can travel two ways and they are not the same experiment: inside the request as
     * `streamer_context.po_token`, which is where a SABR reference puts it, or on the URL as `pot=`,
     * which is where the reference implementations put a streaming token for a plain media fetch.
     * `-DpoTokenInUrl=true` picks the second.
     */
    private fun ceilingOf(session: SabrSession, poToken: ByteArray?, token: String? = null): Long = runBlocking {
        val audio = session.audio ?: return@runBlocking 0L
        val inUrl = System.getProperty("poTokenInUrl").toBoolean() && token != null
        val url = if (inUrl) {
            session.streamingUrl + (if ("?" in session.streamingUrl) "&" else "?") + "pot=" + token
        } else {
            session.streamingUrl
        }
        val stream = SabrStream(
            url = url,
            ustreamerConfig = session.ustreamerConfig,
            format = audio,
            kind = SabrTrackKind.AUDIO,
            transport = transport,
            totalBytes = audio.contentLength,
            durationMs = session.durationMs,
            poToken = poToken.takeUnless { inUrl },
            // Sent on BOTH arms, so it is not a second variable in the comparison.
            // Named after the client the ENDPOINT came from: a WEB endpoint told it is talking to
            // an Android client is a request that disagrees with itself.
            clientInfo = when (System.getProperty("clientInfo")) {
                "android" -> SabrClientInfo.ANDROID
                "web" -> SabrClientInfo.WEB
                else -> null
            },
        )
        var at = 0L
        repeat(READS_TO_EXHAUSTION) {
            val got = runCatching { stream.read(at) }.getOrDefault(ByteArray(0))
            if (got.isEmpty()) return@runBlocking at
            at += got.size
        }
        at
    }

    /**
     * A session from the WEB client, naming the visitorData the token was minted against.
     *
     * The visitorData is a PARAMETER because the token and the player request have to agree on it.
     * Passing `null` asks as the ordinary ANDROID client, which is what the app does today and what
     * the no-token arm should measure.
     */
    private fun freshSession(visitorData: String?): SabrSession {
        SabrSessions.clear()
        val client = InnerTubeClient(http)
        val response = runBlocking {
            if (visitorData == null) {
                client.player(VIDEO_ID)
            } else {
                val stamp = HttpSignatureTimestampSource(http).current()?.toLong() ?: 0L
                client.playerAsWeb(VIDEO_ID, visitorData, stamp, System.getProperty("playerPoToken") ?: "")
            }
        }
        val parsed = (response as? InnerTubeResponse.Success)?.body?.let(PlayerResponseParser::parse)
        assumeTrue("YouTube served no playable response for $VIDEO_ID — got $parsed", parsed is PlayerResult.Success)
        val success = parsed as PlayerResult.Success
        assumeTrue(
            "this response carried no SABR endpoint, so there is nothing to measure",
            SabrResolve.prepare(VIDEO_ID, success.streaming, success.details) != null,
        )
        return SabrSessions.of(VIDEO_ID)!!
    }

    private companion object {
        /** NASA's "Cosmic Dawn" — public domain, 97 minutes, so a megabyte is nowhere near its end. */
        const val VIDEO_ID = "uSMGENDH_QI"

        /** Enough reads to reach the ceiling several times over. */
        const val READS_TO_EXHAUSTION = 60

        /** Above this the arm never stopped, and there is no ceiling to compare against. */
        const val CEILING_MUST_BE_BELOW = 20L * 1024 * 1024

        /** How much better counts as lifted rather than noise. */
        const val LIFTED_BY = 3

        const val KB = 1024
        const val CALL_TIMEOUT_SECONDS = 60L
        val PROTOBUF = "application/x-protobuf".toMediaType()
    }
}
