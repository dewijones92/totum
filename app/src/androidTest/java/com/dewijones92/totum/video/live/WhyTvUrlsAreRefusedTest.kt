package com.dewijones92.totum.video.live

import androidx.test.platform.app.InstrumentationRegistry
import com.dewijones92.totum.TotumApplication
import com.dewijones92.totum.innertube.auth.AccessTokenResult
import com.dewijones92.totum.innertube.browse.InnerTubeClient
import com.dewijones92.totum.innertube.browse.InnerTubeResponse
import com.dewijones92.totum.innertube.player.HttpSignatureTimestampSource
import com.dewijones92.totum.innertube.player.NSolver
import com.dewijones92.totum.innertube.player.PlayerResponseParser
import com.dewijones92.totum.innertube.player.PlayerResult
import com.dewijones92.totum.innertube.player.StreamingData
import com.dewijones92.totum.innertube.player.withSolvedN
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.Assume.assumeTrue
import org.junit.Test

/** MANUAL ONLY: needs a device signed in to YouTube. */
class WhyTvUrlsAreRefusedTest {

    private val app = InstrumentationRegistry.getInstrumentation()
        .targetContext.applicationContext as TotumApplication
    private val http = OkHttpClient()
    private val client = InnerTubeClient(http)

    @Test
    fun tvUrlsAcrossSolverScriptsAndUserAgents() = runBlocking {
        val token = (app.container.youTubeAccount.accessToken() as? AccessTokenResult.Available)?.token
        assumeTrue("this device is not signed in", token != null)
        val timestamps = HttpSignatureTimestampSource(http)
        val stamp = timestamps.current()
        assumeTrue("no signature timestamp", stamp != null)
        val build = timestamps.playerScriptUrl()!!.substringAfter("/s/player/").substringBefore("/")
        val scripts = mapOf(
            "web base.js" to HttpSignatureTimestampSource.basePlayerScriptUrl(build),
            "tv-player-ias.js" to HttpSignatureTimestampSource.tvPlayerScriptUrl(build),
        )
        val solver = NSolver { challenges, playerUrl -> app.container.ytDlpEngine.solveN(challenges, playerUrl) }
        println("[tv403] build $build stamp $stamp video $VIDEO_ID")
        val clients = mapOf(
            "control: anonymous ANDROID" to suspend { client.player(VIDEO_ID) },
            "downgraded TV" to suspend { client.playerDowngradedTv(VIDEO_ID, stamp!!, token!!) },
            "TV" to suspend { client.playerAsAccount(VIDEO_ID, stamp!!, token!!) },
        )
        clients.forEach { (label, ask) ->
            val streaming = streamingOf(ask()) ?: return@forEach println("[tv403] $label: no streams")
            scripts.forEach { (scriptLabel, script) ->
                val solved = streaming.withSolvedN(solver, script)
                val best = solved.directlyPlayable
                    .filter { it.mimeType?.startsWith("video/") == true && it.url != null }
                    .maxByOrNull { it.height ?: 0 }
                if (best == null) {
                    println("[tv403] $label / $scriptLabel: nothing fetchable after solving")
                    return@forEach
                }
                val url = best.url!!.value
                val fetches = USER_AGENTS.entries.joinToString("; ") { (uaLabel, ua) -> "$uaLabel ${fetch(url, ua)}" }
                val markers = listOf("pot", "n", "sabr", "rqh", "spc").joinToString(" ") { key ->
                    "$key=${if (Regex("[?&]$key=").containsMatchIn(url)) "yes" else "no"}"
                }
                println(
                    "[tv403] $label / n by $scriptLabel / ${best.height}p itag ${best.itag} " +
                        "c=${url.substringAfter("&c=", "?").substringBefore("&")} [$markers]: $fetches",
                )
                delay(LATER_MS)
                println("[tv403]   +${LATER_MS}ms: cobalt-tv ${fetch(url, InnerTubeClient.TV_DOWNGRADED_USER_AGENT)}")
                println("[tv403]   first 1MB: cobalt-tv ${fetchStart(url)}")
            }
        }
    }

    private fun streamingOf(response: InnerTubeResponse): StreamingData? {
        val body = (response as? InnerTubeResponse.Success)?.body ?: return null
        return (PlayerResponseParser.parse(body) as? PlayerResult.Success)?.streaming
    }

    private fun fetchStart(url: String): String = runCatching {
        val request = Request.Builder().url(url).header("Range", "bytes=0-$PROBE_BYTES")
            .header("User-Agent", InnerTubeClient.TV_DOWNGRADED_USER_AGENT).build()
        http.newCall(request).execute().use { "HTTP ${it.code}" }
    }.getOrElse { "threw ${it::class.simpleName}" }

    private fun fetch(url: String, userAgent: String?): String = runCatching {
        val request = Request.Builder().url(url).header("Range", "bytes=$DEEP_OFFSET-${DEEP_OFFSET + PROBE_BYTES}")
            .apply { userAgent?.let { header("User-Agent", it) } }
            .build()
        http.newCall(request).execute().use { "HTTP ${it.code}" }
    }.getOrElse { "threw ${it::class.simpleName}" }

    private companion object {
        const val VIDEO_ID = "uSMGENDH_QI"
        const val DEEP_OFFSET = 8_000_000L
        const val PROBE_BYTES = 102_399L
        const val LATER_MS = 8_000L
        val USER_AGENTS = mapOf(
            "okhttp" to null,
            "dalvik" to "Dalvik/2.1.0 (Linux; U; Android 15; sdk_gphone64_x86_64 Build/AE3A.240806.043)",
            "android-yt" to "com.google.android.youtube/20.10.38 (Linux; U; Android 14) gzip",
            "cobalt-tv" to InnerTubeClient.TV_DOWNGRADED_USER_AGENT,
        )
    }
}
