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
            if (UmpReader.read(response).parts.any { it.name == "SABR_ERROR" }) lastResponse = response
        }
    }

    /** What the server actually sent us, for a failure message that can be acted on. */
    private fun whatTheServerSent() =
        "server sent parts=$seen; refusal=${ResponseSummary.of(lastResponse)}"

    @Test
    fun `sabr delivers most of a long audio stream`() = runBlocking {
        val stream = audioStreamFor(LONG_VIDEO_ID)

        val declared = stream.contentLength
        assertNotNull("the stream did not declare a length, so there is nothing to measure", declared)

        val got = readUpTo(stream, declared!!)

        assertTrue(
            "SABR delivered ${got shr KB_SHIFT}KB of ${declared shr KB_SHIFT}KB " +
                "(${got * PERCENT / declared}%) — ${stream.describeProgress()} — ${whatTheServerSent()}",
            got * PERCENT / declared >= MIN_PERCENT,
        )
    }

    /**
     * Past the first megabyte specifically, because that is the exact shape of the 2026-08-18
     * failure: the first 1MB served fine and everything beyond it answered 403. A test that only
     * checked "we got bytes" saw a perfectly healthy first chunk and said nothing.
     */
    @Test
    fun `sabr reaches well past the first megabyte`() = runBlocking {
        val stream = audioStreamFor(LONG_VIDEO_ID)

        val got = readUpTo(stream, PAST_THE_CAP_BYTES)

        assertTrue(
            "only ${got shr KB_SHIFT}KB arrived — the 2026-08-18 failure served ~1MB and " +
                "refused everything after it. ${stream.describeProgress()} — ${whatTheServerSent()}",
            got > PAST_THE_CAP_BYTES,
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

        /** Most of it, not all: the tail of a SABR conversation is where an off-by-one would live. */
        const val MIN_PERCENT = 80

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
