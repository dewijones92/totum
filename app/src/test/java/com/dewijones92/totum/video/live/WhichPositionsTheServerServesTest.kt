package com.dewijones92.totum.video.live

import com.dewijones92.totum.sabr.SabrClientInfo
import com.dewijones92.totum.sabr.SabrFormat
import com.dewijones92.totum.sabr.SabrSegments
import com.dewijones92.totum.sabr.SabrTrackKind
import com.dewijones92.totum.sabr.SabrTransport
import kotlinx.coroutines.runBlocking
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.util.Base64
import java.util.concurrent.TimeUnit

/**
 * WHICH requested positions does the server serve, once it has stopped serving?
 *
 * Fifteen fields and behaviours have been varied without moving a ceiling that sits at exactly
 * 60001ms of media — tokens, client info, cookie, contexts, selected formats, buffered ranges,
 * positions from three different sources, pacing at two margins, and ExoPlayer's buffer policy by
 * duration and by bytes. The server states `endSegment=581`, so the media plainly exists, and it
 * refuses with no error and the same protection status as a success.
 *
 * So stop guessing fields and MAP THE FUNCTION. Everything is held fixed and only the requested
 * position varies, which is the one input the protocol says the answer depends on. If some position
 * yields segment seven, the rule is discoverable; if none does, the limit is not about position at all
 * and every position-shaped theory — including the readahead one — is dead.
 *
 * Reports; asserts nothing about YouTube.
 */
class WhichPositionsTheServerServesTest {

    private val http = OkHttpClient.Builder().callTimeout(TIMEOUT_S, TimeUnit.SECONDS).build()

    private val transport = SabrTransport { url, body ->
        val request = Request.Builder().url(url).post(body.toRequestBody(PROTOBUF)).build()
        http.newCall(request).execute().use { it.body.bytes() }
    }

    @Test
    fun positionsAreSweptOnceTheServerHasStopped() {
        val endpoint = System.getProperty("sabrEndpoint")
        val config = System.getProperty("ustreamerConfig")
        val spec = System.getProperty("sabrAudio")
        assumeTrue(
            "needs -DsabrEndpoint, -DustreamerConfig, -DsabrAudio — see tools/potoken/websabr.py",
            !endpoint.isNullOrBlank() && !config.isNullOrBlank() && !spec.isNullOrBlank(),
        )
        val audio = spec!!.split(",")
        val segments = SabrSegments(
            url = endpoint!!,
            ustreamerConfig = Base64.getDecoder().decode(config),
            format = SabrFormat(
                itag = audio[0].toInt(),
                lastModified = audio[1].toLong(),
                xtags = audio[2].ifBlank { null },
                contentLength = audio[3].toLongOrNull(),
            ),
            kind = SabrTrackKind.AUDIO,
            transport = transport,
            clientInfo = SabrClientInfo.WEB,
        )

        runBlocking {
            // Fill to the ceiling first, the ordinary way.
            var sequence = -1
            repeat(FILL_CALLS) {
                val next = if (sequence < 0) segments.covering(0) else segments.after(sequence)
                sequence = next?.sequenceNumber ?: return@repeat
            }
            val covered = segments.heldSegments.filter { !it.isInitSegment }
                .maxOfOrNull { it.startMs + it.durationMs } ?: 0
            println("[sweep] filled to ${covered}ms across ${covered / 1000}s, ${segments.heldSegments.size} segments")

            // Now vary ONLY the requested position.
            SWEEP_MS.forEach { at ->
                val gained = segments.probeAt(at)
                println("[sweep] asked ${at}ms -> $gained new segment(s)")
            }
            println("[sweep] ended holding ${segments.heldSegments.size} segments, policy ${segments.policy}")

            // `backoff_time_ms` appears in the policy only once the server has stopped serving, which
            // is the server asking us to wait -- and we had been hammering it. If a wait restores
            // service then this is a rate limit that recovers; if it does not, the session is simply
            // finished at sixty seconds and no amount of patience helps.
            WAITS_MS.forEach { wait ->
                kotlinx.coroutines.delay(wait)
                val gained = segments.probeAt(NEXT_WANTED_MS)
                println("[sweep] after waiting ${wait}ms -> $gained new segment(s), policy ${segments.policy}")
            }

            // A FRESH SESSION, asked for the segment the exhausted one refuses. If sixty seconds is a
            // per-session quota then this is the whole answer: a client streams a long video by
            // re-resolving, and `maxSinceLastRequest=60000ms` has been the session's own lifetime hint
            // all along. If it also refuses, the limit is on something wider than the session.
            val second = System.getProperty("sabrEndpoint2")
            if (second.isNullOrBlank()) {
                println("[sweep] no -DsabrEndpoint2 supplied, so the fresh-session question is untested")
                return@runBlocking
            }
            val fresh = SabrSegments(
                url = second,
                ustreamerConfig = Base64.getDecoder().decode(System.getProperty("ustreamerConfig2") ?: config),
                format = SabrFormat(
                    itag = audio[0].toInt(),
                    lastModified = audio[1].toLong(),
                    xtags = audio[2].ifBlank { null },
                    contentLength = audio[3].toLongOrNull(),
                ),
                kind = SabrTrackKind.AUDIO,
                transport = transport,
                clientInfo = SabrClientInfo.WEB,
            )
            val gained = fresh.probeAt(NEXT_WANTED_MS)
            val got = fresh.heldSegments.filter { !it.isInitSegment }
            println(
                "[sweep] a FRESH session asked ${NEXT_WANTED_MS}ms -> $gained new segment(s)" +
                    got.joinToString(prefix = " [", postfix = "]") { "seq=${it.sequenceNumber}@${it.startMs}ms" },
            )
            println(
                if (got.any { it.startMs >= NEXT_WANTED_MS - it.durationMs }) {
                    "[sweep] ✅ A FRESH SESSION SERVES WHAT THE OLD ONE WOULD NOT. Sixty seconds is a " +
                        "per-session quota, and streaming a long video means re-resolving. This is the fix."
                } else {
                    "[sweep] ✗ a fresh session refuses it too, so the limit is wider than one session."
                },
            )
        }
    }

    private companion object {
        const val FILL_CALLS = 8

        /** The position just past the ceiling, which is what we actually want served. */
        const val NEXT_WANTED_MS = 60_001L

        /** The stated backoff, then well beyond it, then beyond the session timeout. */
        val WAITS_MS = listOf(3_000L, 10_000L, 30_000L)
        const val TIMEOUT_S = 60L
        val PROTOBUF = "application/x-protobuf".toMediaType()

        /**
         * Inside the buffer, at its edge, and beyond it. If the rule is "serve until the buffer is
         * `targetAudioReadahead` ahead of the position", a position well inside the buffer should be
         * refused and one near its end should be served.
         */
        val SWEEP_MS = listOf(0L, 15_000L, 30_000L, 45_000L, 55_000L, 59_000L, 60_001L, 65_000L, 90_000L)
    }
}
