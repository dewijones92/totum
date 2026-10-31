package com.dewijones92.totum.video

import com.dewijones92.totum.common.Diag
import com.dewijones92.totum.domain.PlayableItem
import com.dewijones92.totum.innertube.browse.InnerTubeClient
import com.dewijones92.totum.innertube.browse.InnerTubeResponse
import com.dewijones92.totum.innertube.browse.parsed
import com.dewijones92.totum.innertube.player.HttpEmbedHostFlagsSource
import com.dewijones92.totum.innertube.player.HttpSignatureTimestampSource
import com.dewijones92.totum.innertube.player.HttpVisitorIdSource
import com.dewijones92.totum.innertube.player.NSolver
import com.dewijones92.totum.innertube.player.PlayerResponseParser
import com.dewijones92.totum.innertube.player.PlayerResult
import com.dewijones92.totum.innertube.player.SignatureSolver
import com.dewijones92.totum.innertube.player.StreamingData
import com.dewijones92.totum.innertube.player.withSolvedChallenges
import com.dewijones92.totum.innertube.player.withSolvedN

fun solvingChallenges(
    signatureTimestamps: HttpSignatureTimestampSource,
    nSolver: NSolver,
    signatureSolver: SignatureSolver,
): suspend (StreamingData) -> StreamingData = { streaming ->
    val playerUrl = runCatching { signatureTimestamps.playerScriptUrl() }.getOrNull()
    if (playerUrl != null) {
        streaming.withSolvedChallenges(nSolver, signatureSolver, playerUrl)
    } else {
        // FAIL CLOSED. Returning the streams untouched here looked harmless and
        // was the worst option: their `n` is unsolved, so they 403 the moment
        // playback opens them — a video that resolved in 112ms and then died,
        // rather than one that took 14s and worked. Seen on the emulator
        // 2026-08-02. An empty solver drops every format carrying an `n`, which
        // sends the caller to extraction, and anything with no `n` survives.
        Diag.warn("resolve", "no player script — dropping any stream that needs its n solved")
        streaming.withSolvedN({ _, _ -> emptyMap() }, "")
    }
}

fun signedInVideo(resolver: VideoResolver): suspend (PlayableItem, Int?) -> VideoQuality? = { item, maxHeight ->
    val qualities = resolver.resolve(item, "download")?.qualities.orEmpty()
    qualities.firstOrNull { maxHeight == null || it.height <= maxHeight } ?: qualities.lastOrNull()
}

class EmbeddedPlayer(
    private val client: InnerTubeClient,
    private val visitorIds: HttpVisitorIdSource,
    private val embedHostFlags: HttpEmbedHostFlagsSource,
    private val signatureTimestamps: HttpSignatureTimestampSource,
) {
    suspend fun player(videoId: String): PlayerResult? {
        val visitor = visitorIds.current() ?: return null
        val flags = embedHostFlags.forVideo(videoId) ?: return null
        val stamp = runCatching { signatureTimestamps.current() }.getOrNull() ?: return null
        val response = client.playerAsEmbedded(videoId, visitor, stamp, flags)
        val success = response as? InnerTubeResponse.Success ?: run {
            Diag.log("resolve", "$videoId: the embedded player answered $response")
            return null
        }
        return success.parsed { PlayerResponseParser.parse(it) }
    }
}
