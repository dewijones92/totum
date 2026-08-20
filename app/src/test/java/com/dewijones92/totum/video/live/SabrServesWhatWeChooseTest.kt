package com.dewijones92.totum.video.live

import com.dewijones92.totum.innertube.browse.InnerTubeClient
import com.dewijones92.totum.innertube.browse.InnerTubeResponse
import com.dewijones92.totum.innertube.player.PlayableFormat
import com.dewijones92.totum.innertube.player.PlayerResponseParser
import com.dewijones92.totum.innertube.player.PlayerResult
import com.dewijones92.totum.sabr.SabrFormat
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
import java.util.concurrent.TimeUnit

/**
 * Does SABR still serve the formats `SabrResolve` is willing to choose?
 *
 * `SabrResolve` carries three caps — mp4 only, 30fps only, 1080p or below — and each one is a
 * measurement of YouTube's behaviour taken on **2026-07-31**, written into a `const`. A measurement
 * in a constant is a fact with no expiry date on it, and the two ways that goes wrong are opposites:
 *
 * * **It gets stricter.** A format we still pick stops being served, and playback breaks for a
 *   reason no unit test can see, because our picker is behaving exactly as designed.
 * * **It gets looser.** 60fps or 2160p start serving, and we carry on refusing them forever —
 *   Dewi watches a 4K60 upload at 1080p30 with nothing anywhere saying why. That is the shape of
 *   the "works great in smarttube" gap.
 *
 * So this test **asserts only the first**, which is ours: every format our own picker chooses must
 * actually deliver bytes. What YouTube permits beyond that is *reported*, never asserted — a test
 * that failed when YouTube RELAXED a restriction would be red for good news, which is the same
 * mistake as asserting someone else's policy and one this repo has already made three times.
 *
 * The printed line is the deliverable as much as the green tick: it is the only place that says
 * what quality YouTube would serve today versus what we ask for.
 */
class SabrServesWhatWeChooseTest {

    private val http = OkHttpClient.Builder()
        .callTimeout(CALL_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .build()

    private val transport = SabrTransport { url, body ->
        val request = Request.Builder()
            .url(url)
            .post(body.toRequestBody(PROTOBUF))
            .build()
        http.newCall(request).execute().use { it.body.bytes() }
    }

    @Test
    fun everyFormatWeWouldChooseActuallyServes() = runBlocking {
        val parsed = playerResponse(FOUR_K_SIXTY)
        val prepared = SabrResolve.prepare(FOUR_K_SIXTY, parsed.streaming, parsed.details)
        assertTrue(
            "SabrResolve refused to build a session for a public-domain 4K60 video. The reasons are " +
                "logged by SabrResolve.refuse; if YouTube has stopped offering a SABR endpoint at all " +
                "then this is the news, not a skip.",
            prepared != null,
        )
        val session = SabrSessions.of(FOUR_K_SIXTY)!!

        val audio = served(session, session.audio!!, SabrTrackKind.AUDIO)
        val video = session.video?.let { served(session, it, SabrTrackKind.VIDEO) } ?: 0L

        println("[sabr] our own picks: audio ${audio / KB}KB, video ${video / KB}KB")
        println("[sabr] ${wouldYouTubeAllowMore(parsed.streaming.formats, session)}")

        assertTrue("SABR served no audio for a format our own picker chose", audio > ENOUGH_BYTES)
        assertTrue(
            "SABR served no video for a format our own picker chose. Our caps let it through and " +
                "YouTube then refused it, so the caps in SabrResolve no longer match reality.",
            session.video == null || video > ENOUGH_BYTES,
        )
    }

    /**
     * Can SABR be opened PART-WAY through, live?
     *
     * This is the one limitation that keeps SABR out of the app's ordinary path: it is offered only
     * within the first ten seconds of an item, because a mid-item open used to ask for `player_time_ms
     * = 0`, receive the start of the file, discard every byte as already-passed, and kill the track.
     * `SabrStream.aimAtByte` now translates the byte offset into a media time.
     *
     * **Asserts ours, reports theirs.** What we own is the REQUEST: it must ask for the media time that
     * corresponds to the byte offset. Whether YouTube then serves a cold mid-stream position is its
     * decision, and measured 2026-08-18 it does not — it answers four ~1KB control responses carrying no
     * media at all:
     *
     * ```
     * mediaTime=407499ms  ← the aim is right, halfway through an ~815s stream
     * fetches=4 served=0B discarded=8152B (100% wasted) segments=0[]
     * ```
     *
     * So `aimAtByte` is **necessary but not sufficient**, and this test records that honestly rather than
     * going red for an unimplemented capability. What the wire measurement does NOT establish is *what*
     * was refused: the target is past the ~1MB attestation ceiling, and `SABR_SEEK` is server→client so
     * it cannot be the missing request part. See `docs/todos/sabr-cannot-seek.md`.
     */
    @Test
    fun sabrCanBeOpenedPartWayThrough() = runBlocking {
        val parsed = playerResponse(FOUR_K_SIXTY)
        assertTrue(
            "SabrResolve refused to build a session; its reasons are logged by SabrResolve.refuse",
            SabrResolve.prepare(FOUR_K_SIXTY, parsed.streaming, parsed.details) != null,
        )
        val session = SabrSessions.of(FOUR_K_SIXTY)!!
        val audio = session.audio!!
        val length = audio.contentLength
        assumeTrue("this format did not state a length, so there is no offset to aim at", length != null)

        val target = length!! / 2
        val sizes = mutableListOf<Int>()
        val watched = SabrTransport { url, body -> transport.post(url, body).also { sizes += it.size } }
        val watchedStream = SabrStream(
            url = session.streamingUrl,
            ustreamerConfig = session.ustreamerConfig,
            format = audio,
            kind = SabrTrackKind.AUDIO,
            transport = watched,
            totalBytes = length,
            durationMs = session.durationMs,
        )
        val got = runCatching { watchedStream.read(target) }.getOrDefault(ByteArray(0))

        // The RESPONSE sizes as well as the answer, because "the server sent nothing" and "the server
        // sent plenty but not at the byte we asked for" are completely different problems and the empty
        // return value alone cannot tell them apart.
        println(
            "[sabr] opened ${target}B into a ${length}B stream and got ${got.size / KB}KB; " +
                "server responses were ${sizes.map { it / KB }}KB",
        )
        val progress = watchedStream.describeProgress()
        println("[sabr] $progress")

        // OURS: the request asked for the right point in the media, not the start.
        val askedMs = ASKED_TIME.find(progress)?.groupValues?.get(1)?.toLongOrNull() ?: 0
        val expectedMs = (session.durationMs ?: 0) / 2
        assertTrue(
            "the stream asked for ${askedMs}ms when opening halfway into a ${length}B stream. It should " +
                "aim near ${expectedMs}ms; asking for the start is the units bug aimAtByte exists to fix.",
            askedMs > expectedMs / 2,
        )
        // THEIRS: whether YouTube serves it. Reported, because a red build for a capability we have not
        // built teaches everyone to ignore red.
        println(
            if (got.isEmpty()) {
                "[sabr] YouTube served NO media for a cold open at ${target}B — SABR remains " +
                    "start-of-item only. See docs/todos/sabr-cannot-seek.md."
            } else {
                "[sabr] SABR SERVED A COLD MID-STREAM OPEN (${got.size / KB}KB) — seeking may now be " +
                    "possible; revisit the 10s window in StreamRecovery."
            },
        )
    }

    /**
     * Does a jump work once the conversation is ESTABLISHED?
     *
     * The hypothesis from `docs/todos/sabr-cannot-seek.md`: a COLD stream has no playback cookie and no
     * prior buffered ranges, so YouTube may only permit a seek inside a conversation it already
     * recognises. This plays from the start for a few reads and only then jumps.
     *
     * ⚠️ **A NEGATIVE RESULT HERE MEANS NOTHING, and this probe once published one anyway.** Three
     * reasons, all ours:
     *
     *  * it judges at the READER (`jumped.isEmpty()`), downstream of every reader defect, unlike the
     *    cold arm above which wraps the transport and can therefore say what arrived on the wire;
     *  * it asks for `length / 2`, an arbitrary mid-segment byte, while `SabrStream.read` needs
     *    `chunks[from]` to exist EXACTLY and SABR answers from a segment boundary — so a perfectly
     *    served jump still comes back empty;
     *  * the target is past the ~1MB attestation ceiling measured on eighteen streams
     *    (`docs/todos/sabr-stops-at-one-megabyte.md`), so a refused seek and a refused megabyte look
     *    identical.
     *
     * A sound version wraps the transport in BOTH arms, aims at a real `MEDIA_HEADER` sequence boundary
     * captured from the warm reads (never `contentLength / 2`), and stays inside the first megabyte.
     * Until then this prints what happened and claims nothing.
     */
    @Test
    fun aJumpInsideAnEstablishedConversation() = runBlocking {
        val parsed = playerResponse(FOUR_K_SIXTY)
        assumeTrue(
            "no SABR session could be built, so there is nothing to probe",
            SabrResolve.prepare(FOUR_K_SIXTY, parsed.streaming, parsed.details) != null,
        )
        val session = SabrSessions.of(FOUR_K_SIXTY)!!
        val audio = session.audio!!
        val length = audio.contentLength
        assumeTrue("this format stated no length, so there is no offset to aim at", length != null)

        val stream = SabrStream(
            url = session.streamingUrl,
            ustreamerConfig = session.ustreamerConfig,
            format = audio,
            kind = SabrTrackKind.AUDIO,
            transport = transport,
            totalBytes = length,
            durationMs = session.durationMs,
        )
        var at = 0L
        var warmed = 0L
        repeat(WARM_READS) {
            val chunk = runCatching { stream.read(at) }.getOrDefault(ByteArray(0))
            warmed += chunk.size
            at += chunk.size
        }
        println("[sabr] warmed the conversation with ${warmed / KB}KB over $WARM_READS reads, now at ${at}B")
        assertTrue("sequential reading from the start must work — that is ours", warmed > ENOUGH_BYTES)

        val target = length!! / 2
        val jumped = runCatching { stream.read(target) }.getOrDefault(ByteArray(0))
        println("[sabr] then jumped to ${target}B and got ${jumped.size / KB}KB")
        println("[sabr] ${stream.describeProgress()}")
        println(
            if (jumped.isEmpty()) {
                "[sabr] a warm jump returned nothing AT THE READER, which settles nothing: this probe " +
                    "cannot tell a refused seek from one we could not key, and its target is past the " +
                    "~1MB ceiling. Session continuity stays OPEN — see docs/todos/sabr-cannot-seek.md."
            } else {
                "[sabr] A WARM JUMP SERVED ${jumped.size / KB}KB. That direction IS conclusive: " +
                    "establishing the conversation first reaches media a cold jump does not. Act on it."
            },
        )
    }

    /**
     * What YouTube would serve beyond our caps — printed, never asserted.
     *
     * Probes one excluded format of each kind rather than all of them: the point is to notice a
     * relaxation, and one 60fps format serving is enough to say "go and look". Probing the whole
     * ladder would cost a minute of live requests to tell us the same thing.
     */
    private suspend fun wouldYouTubeAllowMore(formats: List<PlayableFormat>, session: SabrSession): String {
        val excluded = formats.filter { it.lastModified != null && it.height != null }
            .filter { (it.fps ?: 0) > CAPPED_FPS || (it.height ?: 0) > CAPPED_HEIGHT }
            .filter { it.mimeType?.contains("mp4") == true && it.mimeType?.contains("mp4a") != true }
        val probe = excluded.maxByOrNull { it.height ?: 0 } ?: return "YouTube offered nothing above our caps"
        val format = SabrFormat(probe.itag, probe.lastModified!!, probe.xtags, probe.contentLength)
        val got = served(session, format, SabrTrackKind.VIDEO)
        val label = "itag ${probe.itag} ${probe.height}p${probe.fps ?: ""}"
        return if (got > ENOUGH_BYTES) {
            "$RELAXED $label served ${got / KB}KB — SabrResolve still refuses it, so we are " +
                "throwing quality away. Re-measure the caps (they date from 2026-07-31)."
        } else {
            "confirmed still refused: $label served ${got / KB}KB. Our caps remain correct."
        }
    }

    private suspend fun served(session: SabrSession, format: SabrFormat, kind: SabrTrackKind): Long {
        val stream = SabrStream(
            url = session.streamingUrl,
            ustreamerConfig = session.ustreamerConfig,
            format = format,
            kind = kind,
            transport = transport,
            totalBytes = format.contentLength,
            durationMs = session.durationMs,
        )
        // Reads from the START, and asks for more than the first response can hold: an empty first
        // response is normal (the server sends config before media), so a one-shot probe would report
        // every format as refused.
        return runCatching { stream.read(0).size.toLong() }.getOrDefault(0L)
    }

    private fun playerResponse(videoId: String): PlayerResult.Success {
        val response = runBlocking { InnerTubeClient(http).player(videoId) }
        val parsed = (response as? InnerTubeResponse.Success)?.body?.let(PlayerResponseParser::parse)
        assertTrue(
            "YouTube served no playable player response for $videoId — got $parsed. That is the " +
                "failure the app experiences as \"nothing plays\".",
            parsed is PlayerResult.Success,
        )
        return parsed as PlayerResult.Success
    }

    private companion object {
        /**
         * Blender's "Big Buck Bunny" — Creative Commons, 4K at 60fps, and permanently up. It has to
         * be 4K60 or the interesting formats are not in the response to probe.
         */
        const val FOUR_K_SIXTY = "aqz-KE-bpKQ"

        /** Mirrors `SabrResolve`'s own caps. Duplicated deliberately: this test must fail if they drift. */
        const val CAPPED_FPS = 30
        const val CAPPED_HEIGHT = 1080

        const val RELAXED = "SABR HAS RELAXED:"
        const val ENOUGH_BYTES = 10L * 1024
        const val KB = 1024
        const val CALL_TIMEOUT_SECONDS = 60L

        /** Enough reads to build a real conversation before jumping. */
        const val WARM_READS = 4

        /** Pulls `mediaTime=NNNms` out of the stream's own progress line. */
        val ASKED_TIME = Regex("""mediaTime=(\d+)ms""")

        val PROTOBUF = "application/x-protobuf".toMediaType()
    }
}
