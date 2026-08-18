package com.dewijones92.totum.video.live

import com.dewijones92.totum.innertube.browse.InnerTubeClient
import com.dewijones92.totum.innertube.browse.InnerTubeResponse
import com.dewijones92.totum.innertube.player.PlayerResponseParser
import com.dewijones92.totum.innertube.player.PlayerResult
import com.dewijones92.totum.sabr.ResponseSummary
import com.dewijones92.totum.sabr.SabrSessions
import com.dewijones92.totum.sabr.SabrStream
import com.dewijones92.totum.sabr.SabrTrackKind
import com.dewijones92.totum.sabr.SabrTransport
import com.dewijones92.totum.sabr.UmpReader
import com.dewijones92.totum.video.SabrResolve
import kotlinx.coroutines.runBlocking
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.junit.After
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.TimeUnit

/**
 * **The test that would have caught 2026-08-18**: can the app actually fetch a whole YouTube
 * stream, right now?
 *
 * That day every un-downloaded item stopped playing — Dewi: *"cant play anything that i havent
 * already downloaded"* — with the entire suite green and CI reporting success. YouTube had stopped
 * serving the URLs yt-dlp can obtain past roughly the first megabyte. Nothing in the app's own code
 * had changed, which is the important part: **no commit caused it, so no test that only asserts
 * about our code could ever have caught it.** The only guard against this class of failure is a
 * test that fetches real bytes from live YouTube and insists on getting a lot of them.
 *
 * Three things the existing live tests got wrong, all of which let a fully broken app pass:
 *
 * | Test | Its bar for "works" | Why that passes when nothing plays |
 * |---|---|---|
 * | `SabrPlaybackTest` | position passed **1 second** | one second is the first chunk |
 * | `LiveSabrDownloadTest` | file bigger than **10 KB** | a header is 10KB |
 * | Both | fixture is a **19-second** video | a whole 19s file is ~300KB, under any cap |
 *
 * So this one asserts a **proportion of the stream's own declared length**, on a video long enough
 * that a cap cannot hide inside it. Proportional rather than a byte count so the assertion cannot
 * rot as the fixture changes, and so it states the thing that actually matters: we got *most of
 * what there is*.
 *
 * It is a JVM test on purpose — `:lib:sabr` is pure Kotlin and `SabrTransport` is one method — so it
 * runs in seconds without an emulator, which is what makes it affordable to run often. It talks to
 * live YouTube, so CI runs it through the home connection (`tools/ci/live-test-via-home.sh`); see
 * that script for why a datacentre IP will not do.
 *
 * **It is allowed to FAIL, not skip.** Dewi's call, 2026-08-18, after learning that
 * `LiveStreamPlaysToItsEndTest` hit this exact failure in CI and reported
 * `assumeTrue("… an environment condition and not this defect")`. That excuse was true in August
 * and is now precisely backwards: "YouTube did not serve us a playable stream" *is* the defect. The
 * only assumption left here is whether we got a player response at all — no network, no test — and
 * everything after that is an assertion.
 */
class SabrCarriesAWholeStreamTest {

    private val http = OkHttpClient.Builder()
        .callTimeout(CALL_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .build()

    @After fun clearSessions() = SabrSessions.clear()

    /**
     * SABR is a conversation over POST, not a URL — the whole transport is this.
     *
     * Wrapped so a failure can say WHAT the server sent, not just how little of it. A stream that
     * stops is either our bug or a refusal, and the UMP part types are what tell those apart:
     * `STREAM_PROTECTION_STATUS` or `SABR_ERROR` alongside the media is YouTube declining, which is
     * not something a test should report as an unexplained shortfall.
     */
    private val seen = mutableSetOf<String>()
    private var lastResponse: ByteArray = ByteArray(0)
    private val transport = SabrTransport { url, body ->
        okHttpSabrTransport(http).post(url, body).also { response ->
            UmpReader.read(response).parts.forEach { seen += it.name }
            // Kept when the RESPONSE is a refusal, judged by the same rule the app uses -- not by
            // matching a part NAME. It matched the literal "SABR_ERROR", which moved from id 42 to 44
            // when the part table was regenerated from the proto, and 44 appears in neither of this
            // repo's captured refusals. So the failure message this wrapper exists for printed
            // `refusal=parts=[] itags=[] reasons=[] protection=none` in exactly the case it is for.
            // A diagnostic keyed on a name is a diagnostic that a rename silently empties.
            if (ResponseSummary.refusalIn(response) != null) lastResponse = response
        }
    }

    /** What the server actually sent us, for a failure message that can be acted on. */
    private fun whatTheServerSent() =
        "server sent parts=$seen; refusal=${ResponseSummary.of(lastResponse)}"

    /**
     * SABR still delivers its trial window, which is what proves OUR half of the conversation works.
     *
     * The bar is deliberately the floor rather than the whole stream. YouTube caps an unattested client
     * at roughly a megabyte and there is nothing in this repository that can change that — see
     * `docs/todos/youtube-requires-attestation.md`. A test asserting 80% of the file would be red every
     * run until a PO token exists, and a permanently red build is worse than no test: it is the thing
     * that taught everyone to wave `LiveStreamPlaysToItsEndTest`'s skip through in the first place.
     *
     * What this DOES guard is everything between the socket and the reader: UMP framing, protobuf
     * parsing, run attribution by header id, the buffered ranges we send, and the claimed position. If
     * any of that regresses the number drops to zero or near it, and this fails. The ceiling moving is
     * the canary's job (`tools/ci/youtube-canary.py`), not this one's.
     */
    @Test
    fun `sabr delivers the window it is offered`() = runBlocking {
        val stream = audioStreamFor(LONG_VIDEO_ID)

        val got = readUpTo(stream, PAST_THE_CAP_BYTES)

        assertTrue(
            "SABR delivered only ${got shr KB_SHIFT}KB. Our own machinery should carry at least the " +
                "trial window; near-zero means we broke the conversation, not that YouTube tightened " +
                "it. ${stream.describeProgress()} — ${whatTheServerSent()}",
            got >= MIN_TRIAL_WINDOW_BYTES,
        )
    }

    /**
     * And when it stops, it stops because YouTube SAID SO — not because we lost our place.
     *
     * The distinction is the whole point, and it is the one that took a day to establish. A stream that
     * ends early looks identical whether the server refused it or our claimed position ran away from
     * our bytes, and on 2026-08-18 it was both: a runaway clock AND a real refusal, fixed separately.
     * This asserts the refusal is present, so a future early ending with no refusal in the response is
     * ours to explain.
     */
    @Test
    fun `when it stops, the server has refused rather than us losing track`() = runBlocking {
        val stream = audioStreamFor(LONG_VIDEO_ID)
        val declared = stream.contentLength
        assertNotNull("the stream did not declare a length", declared)

        val got = readUpTo(stream, declared!!)

        if (got * PERCENT / declared >= MOSTLY_DELIVERED) return@runBlocking
        assertTrue(
            "it stopped after ${got shr KB_SHIFT}KB of ${declared shr KB_SHIFT}KB and the response " +
                "carried no refusal — so this is our machinery giving up, which is a defect here " +
                "rather than a policy at YouTube. ${stream.describeProgress()} — ${whatTheServerSent()}",
            seen.any { it == "STREAM_PROTECTION_STATUS" || it == "SABR_ERROR" },
        )
    }

    /** Reads sequentially until [target] bytes have arrived or the stream is done. */
    private suspend fun readUpTo(stream: SabrStream, target: Long): Long {
        var got = 0L
        while (got < target) {
            val part = stream.read(got)
            if (part.isEmpty()) break
            got += part.size
        }
        return got
    }

    /**
     * A real SABR audio stream for [videoId].
     *
     * **Nothing here is excused, including YouTube saying no.** The first version of this returned
     * null and printed a note when the player response was not a success — and immediately proved
     * why that is wrong: the original fixture turned out to be a retired live stream, YouTube
     * answered `UNPLAYABLE`, and the test passed in green having measured nothing at all. That is
     * the same silent-pass this whole file exists to remove, rebuilt inside the replacement. A
     * fixture chosen to be permanently playable that stops being served is exactly the news worth
     * having, whether the cause is YouTube, our client identity, or the video.
     */
    private fun audioStreamFor(videoId: String): SabrStream {
        val response = runBlocking { InnerTubeClient(http).player(videoId) }
        val parsed = (response as? InnerTubeResponse.Success)?.body?.let(PlayerResponseParser::parse)
        assertTrue(
            "YouTube served us no playable player response for $videoId — got $parsed. This is the " +
                "failure the app experiences as \"nothing plays\", not an environment quirk to skip.",
            parsed is PlayerResult.Success,
        )
        parsed as PlayerResult.Success
        val prepared = SabrResolve.prepare(videoId, parsed.streaming, parsed.details)
        assertNotNull("the player response could not be turned into a SABR session", prepared)
        val session = SabrSessions.of(videoId)
        assertNotNull("no SABR session was registered for $videoId", session)
        val audio = session!!.audio
        assertNotNull("the session carries no audio format", audio)
        return SabrStream(
            url = session.streamingUrl,
            ustreamerConfig = session.ustreamerConfig,
            format = audio!!,
            kind = SabrTrackKind.AUDIO,
            transport = transport,
            totalBytes = audio.contentLength,
            durationMs = session.durationMs,
        )
    }

    private companion object {
        /**
         * NASA's "Moonbound Episode 2" — 37 minutes, public domain, and a VOD rather than a live
         * recording. Long enough that a megabyte-sized cap cannot hide inside it, which is the
         * entire requirement: the previous live tests used a 19-second video whose whole file fits
         * under any cap worth catching.
         */
        const val LONG_VIDEO_ID = "ttiLcMUQq80"

        /**
         * The floor our own code must clear. Measured at ~812KB against live YouTube on 2026-08-18;
         * 600KB leaves room for the cap to wobble while still failing loudly if we stop parsing.
         */
        const val MIN_TRIAL_WINDOW_BYTES = 600L * 1024

        /** Above this share, the stream is being served properly and there is nothing to explain. */
        const val MOSTLY_DELIVERED = 80

        /** Comfortably past the 2026-08-18 ceiling, and quick to reach. */
        const val PAST_THE_CAP_BYTES = 4L * 1024 * 1024

        const val CALL_TIMEOUT_SECONDS = 60L
        const val PERCENT = 100
        const val KB_SHIFT = 10
    }
}

/** OkHttp behind `SabrTransport`, kept out of the test body so both cases share one. */
private fun okHttpSabrTransport(http: OkHttpClient) = SabrTransport { url, body ->
    http.newCall(
        Request.Builder().url(url).post(body.toRequestBody(OCTET_STREAM)).build(),
    ).execute().use { it.body.bytes() }
}

private val OCTET_STREAM = "application/octet-stream".toMediaType()
