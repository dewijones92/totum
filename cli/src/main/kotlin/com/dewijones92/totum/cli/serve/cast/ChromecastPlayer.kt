package com.dewijones92.totum.cli.serve.cast

import com.dewijones92.totum.cli.serve.targets.FoundTarget
import com.dewijones92.totum.cli.serve.targets.PlayRequest
import com.dewijones92.totum.cli.serve.targets.TargetKit
import com.dewijones92.totum.cli.serve.targets.TargetLink
import com.dewijones92.totum.cli.serve.targets.TargetPlayer
import com.dewijones92.totum.cli.serve.targets.TargetSource
import com.dewijones92.totum.common.Diag
import com.dewijones92.totum.data.targets.Target
import com.dewijones92.totum.data.targets.TargetKind
import com.dewijones92.totum.playback.cast.CastItem
import com.dewijones92.totum.playback.cast.Http
import com.dewijones92.totum.playback.cast.LocalManifests
import kotlin.concurrent.thread

internal class ChromecastPlayer(
    private val label: String,
    private val control: CastControl,
    private val streams: PiStreams,
    private val clock: () -> Long = System::currentTimeMillis,
    private val watch: (check: () -> Boolean) -> Unit = ::watchWhileTrue,
) : TargetPlayer {

    private val tv = PiCastTv(label) { control.model }

    @Volatile private var rawUrl: String? = null

    @Volatile private var mediaId: String? = null

    @Volatile private var watching: Any? = null

    @Volatile private var liveNow = false

    override val notice: String? get() = tv.notice

    override val live: Boolean get() = liveNow

    override var onEnded: (failed: Boolean) -> Unit
        get() = control.onEnded
        set(value) {
            control.onEnded = value
        }

    override fun play(request: PlayRequest) {
        stop()
        tv.notice = null
        val wanted = request.cast ?: CastItem(request.title, request.url, listening = !request.picture)
        liveNow = LocalManifests.isLive(wanted.uri)
        val item = wanted.copy(mediaId = "$label|${wanted.mediaId}", skipsSilence = request.sound.skipSilence)
        val manifest = if (streams.available) streams.urlFor(tv, item, request.sound) else null
        if (manifest != null) {
            mediaId = item.mediaId
            control.load(manifest, Http.MPD, request.title, request.startAtMs)
            watchTheCutEnd(item.mediaId)
        } else {
            val url = streams.givenAsItIs(label, request.url)
            rawUrl = url.takeIf { it != request.url }
            Diag.log("cast-target", "$label: ${request.title} loaded as it comes (${hostOrPath(url)})")
            control.load(url, contentTypeOf(request.url, request.picture, false), request.title, request.startAtMs)
        }
    }

    private fun watchTheCutEnd(id: String) {
        val timeline = streams.timelineFor(id)?.takeIf { it.cutsSilences } ?: return
        val mine = Any()
        watching = mine
        watch { watching !== mine || nudgedAtTheEnd(id, timeline.endTvUs) }
    }

    internal fun nudgedAtTheEnd(id: String, endTvUs: Long?): Boolean {
        if (mediaId != id || endTvUs == null) return false
        val reading = control.reading() ?: return false
        if (!reading.playing || reading.positionAt(clock()) * MICROS_PER_MS < endTvUs - SLACK_US) return false
        Diag.log(
            "cast-target",
            "$label: the cut audio ends at ${endTvUs / MICROS_PER_MS}ms on the TV (at " +
                "${reading.positionAt(clock())}ms now); stopping the TV so the queue moves on",
        )
        watching = null
        control.stop()
        control.onEnded(false)
        return true
    }

    override fun pause(paused: Boolean) = control.pause(paused)

    override fun seek(positionMs: Long) {
        val tvMs = timeline()?.tvFor(positionMs * MICROS_PER_MS)?.div(MICROS_PER_MS) ?: positionMs
        if (tvMs != positionMs) {
            Diag.log(
                "cast-target",
                "$label: seek to ${positionMs}ms of the source is ${tvMs}ms on the TV"
            )
        }
        control.seek(tvMs)
    }

    override fun volume(percent: Int) = control.volume(percent)

    override val volumePercent: Int? get() = control.volumePercent()

    override fun stop() {
        watching = null
        liveNow = false
        control.stop()
        rawUrl?.let(streams::withdraw)
        rawUrl = null
        mediaId?.let(streams::release)
        mediaId = null
    }

    override val positionMs: Long?
        get() = control.reading()?.positionAt(clock())?.let { tvMs ->
            timeline()?.sourceFor(tvMs * MICROS_PER_MS)?.div(MICROS_PER_MS) ?: tvMs
        }

    override val durationMs: Long? get() = control.reading()?.durationMs

    private fun timeline() = mediaId?.let(streams::timelineFor)?.takeIf { it.cutsSilences }

    private fun hostOrPath(url: String): String = runCatching { java.net.URI(url).host }.getOrNull() ?: "a file"

    private companion object {
        const val MICROS_PER_MS = 1_000L
        const val SLACK_US = 300_000L
    }
}

private const val WATCH_MS = 500L

private fun watchWhileTrue(check: () -> Boolean) {
    thread(name = "cast-end-watch", isDaemon = true) {
        while (!check()) Thread.sleep(WATCH_MS)
    }
}

internal fun contentTypeOf(url: String, picture: Boolean, processed: Boolean): String {
    if (processed) return "audio/aac"
    val mime = Regex("""[?&]mime=([^&]+)""").find(url)?.groupValues?.get(1)?.replace("%2F", "/")
    if (mime != null) return mime
    val extension = url.substringBefore('?').substringAfterLast('.', "").lowercase()
    return when (extension) {
        "mp3" -> "audio/mpeg"
        "m4a", "aac" -> "audio/mp4"
        "ogg", "opus" -> "audio/ogg"
        "webm" -> if (picture) "video/webm" else "audio/webm"
        "mkv" -> "video/x-matroska"
        else -> if (picture) "video/mp4" else "audio/mpeg"
    }
}

internal class CastLink(private val host: String, private val control: CastControl) : TargetLink {
    override suspend fun connect(): String? = host.takeIf { control.reachable() }

    override suspend fun release() {
        Diag.log("cast-target", "$host: handing the TV back")
        control.release()
    }
}

internal class KnownCasts(val name: String, val host: String)

internal fun knownCastsFrom(spec: String?): List<KnownCasts> = spec.orEmpty().split(';').mapNotNull { pair ->
    pair.split('=', limit = 2).takeIf { it.size == 2 && it[0].isNotBlank() && it[1].isNotBlank() }
        ?.let { KnownCasts(it[0].trim(), it[1].trim()) }
}

internal class ChromecastSource(
    private val known: List<KnownCasts>,
    private val streams: PiStreams,
) : TargetSource {

    override suspend fun find(fresh: Boolean): List<FoundTarget> = known.map { cast ->
        val kind = if (cast.name.contains("chromecast", ignoreCase = true)) TargetKind.CHROMECAST else TargetKind.TV
        val id = cast.name.lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-')
        FoundTarget(Target(id, cast.name, kind)) {
            val control = PyCastControl(cast.host, cast.name)
            TargetKit(ChromecastPlayer(cast.name, control, streams), CastLink(cast.host, control))
        }
    }.also {
        Diag.log(
            "cast-target",
            "configured ${it.size} cast target(s): ${known.joinToString { "${it.name}=${it.host}" }}"
        )
    }
}
