package com.dewijones92.totum.cli.serve.cast

import com.dewijones92.totum.cli.serve.targets.PlayRequest
import com.dewijones92.totum.cli.serve.targets.TargetLink
import com.dewijones92.totum.cli.serve.targets.TargetPlayer
import com.dewijones92.totum.cli.serve.targets.withoutApiKeys
import com.dewijones92.totum.common.Diag
import com.dewijones92.totum.data.targets.TargetPower
import com.dewijones92.totum.data.targets.TargetTrouble
import com.dewijones92.totum.data.targets.TroubleKind
import com.dewijones92.totum.playback.cast.CastFormat
import com.dewijones92.totum.playback.cast.CastItem
import com.dewijones92.totum.playback.cast.LocalManifests
import kotlin.concurrent.thread

internal class ChromecastPlayer(
    private val label: String,
    private val control: CastControl,
    private val streams: PiStreams,
    private val wake: TvWake? = null,
    private val clock: () -> Long = System::currentTimeMillis,
    private val watch: (check: () -> Boolean) -> Unit = ::watchWhileTrue,
) : TargetPlayer {

    private val tv = PiCastTv(label, screen = { control.screen }) { control.model }

    private val itemLock = Any()

    @Volatile private var rawUrl: String? = null

    @Volatile private var mediaId: String? = null

    @Volatile private var watching: Any? = null

    @Volatile private var liveNow = false

    override val notice: String? get() = tv.notice

    override val live: Boolean get() = liveNow

    override val power: TargetPower get() = wake?.power ?: TargetPower.ON

    private var lostHandler: (reason: String) -> Unit = {}

    init {
        control.onLost = { reason ->
            wake?.markAsleep()
            lostHandler(reason)
        }
    }

    override var onEnded: (failed: Boolean) -> Unit
        get() = control.onEnded
        set(value) {
            control.onEnded = value
        }

    override var onLost: (reason: String) -> Unit
        get() = lostHandler
        set(value) {
            lostHandler = value
        }

    override fun play(request: PlayRequest) = synchronized(itemLock) {
        stop()
        tv.notice = null
        val wanted = request.cast ?: CastItem(request.title, request.url, listening = !request.picture)
        liveNow = LocalManifests.isLive(wanted.uri)
        val item = wanted.copy(mediaId = "$label|${wanted.mediaId}", skipsSilence = request.sound.skipSilence)
        val format = control.format
        val manifest = if (streams.available) streams.urlFor(tv, item, request.sound, format) else null
        if (manifest != null) {
            mediaId = item.mediaId
            soundOnlyNote(request, format)
            control.load(manifest, format.mime, request.title, request.startAtMs)
            watchTheCutEnd(item.mediaId)
        } else {
            val url = streams.givenAsItIs(label, request.url)
            if (control.keepsAddressesPrivate && url.withoutApiKeys() != url) {
                Diag.warn(
                    "cast-target",
                    "$label: no manifest could be built for ${request.title} and its own address carries a key " +
                        "(${hostOrPath(url)}); not handing it to a browser",
                )
                error("no stream could be built for $label, and the item's own address carries a key")
            }
            rawUrl = url.takeIf { it != request.url }
            Diag.log("cast-target", "$label: ${request.title} loaded as it comes (${hostOrPath(url)})")
            control.load(url, contentTypeOf(request.url, request.picture, false), request.title, request.startAtMs)
        }
    }

    private fun soundOnlyNote(request: PlayRequest, format: CastFormat) {
        if (format == CastFormat.DASH || !request.picture || !hasAPicture(request)) return
        tv.notice = "🔈 $label plays the sound of this over ${format.name}; there is no picture yet"
        Diag.log(
            "cast-target",
            "$label: ${request.title} has a picture, but ${format.name} carries sound only; ${tv.notice}"
        )
    }

    private fun hasAPicture(request: PlayRequest): Boolean {
        val cast = request.cast
        val extension = request.url.substringBefore('?').substringAfterLast('.', "").lowercase()
        return cast?.ladder?.isNotEmpty() == true || cast?.separateAudioUrl != null ||
            extension in VIDEO_EXTENSIONS || request.url.contains("mime=video")
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
        synchronized(itemLock) {
            if (mediaId != id || watching == null) {
                Diag.log(
                    "cast-target",
                    "$label: the cut audio of $id ended, but another item is loaded now; left alone"
                )
                return true
            }
            Diag.log(
                "cast-target",
                "$label: the cut audio ends at ${endTvUs / MICROS_PER_MS}ms on the TV (at " +
                    "${reading.positionAt(clock())}ms now); stopping the TV so the queue moves on",
            )
            watching = null
            control.stop()
        }
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

    override fun stop() = synchronized(itemLock) {
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
        val VIDEO_EXTENSIONS = setOf("mp4", "m4v", "mkv", "webm", "mov", "avi", "ts")
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

internal class CastLink(
    private val host: String,
    private val control: CastControl,
    private val wake: TvWake? = null,
) : TargetLink {

    @Volatile override var unreachable: TargetTrouble? = null
        private set

    override suspend fun connect(): String? {
        unreachable = null
        if (wake != null && !wake.ensureAwake()) {
            unreachable = TargetTrouble(TroubleKind.WONT_WAKE)
            return null
        }
        val tries = if (wake?.sentWake == true) TRIES_AFTER_A_WAKE else 1
        repeat(tries) { attempt ->
            if (control.reachable()) return host
            Diag.log("cast-target", "$host: receiver not connected (try ${attempt + 1} of $tries)")
        }
        return null
    }

    override suspend fun release() {
        Diag.log("cast-target", "$host: handing the TV back")
        control.release()
    }

    private companion object {
        const val TRIES_AFTER_A_WAKE = 2
    }
}

internal class KnownCasts(val name: String, val host: String, val mac: String? = null)

internal fun knownCastsFrom(spec: String?): List<KnownCasts> = spec.orEmpty().split(';').mapNotNull { pair ->
    val parts = pair.split('=', limit = 2)
    val name = parts.first().trim()
    val where = parts.getOrNull(1)?.substringBefore('@')?.trim().orEmpty()
    if (name.isEmpty() || where.isEmpty()) return@mapNotNull null
    val macText = parts[1].substringAfter('@', "").trim()
    val mac = macText.takeIf { it.isNotEmpty() }?.let { text ->
        WakeOnLan.parseMac(text)?.let(WakeOnLan::show).also {
            if (it == null) {
                Diag.warn(
                    "cast-target",
                    "$name: '$text' is not a MAC (aa:bb:cc:dd:ee:ff), so no wake-on-LAN"
                )
            }
        }
    }
    KnownCasts(name, where, mac)
}
