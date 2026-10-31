package com.dewijones92.totum.video

import com.dewijones92.totum.common.Diag
import com.dewijones92.totum.common.HttpUrl
import com.dewijones92.totum.innertube.player.LiveDash
import com.dewijones92.totum.innertube.player.LiveHead
import com.dewijones92.totum.innertube.player.PlayerResult
import com.dewijones92.totum.innertube.player.StreamingData
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request

class LiveStreamManifest(
    private val heads: suspend (HttpUrl) -> LiveHead?,
    private val publish: (String, String) -> HttpUrl,
) {
    suspend fun applyTo(videoId: String, answer: PlayerResult.Success): PlayerResult.Success {
        val streaming = answer.streaming
        val needsOne = answer.details?.isLive == true && streaming.liveManifestUrl == null && streaming.hasLiveSegments
        if (!needsOne) return answer
        val manifest = build(videoId, streaming) ?: return answer
        return answer.copy(streaming = streaming.copy(liveManifestUrl = publish(videoId, manifest)))
    }

    companion object {
        fun headsOver(client: OkHttpClient): suspend (HttpUrl) -> LiveHead? = { url ->
            withContext(Dispatchers.IO) {
                val request = Request.Builder().url(url.value).header("Range", "bytes=0-0").build()
                runCatching {
                    client.newCall(request).execute().use { reply ->
                        val head = LiveDash.head { name -> reply.header(name) }
                        val named = reply.headers.names().filter { it.startsWith("X-", ignoreCase = true) }
                        Diag.log("live", "head request answered ${reply.code}: $named -> $head")
                        head
                    }
                }.onFailure { Diag.warn("live", "head request failed", it) }.getOrNull()
            }
        }
    }

    private suspend fun build(videoId: String, streaming: StreamingData): String? {
        val probe = LiveDash.headProbeFormat(streaming.formats)?.url
        val head = probe?.let { heads(it) }
        val manifest = head?.let { LiveDash.manifest(streaming.formats, it) }
        val why = when {
            probe == null -> "it offers no audio format to ask for its head"
            head == null -> "the head request named no sequence number"
            manifest == null -> "no manifest could be built from ${streaming.formats.size} format(s)"
            else -> null
        }
        if (why != null) {
            Diag.warn("live", "$videoId is live but plays as before: $why")
        } else {
            Diag.log(
                "live",
                "$videoId is live: head segment ${head?.sequence}, ${head?.streamMs}ms streamed; playing a manifest " +
                    "Totum built from ${streaming.formats.count { it.url != null }} format(s)",
            )
        }
        return manifest
    }
}
