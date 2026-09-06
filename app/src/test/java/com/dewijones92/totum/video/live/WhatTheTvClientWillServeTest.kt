package com.dewijones92.totum.video.live

import com.dewijones92.totum.innertube.auth.AccessToken
import com.dewijones92.totum.innertube.browse.InnerTubeClient
import com.dewijones92.totum.innertube.browse.InnerTubeResponse
import com.dewijones92.totum.innertube.player.HttpSignatureTimestampSource
import com.dewijones92.totum.innertube.player.PlayableFormat
import com.dewijones92.totum.innertube.player.PlayerResponseParser
import com.dewijones92.totum.innertube.player.PlayerResult
import com.dewijones92.totum.innertube.player.SignatureTimestamp
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.util.concurrent.TimeUnit

/**
 * What quality YouTube will actually serve the SIGNED-IN TV client — the SmartTube question.
 *
 * SmartTube plays 4K60 on the same broadband where this app caps out at 1080p30, and the difference
 * is its client identity, not its SABR code. Measured 2026-08-18: `TVHTML5` answers
 * `LOGIN_REQUIRED — "Sign in to confirm you're not a bot"` anonymously at BOTH the current and the
 * downgraded version, so there is no TV client to be without an account. With one, this is the
 * measurement that decides whether 4K60 is reachable:
 *
 * * If the TV client's ladder carries 60fps/2160p formats that the ANDROID client's `ustreamer_config`
 *   refuses over SABR, then routing SABR through the TV response is the fix and the gap closes.
 * * If it does not, SmartTube's quality comes from somewhere else and
 *   `docs/todos/youtube-requires-attestation.md` is wrong about the cause — worth knowing either way.
 *
 * **Reports, never asserts** (beyond needing a response at all). Which formats YouTube offers which
 * client is entirely YouTube's business; asserting it would be the assert-someone-else's-policy
 * mistake for the fifth time. The printed table IS the deliverable.
 *
 * Needs a token, which only a human can obtain — Google blocks an automated browser from the device-code
 * approval outright. Supply it as `TOTUM_ACCESS_TOKEN`; without one the test skips, because "no
 * credentials here" is genuinely not a finding about the app. Read it off a signed-in emulator with:
 *
 * ```
 * adb shell run-as com.dewijones92.totum \
 *   cat /data/data/com.dewijones92.totum/shared_prefs/youtube_account.xml
 * ```
 */
class WhatTheTvClientWillServeTest {

    private val http = OkHttpClient.Builder()
        .callTimeout(CALL_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .build()

    @Test
    fun theSignedInTvClientsLadder() = runBlocking {
        val raw = System.getenv(TOKEN_ENV)?.takeIf { it.isNotBlank() }
        assumeTrue("no $TOKEN_ENV in the environment — nothing to measure with", raw != null)
        val token = AccessToken(raw!!)

        val sts = HttpSignatureTimestampSource(http).current()
        println("[tv] signatureTimestamp=$sts")
        val client = InnerTubeClient(http)

        // Two videos, because "UNPLAYABLE" from one video proves nothing about the CLIENT. Big Buck
        // Bunny is 4K60 (the interesting ladder) and the 97-minute VOD is one this app plays daily, so
        // if the TV client refuses both it is the client, and if it refuses one it is the video.
        for ((id, what) in listOf(FOUR_K_SIXTY to "4K60 CC film", ORDINARY_VOD to "97-minute VOD")) {
            println("[tv] --- $what ($id)")
            report("  ANDROID anonymous (what we use today)") { client.player(id) }
            report("  TVHTML5 current, signed in") { client.playerAsAccount(id, sts ?: NO_STAMP, token) }
            report("  TVHTML5 downgraded, signed in") { client.playerDowngradedTv(id, sts ?: NO_STAMP, token) }
        }
    }

    private suspend fun report(label: String, call: suspend () -> InnerTubeResponse) {
        val response = runCatching { call() }.getOrElse {
            println("[tv] $label: threw ${it::class.simpleName}: ${it.message}")
            return
        }
        val body = (response as? InnerTubeResponse.Success)?.body ?: run {
            println("[tv] $label: $response")
            return
        }
        when (val parsed = PlayerResponseParser.parse(body)) {
            is PlayerResult.Success -> describe(
                label,
                parsed.streaming.formats,
                parsed.streaming.serverAbrStreamingUrl != null,
                parsed.streaming.ustreamerConfig != null
            )
            else -> println("[tv] $label: not playable — $parsed")
        }
    }

    private fun describe(label: String, formats: List<PlayableFormat>, sabr: Boolean, ustreamer: Boolean) {
        val video = formats.filter { it.height != null }
        val sixty = video.filter { (it.fps ?: 0) > STANDARD_FPS }
        val tall = video.filter { (it.height ?: 0) > OUR_CAP }
        println(
            "[tv] $label: ${formats.size} formats, ${formats.count { it.url != null }} with a URL, " +
                "sabrEndpoint=$sabr ustreamerConfig=$ustreamer, " +
                "max ${video.maxOfOrNull { it.height ?: 0 } ?: 0}p, " +
                "${sixty.size} at >${STANDARD_FPS}fps, ${tall.size} above ${OUR_CAP}p",
        )
        if (tall.isNotEmpty()) {
            val listed = tall.sortedByDescending { it.height }.take(TOP_N).joinToString { f ->
                val url = if (f.url != null) " (url)" else ""
                "itag ${f.itag} ${f.height}p${f.fps ?: ""}$url"
            }
            println("[tv]     above our cap: $listed")
        }
    }

    private companion object {
        /** A stamp the server will refuse, so the refusal is measured rather than a crash. */
        val NO_STAMP = SignatureTimestamp(0)

        /** Blender's "Big Buck Bunny" — Creative Commons, 4K60, permanently up. */
        const val FOUR_K_SIXTY = "aqz-KE-bpKQ"

        /** One this app plays constantly, as the control against a video-specific refusal. */
        const val ORDINARY_VOD = "uSMGENDH_QI"
        const val TOKEN_ENV = "TOTUM_ACCESS_TOKEN"
        const val STANDARD_FPS = 30
        const val OUR_CAP = 1080
        const val TOP_N = 6
        const val CALL_TIMEOUT_SECONDS = 45L
    }
}
