package com.dewijones92.totum.playback.cast

import com.dewijones92.totum.common.Diag
import com.dewijones92.totum.domain.Picture
import com.dewijones92.totum.domain.QualityLabel
import com.dewijones92.totum.domain.VideoCodecFamily
import com.dewijones92.totum.domain.pictureAt
import com.dewijones92.totum.playback.CastProblem
import com.dewijones92.totum.playback.CastStage
import java.io.File
import java.io.IOException
import java.net.URI
import java.security.SecureRandom
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedDeque

public class CastSubtitleSource(
    public val location: String,
    public val language: String?,
    public val label: String?,
)

public data class CastItem(
    public val mediaId: String,
    public val uri: String,
    public val separateAudioUrl: String? = null,
    public val listening: Boolean = false,
    public val skipsSilence: Boolean? = null,
    public val ladder: List<CastRung> = emptyList(),
    public val durationMs: Long? = null,
    public val subtitles: List<CastSubtitleSource> = emptyList(),
)

public interface CastHostPort {
    public val vpnInTheWay: Boolean
    public fun baseUrl(port: Int): String?
    public fun hold(active: Boolean)
}

@Suppress("TooManyFunctions")
public class CastStreamServer(
    private val engines: AudioEngines,
    private val skipSilence: () -> Boolean,
    private val host: CastHostPort,
    private val tvs: CastTvPort,
    private val tools: MediaTools?,
    private val port: Int = DEFAULT_PORT,
    private val alsoServes: (HttpRequest) -> HttpReply? = { null },
    private val upstream: (String, String?) -> UpstreamReply = PatientUpstream()::get,
) {
    private val videos = TvVideos(tvs)

    @Volatile
    public var screens: ScreenLink? = null

    private val server = CastHttpServer(
        handle = { request ->
            alsoServes(request) ?: live.replyTo(request) ?: screens?.replyTo(request) ?: handle(request)
        },
    )

    public fun start(): Int = server.start(port)

    public fun serveScreens(): String? {
        server.start(port)
        return host.baseUrl(port)
    }

    private val sessions = ConcurrentHashMap<String, Session>()
    private val order = ConcurrentLinkedDeque<String>()
    private val latest = ConcurrentHashMap<String, Session>()
    private val live = LiveCasts()

    public fun timelineFor(mediaId: String): CastTimeline? = latest[mediaId]?.audio?.timeline

    private val ceilings = ConcurrentHashMap<String, Int>()
    private val steppingDown = ConcurrentHashMap.newKeySet<String>()

    public fun stepDown(mediaId: String): CastStep? {
        val rungs = latest[mediaId]?.rungs
        val from = rungs?.rungs?.firstOrNull()?.first
        val lower = rungs?.nextLowerHeight()
        if (from == null || lower == null) {
            Diag.log(
                "cast",
                "$mediaId: stalled, but there is no lower picture to send " +
                    "(sending ${from?.label ?: "no ladder picture"}; the pictures this TV could be sent: " +
                    "${rungs?.showableSummary() ?: "no session"}); it keeps going as it is",
            )
            return null
        }
        ceilings[mediaId] = lower
        steppingDown.add(mediaId)
        videos.steppedDown(mediaId, from.height)
        Diag.warn("cast", "$mediaId: stepping down from ${from.label} to ${lower}p because the TV keeps stalling")
        return CastStep(from.quality, lower)
    }

    private val random = SecureRandom()
    private val reach = CastReach(tvs, vpnInTheWay = { host.vpnInTheWay })
    private val failures = CastFailures(tvs)

    @Suppress("TooManyFunctions")
    private inner class Session(
        val token: String,
        val item: CastItem,
        val videoUrl: String?,
        val audioSource: String,
        val ladder: List<CastRung>,
        val ceiling: Int?,
    ) {
        val rungs = CastRungs(
            item.mediaId,
            onSent = { label -> if (latest[item.mediaId] === this) videos.sent(item.mediaId, label) },
            onFetching = { height -> if (latest[item.mediaId] === this) videos.fetching(item.mediaId, height) },
        )

        val cuts = videoUrl == null && (item.skipsSilence ?: skipSilence())
        val audio = ProcessedAudio(
            engines.open(AudioRequest(item.mediaId, audioSource, "${item.mediaId}/$token", cuts)),
            "${item.mediaId}/$token",
            cuts,
        )
        val subtitles = if (cuts) {
            emptyList()
        } else {
            item.subtitles + fromTheFile(videoUrl, item.mediaId)
        }

        @Volatile var videoRequests = 0

        val relay = VideoRelay(upstream, RelayLog(item.mediaId, sink = { Diag.log("cast", it) }))

        @Volatile var flowing = false

        @Volatile var layout: StreamLayout? = null

        @Volatile var remuxed: RemuxedVideo? = null

        @Volatile var videoFile: File? = null

        @Volatile private var videoReader = RangeReader { span -> Upstream.bytes(videoUrl.orEmpty(), span) }

        @Volatile var prepared = false

        @Synchronized
        fun ready() {
            if (prepared) return
            audio.prepare()
            videoUrl?.let(::pickVideo)
            prepared = true
            Diag.log(
                "cast",
                "session $token ready: ${item.mediaId} " +
                    "video=${rungs.describe() ?: layout?.describe() ?: remuxed?.describe ?: "none"} " +
                    "audio=processed ${audio.format.sampleRate}Hz ${audio.summary}",
            )
        }

        private fun pickVideo(url: String) {
            try {
                when {
                    !url.startsWith("http") -> localFile(File(pathOf(url)))
                    url.contains(YOUTUBE_STREAMS) && chooseRung(url) -> Unit
                    url.contains(YOUTUBE_STREAMS) ->
                        layout = StreamLayouts.probe { span -> Upstream.bytes(url, span) }
                            .takeIf { shows(it.codecs, it.height) }
                    else -> remoteFile(url)
                }
            } catch (e: UnreadableLayout) {
                refuse("in a format Totum cannot read for a TV", e.message ?: "unreadable")
            } catch (e: java.io.IOException) {
                Diag.warn("cast", "${item.mediaId}: could not look for a picture; sound only", e)
            }
        }

        private fun chooseRung(url: String): Boolean {
            if (ladder.isEmpty()) {
                Diag.log(
                    "cast",
                    "${item.mediaId}: no quality ladder came with this item, so the TV can be sent one picture " +
                        "only and a step-down has nowhere to go",
                )
            }
            return rungs.choose(url, ladder, tvs.current(item.mediaId)?.screen, ceiling)
        }

        private fun remoteFile(url: String) {
            videoReader = RangeReader { span -> Upstream.bytes(url, span) }
            use(VideoProbe.of(videoReader, item.mediaId), file = null)
        }

        private fun localFile(source: File) {
            val started = System.currentTimeMillis()
            videoReader = RangeReader { span -> LocalRanges.bytes(source, span) }
            try {
                use(VideoProbe.of(videoReader, item.mediaId), file = source)
                Diag.log(
                    "cast",
                    "${item.mediaId}: the downloaded file is cut for the TV as it asks, no repackaging first " +
                        "(read in ${System.currentTimeMillis() - started}ms)",
                )
            } catch (e: UnreadableLayout) {
                Diag.warn(
                    "cast",
                    "${item.mediaId}: cannot cut the downloaded file as the TV asks (${e.message}); " +
                        "repackaging it whole",
                )
                tvs.stage(item.mediaId, CastStage.REPACKAGING, 0)
                layout = localVideo(source)?.takeIf { shows(it.codecs, it.height) }
                tvs.stage(item.mediaId, CastStage.TV_STARTING)
            }
        }

        private fun use(found: FoundVideo, file: File?) {
            when (found) {
                is FoundVideo.Remuxed -> if (shows(found.video.codecs, found.video.height)) remuxed = found.video
                is FoundVideo.Fragmented -> layout = found.layout.takeIf { shows(it.codecs, it.height) }
                    ?.also { videoFile = file }
                is FoundVideo.NoPicture -> noteNoPicture(found.why)
            }
        }

        private fun noteNoPicture(why: String) =
            Diag.log("cast", "${item.mediaId}: no picture to send the TV — $why; sound only, nothing refused")

        private fun shows(codecs: String, height: Int?): Boolean {
            val screen = tvs.current(item.mediaId)?.screen
            if (screen != null && !screen.canDecode(codecs, height)) {
                val family = VideoCodecFamily.of(codecs)?.name ?: codecs
                refuse(height?.let { QualityLabel.of(it, codecs).text } ?: family, "beyond ${screen.label}")
                return false
            }
            if (latest[item.mediaId] === this && height != null) {
                videos.sent(item.mediaId, QualityLabel.of(height, codecs))
            }
            return true
        }

        private fun refuse(what: String, why: String) {
            videoFile = null
            if (latest[item.mediaId] === this) {
                videos.refused(item.mediaId, what)
            }
            Diag.warn("cast", "${item.mediaId}: the TV gets sound only — its video is $what ($why)")
        }

        private fun localVideo(source: File): StreamLayout? {
            val tools = tools ?: return null
            val durationUs = audio.durationUs.takeIf { it != UNKNOWN_US }
            val remuxed = VideoRemux.videoOnly(tools, source, "${item.mediaId}/$token", durationUs) { percent ->
                tvs.stage(item.mediaId, CastStage.REPACKAGING, percent)
            } ?: return null
            videoFile = remuxed
            return StreamLayouts.probe { span -> LocalRanges.bytes(remuxed, span) }
        }

        fun close() {
            audio.close()
            relay.close()
        }

        fun audioPart(part: String): HttpReply? {
            ready()
            val bytes = when {
                part == "init.mp4" -> audio.init()
                part.endsWith(".m4s") -> soundSegment(part.removeSuffix(".m4s").toInt())
                else -> return null
            }
            return HttpReply.Bytes(Http.OK, AUDIO_MP4, bytes)
        }

        private fun soundSegment(n: Int): ByteArray = try {
            audio.segment(n)
        } catch (e: IllegalStateException) {
            SoundDecoders.missing(e)?.let { mime ->
                Diag.warn("cast", "${item.mediaId}: this phone has no decoder for its sound ($mime); the strip says so")
                tvs.problem(CastProblem.SOUND_UNDECODABLE)
            }
            throw e
        }

        fun remuxedPart(part: String): HttpReply {
            ready()
            val source = remuxed
                ?: return HttpReply.Bytes(Http.NOT_FOUND, "text/plain", "no remuxed video".toByteArray())
            val bytes = if (part == "init.mp4") {
                source.init()
            } else {
                source.segment(part.removeSuffix(".m4s").toInt(), videoReader)
            }
            return HttpReply.Bytes(Http.OK, VIDEO_MP4, bytes)
        }

        fun manifest(): String {
            ready()
            val durationMs = audio.durationUs.takeIf { it != UNKNOWN_US }?.div(Http.MILLIS)
                ?: item.durationMs ?: 0L
            val ladder = rungs.rungs.mapIndexed { i, (rung, l) ->
                CastVideo.Relayed(l, "video/r$i", DashManifest.bandwidthFor(rung.url, l.height))
            }
            if (ladder.isNotEmpty()) {
                Diag.log(
                    "cast",
                    "${item.mediaId}: ladder offered at " +
                        ladder.joinToString { "${it.layout.height}p=${it.bandwidth / KBPS}kbps" },
                )
            }
            return DashManifest.build(
                durationMs,
                ladder.takeIf { it.isNotEmpty() }?.let { CastVideo.Ladder(it) }
                    ?: layout?.let { CastVideo.Relayed(it, "video") }
                    ?: remuxed?.let {
                        CastVideo.Segmented(
                            it.codecs,
                            it.width,
                            it.height,
                            it.timeline,
                            "video/init.mp4",
                            "video/\$Number\$.m4s",
                        )
                    },
                CastAudio.Processed(
                    initUrl = "audio/init.mp4",
                    mediaTemplate = "audio/\$Number\$.m4s",
                    sampleRate = audio.format.sampleRate,
                    channels = audio.format.channels,
                    codecs = audio.format.codecs,
                    segmentSamples = audio.segmentSamples,
                ),
                subtitles.mapIndexed { i, sub ->
                    CastSubtitle(
                        sub.language ?: "und",
                        "subtitles/$i.vtt",
                        sub.label ?: sub.language ?: "Subtitles",
                    )
                },
            )
        }
    }

    private data class Sources(
        val video: String?,
        val audio: String,
        val listening: Boolean,
        val ladder: List<CastRung> = emptyList(),
        val pictureWhy: String,
    )

    private fun sourcesFor(item: CastItem): Sources? {
        val uri = item.uri
        val separateAudio = item.separateAudioUrl
        val listening = item.listening
        val why = givenAsItIs(uri, separateAudio, listening)
        if (why != null) {
            Diag.log("cast", "${item.mediaId}: $why; the receiver is given it as it is")
            return null
        }
        val verdict = pictureVerdict(uri)
        return when {
            separateAudio != null && !listening ->
                Sources(uri, separateAudio, listening, item.ladder, "a separate video stream with its own audio")
            !listening && verdict.mayCarry -> Sources(uri, uri, listening, pictureWhy = verdict.why)
            separateAudio != null -> Sources(null, separateAudio, listening, pictureWhy = "listening")
            else -> Sources(
                null,
                uri,
                listening,
                pictureWhy = if (listening) "listening (the picture is off by choice)" else verdict.why,
            )
        }
    }

    private val preparing = ConcurrentHashMap.newKeySet<String>()

    private fun fromTheFile(videoUrl: String?, mediaId: String): List<CastSubtitleSource> {
        val tools = tools ?: return emptyList()
        if (videoUrl?.startsWith("http") == true) {
            Diag.log(
                "cast",
                "$mediaId: a streamed file's own subtitle tracks cannot be pulled out without reading all of it, " +
                    "so only sidecar subtitle files reach the TV",
            )
        }
        val path = videoUrl?.takeIf { !it.startsWith("http") }?.let(::pathOf) ?: return emptyList()
        val file = File(path).takeIf { it.extension.equals("mkv", true) || it.extension == "media" }
            ?: return emptyList()
        if (!EmbeddedSubtitles.isDone(tools, file)) {
            if (preparing.add(file.path)) {
                Diag.log(
                    "cast",
                    "$mediaId: the film's own subtitles are not saved yet; saving them now for the next cast"
                )
                Thread(
                    {
                        try {
                            EmbeddedSubtitles.prepare(tools, file)
                        } finally {
                            preparing.remove(file.path)
                        }
                    },
                    "embedded-subs"
                )
                    .start()
            }
            return emptyList()
        }
        return EmbeddedSubtitles.kept(tools, file).map { kept ->
            CastSubtitleSource(kept.file.path, kept.language, null)
        }.also { Diag.log("cast", "$mediaId: ${it.size} subtitle track(s) from the film itself") }
    }

    public fun urlFor(item: CastItem): String? {
        val manifest = LocalManifests.manifestAt(item.uri)
        val sources = if (manifest == null) sourcesFor(item) ?: return null else null
        val base = host.baseUrl(port) ?: run {
            Diag.warn("cast", "${item.mediaId}: no address the TV can reach (not on Wi-Fi?); given as it is")
            reach.noAddress()
            return null
        }
        if (manifest != null || sources == null) {
            server.start(port)
            host.hold(true)
            return manifest?.let { live.open(item.mediaId, it, base, random.token()) }
        }
        return open(item, sources, base)
    }

    public fun relayLive(item: CastItem, base: String): String? {
        val manifest = LocalManifests.manifestAt(item.uri) ?: return null
        server.start(port)
        return live.open(item.mediaId, manifest, base, random.token())
    }

    public fun release(mediaId: String) {
        val mine = sessions.entries.filter { it.value.item.mediaId == mediaId }
        mine.forEach { (token, session) ->
            sessions.remove(token)
            order.remove(token)
            session.close()
        }
        latest.remove(mediaId)
        videos.forget(mediaId)
        failures.forget(mediaId)
        Diag.log("cast", "$mediaId: released ${mine.size} session(s)")
    }

    private fun open(item: CastItem, sources: Sources, base: String): String {
        server.start(port)
        val token = random.token()
        val session = sessionFor(token, item, sources)
        tvs.stage(item.mediaId, CastStage.PREPARING)
        sessions[token] = session
        latest[item.mediaId] = session
        order.addLast(token)
        while (order.size > MAX_SESSIONS) order.pollFirst()?.let { sessions.remove(it)?.close() }
        host.hold(true)
        val url = "$base/c/$token/manifest.mpd"
        reach.handedOver(item.mediaId, token)
        Diag.log(
            "cast",
            "${item.mediaId} -> $url (video=${if (sources.video != null) "passed through" else "none"}, " +
                "picture because ${sources.pictureWhy}, " +
                "audio=processed from ${if (sources.audio.startsWith("http")) "stream" else "disk"}, " +
                "listening=${sources.listening}, " +
                "silences ${if (session.audio.timeline.cutsSilences) "cut" else "kept"})",
        )
        return url
    }

    private fun sessionFor(token: String, item: CastItem, sources: Sources): Session {
        val reload = steppingDown.remove(item.mediaId)
        if (!reload) {
            ceilings.remove(item.mediaId)
            videos.forget(item.mediaId)
        }
        return Session(token, item, sources.video, sources.audio, sources.ladder, ceilings[item.mediaId])
    }

    public fun stopAll() {
        reach.clear()
        failures.clear()
        live.clear()
        sessions.values.forEach { it.close() }
        sessions.clear()
        ceilings.clear()
        steppingDown.clear()
        order.clear()
        latest.clear()
        videos.clear()
        if (screens == null) {
            server.stop()
        } else {
            Diag.log(
                "cast",
                "still serving the PC screen page; the server stays up"
            )
        }
        host.hold(false)
    }

    @Suppress("TooGenericExceptionCaught")
    private fun handle(request: HttpRequest): HttpReply {
        val parts = request.path.substringBefore('?').trim('/').split('/')
        val session = parts.getOrNull(1)?.let(sessions::get)
        if (parts.firstOrNull() != "c" || session == null) {
            Diag.log("cast", "${request.from} asked for ${request.path}: no such session")
            return HttpReply.Bytes(Http.NOT_FOUND, "text/plain", "no such cast".toByteArray())
        }
        val what = parts.drop(2).joinToString("/")
        reach.reached(session.item.mediaId, request.from)
        val started = System.currentTimeMillis()
        val reply = try {
            replyFor(session, what, request) ?: return notFound()
        } catch (e: IOException) {
            noteFailure(session, what, e)
            throw e
        } catch (e: RuntimeException) {
            noteFailure(session, what, e)
            throw e
        }
        if (what != "video") {
            Diag.log(
                "cast",
                "${request.from} ${request.method} $what in ${System.currentTimeMillis() - started}ms"
            )
        }
        val answered = replyStatus(reply) < Http.CLIENT_ERROR
        if (answered) {
            if (what == "manifest.mpd" || what.startsWith("audio/")) failures.worked(session.item.mediaId)
            stageAfter(session, what)
        } else {
            Diag.warn(
                "cast",
                "${session.item.mediaId}: $what answered ${replyStatus(reply)}, so the stage is not marked done",
            )
        }
        return reply
    }

    private fun replyStatus(reply: HttpReply): Int = when (reply) {
        is HttpReply.Bytes -> reply.status
        is HttpReply.Stream -> reply.status
    }

    private fun replyFor(session: Session, what: String, request: HttpRequest): HttpReply? = when {
        what == "manifest.mpd" -> HttpReply.Bytes(Http.OK, Http.MPD, session.manifest().toByteArray())
        what.startsWith("video") -> videoPart(session, what, request.header("range"), request.method == "HEAD")
        what.startsWith("subtitles/") -> subtitle(session, what)
        what.startsWith("audio/") -> session.audioPart(what.removePrefix("audio/")) ?: notFound()
        else -> notFound()
    }

    private fun noteFailure(session: Session, what: String, cause: Throwable) {
        val ours = what == "manifest.mpd" || what.startsWith("audio/")
        if (ours && SoundDecoders.missing(cause) == null) failures.failed(session.item.mediaId, what, cause)
    }

    private fun stageAfter(session: Session, what: String) {
        when {
            session.flowing -> Unit
            what == "manifest.mpd" -> tvs.stage(session.item.mediaId, CastStage.TV_STARTING)
            what.startsWith("video") || what.startsWith("audio/") && what != "audio/init.mp4" -> {
                session.flowing = true
                tvs.stage(session.item.mediaId, null)
            }
        }
    }

    private fun subtitle(session: Session, what: String): HttpReply {
        val index = what.removePrefix("subtitles/").removeSuffix(".vtt").toIntOrNull()
        val config = index?.let(session.subtitles::getOrNull) ?: return notFound()
        val text = if (!config.location.startsWith("http")) {
            Diag.log("cast", "subtitles ${config.language} for ${session.item.mediaId}: from the film's own track")
            File(pathOf(config.location)).readText()
        } else {
            val reply = Upstream.get(config.location, null)
            Diag.log("cast", "subtitles ${config.language} for ${session.item.mediaId}: upstream ${reply.status}")
            if (reply.status != Http.OK) {
                return HttpReply.Stream(reply.status, "text/vtt", reply.contentLength, emptyMap(), reply::body)
            }
            reply.body().use { it.readBytes() }.toString(Charsets.UTF_8)
        }
        val asVtt = if (text.trimStart('﻿').startsWith("WEBVTT")) {
            text
        } else {
            Diag.log(
                "cast",
                "subtitles ${config.language} for ${session.item.mediaId}: SubRip text, made WebVTT for the TV",
            )
            EmbeddedSubtitles.srtToVtt(text)
        }
        val cleaned = TvCaptions.clean(asVtt)
        Diag.log(
            "cast",
            "subtitles ${config.language} for ${session.item.mediaId} cleaned for the TV, " +
                "placed at ${TvCaptions.SAFE_PLACE}: " +
                "cue settings removed ${cleaned.settingsRemoved.ifEmpty { "none" }}, " +
                "${cleaned.inlineTagsRemoved} word-timing tag(s) removed",
        )
        return HttpReply.Bytes(Http.OK, "text/vtt", cleaned.text.toByteArray(Charsets.UTF_8))
    }

    private fun videoPart(session: Session, what: String, range: String?, head: Boolean): HttpReply? = when {
        what.startsWith("video/r") -> what.removePrefix("video/r").toIntOrNull()?.let { i ->
            session.ready()
            session.rungs.urlOf(i)?.let { url ->
                session.rungs.fetched(i)
                session.relay.serve(url, range, head)
            }
        }
        what.startsWith("video/") -> session.remuxedPart(what.removePrefix("video/"))
        what == "video" -> session.videoFile?.let { file ->
            val n = ++session.videoRequests
            if (n <= VIDEO_LOG_FIRST || n % VIDEO_LOG_EVERY == 0) {
                Diag.log("cast", "video request #$n ($range) from the file ${file.name} (${file.length() / Http.KB}KB)")
            }
            LocalRanges.reply(file, range)
        }
            ?: session.videoUrl?.let { session.relay.serve(it, range, head) }
        else -> null
    }

    public companion object {
        private fun notFound() = HttpReply.Bytes(Http.NOT_FOUND, "text/plain", ByteArray(0))

        private data class PictureVerdict(val mayCarry: Boolean, val why: String)

        private fun pictureVerdict(uri: String): PictureVerdict {
            val picture = pictureAt(uri)
            val name = uri.substringBefore('?').substringBefore('#').substringAfterLast('/')
            return when {
                uri.contains(YOUTUBE_STREAMS) -> PictureVerdict(false, "a YouTube address that carries no picture")
                !uri.startsWith("http") -> PictureVerdict(true, "a file on the phone; its bytes will say ($name)")
                picture == Picture.NO -> PictureVerdict(false, "the file name says sound ($name)")
                picture == Picture.YES -> PictureVerdict(true, "the file name says video ($name)")
                else -> PictureVerdict(true, "the address names no file type ($name); its bytes will say")
            }
        }

        private fun givenAsItIs(uri: String, separateAudio: String?, listening: Boolean): String? = when {
            MANIFEST_HINTS.any(uri::contains) -> "a live or adaptive manifest"
            listening -> null
            separateAudio == null && uri.contains("googlevideo.com") && !uri.contains("mime=audio") ->
                "a single muxed stream"
            else -> null
        }

        private fun pathOf(url: String): String {
            if (!url.startsWith("file:")) return url
            return runCatching { URI(url).path }.getOrNull()?.takeIf { it.isNotEmpty() }
                ?: url.removePrefix("file://")
        }

        public const val DEFAULT_PORT: Int = 8765
        private const val MAX_SESSIONS = 6
        private const val VIDEO_LOG_FIRST = 3
        private const val VIDEO_LOG_EVERY = 25
        private val MANIFEST_HINTS = listOf(".m3u8", ".mpd", "/manifest/")
        private const val YOUTUBE_STREAMS = "googlevideo.com"
        private const val AUDIO_MP4 = "audio/mp4"
        private const val VIDEO_MP4 = "video/mp4"
    }
}

private fun SecureRandom.token(): String = ByteArray(
    TOKEN_BYTES
).also(::nextBytes).joinToString("") { "%02x".format(it) }

private const val TOKEN_BYTES = 8
private const val KBPS = 1000
