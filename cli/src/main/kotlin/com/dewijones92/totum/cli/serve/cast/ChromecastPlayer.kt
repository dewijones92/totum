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
import java.io.File

internal class ChromecastPlayer(
    private val label: String,
    private val control: CastControl,
    private val streams: SoundStreams,
    private val clock: () -> Long = System::currentTimeMillis,
) : TargetPlayer {

    @Volatile private var offerUrl: String? = null

    @Volatile private var sourceUrl: String? = null

    @Volatile private var offsetMs = 0L

    override var onEnded: (failed: Boolean) -> Unit
        get() = control.onEnded
        set(value) {
            control.onEnded = value
        }

    override fun play(request: PlayRequest) {
        stop()
        val wantsProcessing = request.sound.changesSound
        val processed = wantsProcessing && !request.picture && streams.available
        if (wantsProcessing && !processed) {
            Diag.log(
                "cast-target",
                "$label: sound processing not applied (picture=${request.picture}, " +
                    "processed sound server ${if (streams.available) "ready" else "not configured"}); " +
                    "playing as it comes",
            )
        }
        val url = when {
            processed -> streams.offer(label, request.url, request.sound, request.startAtMs)
            request.url.startsWith("/") -> streams.offerFile(label, File(request.url))
            streams.relays(request.url) -> relayed(request)
            else -> null
        } ?: request.url
        offerUrl = url.takeIf { it != request.url }
        offsetMs = if (processed) request.startAtMs else 0L
        sourceUrl = if (processed) url else null
        val startAt = if (processed) 0L else request.startAtMs
        control.load(url, contentTypeOf(request.url, request.picture, processed), request.title, startAt)
    }

    private fun relayed(request: PlayRequest): String? {
        val address = streams.offerRelayed(label, request.url)
        val outcome = if (address == null) {
            "given as it is (no address the TV can reach is configured)"
        } else {
            "relayed by the Pi"
        }
        Diag.log("cast-target", "$label: YouTube stream $outcome")
        return address
    }

    override fun pause(paused: Boolean) = control.pause(paused)

    override fun seek(positionMs: Long) = control.seek(positionMs)

    override fun volume(percent: Int) = control.volume(percent)

    override val volumePercent: Int? get() = control.volumePercent()

    override fun stop() {
        control.stop()
        offerUrl?.let(streams::withdraw)
        offerUrl = null
        sourceUrl = null
    }

    override val positionMs: Long?
        get() = control.reading()?.positionAt(
            clock()
        )?.let { it + offsetMs + (sourceUrl?.let(streams::skippedMs) ?: 0) }

    override val durationMs: Long? get() = if (sourceUrl != null) null else control.reading()?.durationMs
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
        Diag.log("cast-target", "$host: nothing to hand back, the app stays on the TV")
    }
}

internal class KnownCasts(val name: String, val host: String)

internal fun knownCastsFrom(spec: String?): List<KnownCasts> = spec.orEmpty().split(';').mapNotNull { pair ->
    pair.split('=', limit = 2).takeIf { it.size == 2 && it[0].isNotBlank() && it[1].isNotBlank() }
        ?.let { KnownCasts(it[0].trim(), it[1].trim()) }
}

internal class ChromecastSource(
    private val known: List<KnownCasts>,
    private val streams: SoundStreams,
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
