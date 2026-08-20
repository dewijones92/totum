package com.dewijones92.totum.video.live

import com.dewijones92.totum.innertube.browse.InnerTubeClient
import com.dewijones92.totum.innertube.browse.InnerTubeResponse
import com.dewijones92.totum.innertube.player.HttpSignatureTimestampSource
import com.dewijones92.totum.innertube.player.PlayerResponseParser
import com.dewijones92.totum.innertube.player.PlayerResult
import com.dewijones92.totum.sabr.ResponseSummary
import com.dewijones92.totum.sabr.SabrClientInfo
import com.dewijones92.totum.sabr.SabrFormat
import com.dewijones92.totum.sabr.SabrSession
import com.dewijones92.totum.sabr.SabrSessions
import com.dewijones92.totum.sabr.SabrStream
import com.dewijones92.totum.sabr.SabrTrackKind
import com.dewijones92.totum.sabr.SabrTracks
import com.dewijones92.totum.sabr.SabrTransport
import com.dewijones92.totum.sabr.UmpPart
import com.dewijones92.totum.sabr.UmpReader
import com.dewijones92.totum.sabr.VideoPlaybackAbrRequest
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

    /** The last HTTP status this transport saw, because a 0-byte body says nothing on its own. */
    private var lastStatus: String = "none"

    private val transport = SabrTransport { url, body ->
        val request = Request.Builder().url(url).post(body.toRequestBody(PROTOBUF)).build()
        http.newCall(request).execute().use {
            lastStatus = "HTTP ${it.code}"
            it.body.bytes()
        }
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
        val info = when (System.getProperty("clientInfo")) {
            "android" -> SabrClientInfo.ANDROID
            "web" -> SabrClientInfo.WEB
            else -> null
        }
        // Dumped BEFORE the ceiling runs, so a run that serves nothing still says why.
        println("[potoken] one response WITHOUT a token:")
        describeOneResponse(suppliedSession() ?: freshSession(visitorData), info, null)
        println("[potoken] one response WITH a token:")
        describeOneResponse(suppliedSession() ?: freshSession(visitorData), info, Base64.getUrlDecoder().decode(token))
        val without = ceilingOf(suppliedSession() ?: freshSession(visitorData), null)
        val with = ceilingOf(
            suppliedSession() ?: freshSession(visitorData),
            Base64.getUrlDecoder().decode(token),
            token
        )

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
     * Is the ceiling THEIRS, or ours for asking at the wrong time?
     *
     * `SabrStream` reacts to a response it kept nothing from by pushing its claimed position thirty
     * seconds forward. On the device that produced a stream claiming 147271ms while holding about 46
     * seconds of audio, and the responses that "proved" a wall were carrying the initialization
     * segment and nothing else -- which is exactly what a server answers when asked for a time it has
     * already covered. Four of those end the stream.
     *
     * So this asks PATIENTLY: never skip, always ask for the time the bytes we hold are worth, and see
     * how far it gets. If a patient reader passes the ~956KB every impatient one stops at, the ceiling
     * was never attestation -- it was a runaway of our own, and no token was ever going to fix it.
     */
    @Test
    fun aPatientReaderIsMeasuredAgainstTheSameCeiling() {
        val session = suppliedSession()
        assumeTrue("no -DsabrEndpoint supplied, so there is no session to be patient with", session != null)
        val audio = session!!.audio!!
        val total = audio.contentLength ?: 0L
        val duration = DURATION_MS

        var held = 0L
        var fetches = 0
        var quiet = 0
        while (fetches < PATIENT_FETCHES && quiet < QUIET_BEFORE_GIVING_UP) {
            // The time our bytes are ACTUALLY worth. No step, no skip, no floor.
            val askAt = if (total <= 0) 0L else held * duration / total
            val body = VideoPlaybackAbrRequest(
                ustreamerConfig = session.ustreamerConfig,
                playerTimeMs = askAt,
                audio = audio,
                tracks = SabrTracks.AUDIO_ONLY,
            ).encode()
            val response = runBlocking { transport.post(session.streamingUrl, body) }
            fetches++
            val media = UmpReader.read(response).parts.filter { it.type == UmpPart.MEDIA }.sumOf { it.payload.size }
            // The init segment arrives on every response; counting it as progress would look like
            // forward motion for ever.
            val fresh = media - INIT_SEGMENT_BYTES
            if (fresh <= 0) {
                quiet++
            } else {
                quiet = 0
                held += fresh
            }
            if (fetches % REPORT_EVERY == 0 || quiet > 0) {
                println(
                    "[patient] fetch $fetches asked ${askAt}ms -> ${response.size}B, " +
                        "media ${media}B, held ${held / KB}KB, quiet=$quiet",
                )
            }
        }
        println("[patient] stopped after $fetches fetches holding ${held / KB}KB of ${total / KB}KB")
        println(
            if (held > IMPATIENT_CEILING) {
                "[patient] ✅ PAST THE CEILING. The ~956KB wall was OURS — asking at the time our bytes " +
                    "are worth keeps the server serving. No token needed."
            } else {
                "[patient] ✗ stopped at ${held / KB}KB like every impatient reader, so the ceiling is theirs."
            },
        )
    }

    /**
     * What the endpoint ACTUALLY answers, part by part, for one request.
     *
     * Added because "0KB served" is a conclusion, not evidence: a refusal, a response carrying only
     * control parts, and media we fail to keep all produce it, and they need completely different
     * fixes. The WEB endpoint measured 0KB in every arm INCLUDING the one with no token, so it is not
     * a wall at all -- and nothing in the run said which of the three it was.
     */
    private fun describeOneResponse(session: SabrSession, clientInfo: SabrClientInfo?, poToken: ByteArray?) {
        val audio = session.audio ?: return
        val body = VideoPlaybackAbrRequest(
            ustreamerConfig = session.ustreamerConfig,
            playerTimeMs = 0,
            audio = audio,
            tracks = SabrTracks.AUDIO_ONLY,
            poToken = poToken,
            clientInfo = clientInfo,
        ).encode()
        val response = runBlocking { transport.post(session.streamingUrl, body) }
        val parts = UmpReader.read(response).parts
        // The URL's own parameters, because a 403 from googlevideo is usually about the URL rather
        // than the body: an `n` that has not been deciphered is the classic cause, and the WEB client
        // is the one that carries an `n` at all.
        val query = session.streamingUrl.substringAfter('?', "").split("&").map { it.substringBefore('=') }
        println("[potoken]   endpoint params: ${query.sorted().joinToString(",")}")
        println(
            "[potoken]   has n=${"n" in query} has pot=${"pot" in query} host=${session.streamingUrl.substringAfter(
                "//"
            ).substringBefore("/")}"
        )
        println("[potoken]   request ${body.size}B -> $lastStatus, response ${response.size}B")
        println("[potoken]   parts: " + parts.joinToString { "${it.name}(${it.payload.size}B)" })
        println("[potoken]   summary: ${ResponseSummary.of(response)}")
        ResponseSummary.refusalIn(response)?.let { println("[potoken]   REFUSAL: $it") }
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
    /**
     * A session handed in whole, for the path this cannot build itself.
     *
     * A WEB SABR endpoint carries an `n` that has to be deciphered before the server will answer at
     * all -- unsolved, it is HTTP 403 with a zero-byte body, which is what made the WEB rows of the
     * po-token table meaningless. Solving it needs a JavaScript runtime, which this JVM has none of
     * (the app uses QuickJS on the device). So `tools/potoken/websabr.py` fetches the response and
     * solves the `n` with node, and passes the finished endpoint in.
     */
    private fun suppliedSession(): SabrSession? {
        val endpoint = System.getProperty("sabrEndpoint") ?: return null
        val config = System.getProperty("ustreamerConfig") ?: return null
        val audio = (System.getProperty("sabrAudio") ?: return null).split(",")
        return SabrSession(
            streamingUrl = endpoint,
            ustreamerConfig = Base64.getDecoder().decode(config),
            audio = SabrFormat(
                itag = audio[0].toInt(),
                lastModified = audio[1].toLong(),
                xtags = audio[2].ifBlank { null },
                contentLength = audio[3].toLongOrNull(),
            ),
            video = null,
            durationMs = null,
        )
    }

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

        /** Generous: at ~330KB a response, a 97-minute audio track is a few hundred fetches. */
        const val PATIENT_FETCHES = 40
        const val QUIET_BEFORE_GIVING_UP = 4
        const val REPORT_EVERY = 5

        /** The init segment, re-sent on every response and not progress. */
        const val INIT_SEGMENT_BYTES = 10_620

        /** What every impatient reader has stopped at. */
        const val IMPATIENT_CEILING = 1_200L * 1024

        /** Cosmic Dawn's length, since a supplied session states none. */
        const val DURATION_MS = 5_805_000L
        val PROTOBUF = "application/x-protobuf".toMediaType()
    }
}
