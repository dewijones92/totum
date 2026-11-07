package com.dewijones92.totum.cli.serve.cast

import com.dewijones92.totum.cli.serve.sound.ProcessedSound
import com.dewijones92.totum.common.Diag
import com.dewijones92.totum.data.targets.SoundSettings
import com.dewijones92.totum.playback.cast.CastItem
import com.dewijones92.totum.playback.cast.CastStreamServer
import com.dewijones92.totum.playback.cast.CastTimeline
import com.dewijones92.totum.playback.cast.LocalManifests
import com.dewijones92.totum.playback.cast.MediaTools
import java.util.concurrent.ConcurrentHashMap

internal class PiStreams(
    private val processed: ProcessedSound,
    private val base: String?,
    port: Int,
    private val relay: StreamRelay = StreamRelay(),
    tools: MediaTools? = null,
    private val liveHls: LiveHls? = null,
) {
    private class Playing(val sound: SoundSettings, val durationUs: Long?)

    private val tvs = PiCastTvs()
    private val playing = ConcurrentHashMap<String, Playing>()

    private val server = CastStreamServer(
        engines = { request ->
            val now = playing[request.mediaId]
            val source = locally(request.label, request.source)
            FfmpegAudioEngine(
                request.label,
                source,
                (now?.sound ?: SoundSettings()).copy(skipSilence = request.cuts),
                now?.durationUs,
                processed,
            ) { withdraw(source) }
        },
        skipSilence = { false },
        host = PiCastHost(base),
        tvs = tvs,
        tools = tools,
        port = port,
        alsoServes = relay::handle,
    )

    val available: Boolean get() = base != null

    fun relayed(): Int = relay.offered()

    fun urlFor(tv: PiCastTv, item: CastItem, sound: SoundSettings): String? {
        tvs.expect(item.mediaId, tv)
        playing[item.mediaId] = Playing(sound, item.durationMs?.let { it * MICROS_PER_MS })
        return server.urlFor(item).also {
            Diag.log(
                "cast-target",
                "${tv.label}: ${item.mediaId} -> ${it ?: "given as it is"} (uri=${hostOf(item.uri)} " +
                    "separateAudio=${item.separateAudioUrl != null} ladder=${item.ladder.size} " +
                    "listening=${item.listening})",
            )
        }
    }

    fun timelineFor(mediaId: String): CastTimeline? = server.timelineFor(mediaId)

    fun release(mediaId: String) {
        server.release(mediaId)
        playing.remove(mediaId)
        tvs.forget(mediaId)
    }

    fun givenAsItIs(label: String, url: String): String {
        val where = base ?: return url
        if (!relay.carries(url)) return url
        server.start()
        val relayed = "${where.trimEnd('/')}${relay.offer(label, url)}"
        Diag.log("cast-target", "$label: a YouTube stream the TV could not be given as a manifest is relayed by the Pi")
        return relayed
    }

    fun locally(label: String, url: String): String {
        if (LocalManifests.servesManifest(url)) {
            val videoId = url.substringAfter("/live/").substringBefore('?').removeSuffix(".mpd")
            liveHls?.urlFor(videoId)?.let { hls ->
                Diag.log("cast-target", "$label: a live stream, played from YouTube's own HLS manifest")
                return hls
            }
            Diag.log("cast-target", "$label: no HLS manifest for $videoId; playing Totum's DASH manifest instead")
            val loopback = "http://127.0.0.1:${server.start()}"
            val opened = server.relayLive(CastItem(label, url), loopback)
            Diag.log("cast-target", "$label: a live manifest, played from ${opened ?: "its own address"}")
            return opened ?: url
        }
        if (!relay.carries(url)) return url
        val listening = server.start()
        return "http://127.0.0.1:$listening${relay.offer(label, url)}"
    }

    fun withdraw(url: String) {
        relay.tokenIn(url)?.let(relay::withdraw)
    }

    private fun hostOf(url: String): String = runCatching { java.net.URI(url).host }.getOrNull() ?: url.take(HOST_SHOWN)

    private companion object {
        const val MICROS_PER_MS = 1_000L
        const val HOST_SHOWN = 24
    }
}
