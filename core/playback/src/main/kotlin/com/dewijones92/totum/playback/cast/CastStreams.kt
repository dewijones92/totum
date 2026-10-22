package com.dewijones92.totum.playback.cast

import android.content.Context
import androidx.annotation.OptIn
import androidx.core.net.toUri
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import com.dewijones92.totum.common.Diag
import com.dewijones92.totum.domain.Picture
import com.dewijones92.totum.domain.VideoCodecFamily
import com.dewijones92.totum.domain.pictureAt
import com.dewijones92.totum.playback.EXTRA_AUDIO_ONLY
import com.dewijones92.totum.playback.EXTRA_AUDIO_URL
import com.dewijones92.totum.playback.EXTRA_SKIP_SILENCE
import com.dewijones92.totum.playback.EXTRA_VIDEO_LADDER
import com.dewijones92.totum.playback.SilenceCuttingAudioProcessor
import com.dewijones92.totum.playback.VolumeBoost
import java.io.File
import java.security.SecureRandom
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedDeque

@OptIn(markerClass = [UnstableApi::class])
internal class CastStreams(
    private val context: Context,
    private val boost: () -> VolumeBoost,
    private val skipSilence: () -> Boolean,
    private val newCutter: () -> SilenceCuttingAudioProcessor,
    private val host: CastHost,
    private val tvs: CastTvPort,
) {
    private val videos = TvVideos(tvs)

    private val server = CastHttpServer(handle = { request -> live.replyTo(request) ?: handle(request) })
    private val sessions = ConcurrentHashMap<String, Session>()
    private val order = ConcurrentLinkedDeque<String>()
    private val latest = ConcurrentHashMap<String, Session>()
    private val live = LiveCasts()

    fun timelineFor(mediaId: String): CastTimeline? = latest[mediaId]?.audio?.timeline
    private val random = SecureRandom()
    private val reach = CastReach(tvs)

    private inner class Session(
        val token: String,
        val item: MediaItem,
        val videoUrl: String?,
        val audioSource: String,
        val ladder: List<CastRung>,
    ) {
        val rungs = CastRungs(
            item.mediaId,
            onSent = { height -> if (latest[item.mediaId] === this) videos.sent(item.mediaId, height) },
            onFetching = { height -> if (latest[item.mediaId] === this) videos.fetching(item.mediaId, height) },
        )

        val cuts = videoUrl == null && (itemSkipsSilence(item) ?: skipSilence())
        val audio = ProcessedAudio(context, audioSource, boost, "${item.mediaId}/$token", newCutter.takeIf { cuts })
        val subtitles = if (cuts) {
            emptyList()
        } else {
            item.localConfiguration?.subtitleConfigurations.orEmpty().filter { it.mimeType == MimeTypes.TEXT_VTT }
        }

        @Volatile var videoRequests = 0

        @Volatile var layout: StreamLayout? = null

        @Volatile var mkv: MkvVideo? = null

        @Volatile var videoFile: File? = null

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
                    "video=${rungs.describe() ?: layout?.describe() ?: mkv?.describe ?: "none"} " +
                    "audio=processed ${audio.format.sampleRate}Hz boost=${boost()}",
            )
        }

        private fun pickVideo(url: String) {
            try {
                when {
                    !url.startsWith("http") ->
                        layout = localVideo(File(url.toUri().path ?: url))?.takeIf { shows(it.codecs, it.height) }
                    url.contains(YOUTUBE_STREAMS) && rungs.choose(url, ladder, tvs.current()?.screen) -> Unit
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

        private fun remoteFile(url: String) {
            val head = Upstream.bytes(url, ByteSpan(0, StreamLayouts.HEAD_BYTES - 1))
            when {
                Ebml.isEbml(head) -> {
                    val found = MkvVideo.probe(head) { span -> Upstream.bytes(url, span) }
                    when {
                        found == null -> noteNoPicture("an MKV with no video track")
                        shows(found.codecs, found.height) -> mkv = found
                    }
                }
                Mp4Layout.isMp4(head) && head.contains(ascii("vide")) ->
                    layout = try {
                        StreamLayouts.probe { span -> Upstream.bytes(url, span) }.takeIf { shows(it.codecs, it.height) }
                    } catch (e: UnreadableLayout) {
                        refuse("an MP4 Totum cannot stream to a TV yet (not fragmented)", e.message ?: "unreadable")
                        null
                    }
                else -> noteNoPicture("audio only, or not a WebM, MP4 or MKV")
            }
        }

        private fun noteNoPicture(why: String) =
            Diag.log("cast", "${item.mediaId}: no picture to send the TV — $why; sound only, nothing refused")

        private fun shows(codecs: String, height: Int?): Boolean {
            val screen = tvs.current()?.screen
            if (screen != null && !screen.canDecode(codecs, height)) {
                val family = VideoCodecFamily.of(codecs)?.name ?: codecs
                refuse("$family ${height}p", "beyond ${screen.label}")
                return false
            }
            if (latest[item.mediaId] === this) videos.sent(item.mediaId, height)
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
            val remuxed = VideoRemux.videoOnly(context, source, "${item.mediaId}/$token") ?: return null
            videoFile = remuxed
            return StreamLayouts.probe { span -> LocalRanges.bytes(remuxed, span) }
        }

        fun audioPart(part: String): HttpReply? {
            ready()
            val bytes = when {
                part == "init.mp4" -> audio.init()
                part.endsWith(".m4s") -> audio.segment(part.removeSuffix(".m4s").toInt())
                else -> return null
            }
            return HttpReply.Bytes(Http.OK, MimeTypes.AUDIO_MP4, bytes)
        }

        fun remuxedPart(part: String): HttpReply {
            ready()
            val source = mkv ?: return HttpReply.Bytes(Http.NOT_FOUND, "text/plain", "no remuxed video".toByteArray())
            val bytes = if (part == "init.mp4") {
                source.init()
            } else {
                source.segment(part.removeSuffix(".m4s").toInt()) { span -> Upstream.bytes(videoUrl.orEmpty(), span) }
            }
            return HttpReply.Bytes(Http.OK, MimeTypes.VIDEO_MP4, bytes)
        }

        fun manifest(): String {
            ready()
            val durationMs = audio.durationUs.takeIf { it != C.TIME_UNSET }?.div(Http.MILLIS)
                ?: item.mediaMetadata.durationMs ?: 0L
            val ladder = rungs.rungs.mapIndexed { i, (_, l) -> CastVideo.Relayed(l, "video/r$i") }
            return DashManifest.build(
                durationMs,
                ladder.takeIf { it.isNotEmpty() }?.let { CastVideo.Ladder(it) }
                    ?: layout?.let { CastVideo.Relayed(it, "video") }
                    ?: mkv?.let {
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
                        sub.label?.toString() ?: sub.language ?: "Subtitles",
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
    )

    private fun sourcesFor(item: MediaItem): Sources? {
        val uri = item.localConfiguration?.uri?.toString() ?: return null
        val extras = item.requestMetadata.extras
        val separateAudio = extras?.getString(EXTRA_AUDIO_URL)
        val listening = extras?.getBoolean(EXTRA_AUDIO_ONLY) == true
        val why = givenAsItIs(uri, separateAudio, listening)
        if (why != null) {
            Diag.log("cast", "${item.mediaId}: $why; the receiver is given it as it is")
            return null
        }
        return when {
            separateAudio != null && !listening -> Sources(uri, separateAudio, listening, ladderIn(extras))
            !listening && mayCarryVideo(uri) -> Sources(uri, uri, listening)
            separateAudio != null -> Sources(null, separateAudio, listening)
            else -> Sources(null, uri, listening)
        }
    }

    private fun itemSkipsSilence(item: MediaItem): Boolean? =
        item.requestMetadata.extras?.takeIf { it.containsKey(EXTRA_SKIP_SILENCE) }?.getBoolean(EXTRA_SKIP_SILENCE)

    fun urlFor(item: MediaItem): String? {
        val manifest = item.localConfiguration?.uri?.toString()?.let(LocalManifests::manifestAt)
        val sources = if (manifest == null) sourcesFor(item) ?: return null else null
        val base = host.baseUrl(PORT) ?: run {
            Diag.warn("cast", "${item.mediaId}: no address the TV can reach (not on Wi-Fi?); given as it is")
            reach.noAddress()
            return null
        }
        if (manifest != null || sources == null) {
            server.start(PORT)
            host.hold(true)
            return manifest?.let { live.open(item.mediaId, it, base, random.token()) }
        }
        server.start(PORT)
        val token = random.token()
        val session = Session(token, item, sources.video, sources.audio, sources.ladder)
        sessions[token] = session
        latest[item.mediaId] = session
        videos.forget(item.mediaId)
        order.addLast(token)
        while (order.size > MAX_SESSIONS) order.pollFirst()?.let { sessions.remove(it)?.audio?.close() }
        host.hold(true)
        val url = "$base/c/$token/manifest.mpd"
        reach.handedOver(item.mediaId, token)
        Diag.log(
            "cast",
            "${item.mediaId} -> $url (video=${if (sources.video != null) "passed through" else "none"}, " +
                "audio=processed from ${if (sources.audio.startsWith("http")) "stream" else "disk"}, " +
                "listening=${sources.listening}, " +
                "silences ${if (session.audio.timeline.cutsSilences) "cut" else "kept"})",
        )
        return url
    }

    fun stopAll() {
        reach.clear()
        live.clear()
        sessions.values.forEach { it.audio.close() }
        sessions.clear()
        order.clear()
        latest.clear()
        videos.clear()
        server.stop()
        host.hold(false)
    }

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
        val reply = when {
            what == "manifest.mpd" -> HttpReply.Bytes(
                Http.OK,
                MimeTypes.APPLICATION_MPD,
                session.manifest().toByteArray()
            )
            what.startsWith("video") -> videoPart(session, what, request.header("range")) ?: return notFound()
            what.startsWith("subtitles/") -> subtitle(session, what)
            what.startsWith("audio/") -> session.audioPart(what.removePrefix("audio/")) ?: notFound()
            else -> notFound()
        }
        if (what != "video") {
            Diag.log(
                "cast",
                "${request.from} ${request.method} $what in ${System.currentTimeMillis() - started}ms"
            )
        }
        return reply
    }

    private fun subtitle(session: Session, what: String): HttpReply {
        val index = what.removePrefix("subtitles/").removeSuffix(".vtt").toIntOrNull()
        val config = index?.let(session.subtitles::getOrNull) ?: return notFound()
        val reply = Upstream.get(config.uri.toString(), null)
        Diag.log("cast", "subtitles ${config.language} for ${session.item.mediaId}: upstream ${reply.status}")
        return HttpReply.Stream(reply.status, "text/vtt", reply.contentLength, emptyMap(), reply::body)
    }

    private fun notFound() = HttpReply.Bytes(Http.NOT_FOUND, "text/plain", ByteArray(0))

    private fun videoPart(session: Session, what: String, range: String?): HttpReply? = when {
        what.startsWith("video/r") -> what.removePrefix("video/r").toIntOrNull()?.let { i ->
            session.rungs.urlOf(i)?.let { url ->
                session.rungs.fetched(i)
                proxy(session, url, range)
            }
        }
        what.startsWith("video/") -> session.remuxedPart(what.removePrefix("video/"))
        what == "video" -> session.videoFile?.let { LocalRanges.reply(it, range) }
            ?: session.videoUrl?.let { proxy(session, it, range) }
        else -> null
    }

    private fun proxy(session: Session, upstream: String, range: String?): HttpReply {
        val asked = System.currentTimeMillis()
        val reply = Upstream.get(upstream, range)
        val n = ++session.videoRequests
        if (n <= VIDEO_LOG_FIRST || n % VIDEO_LOG_EVERY == 0) {
            Diag.log(
                "cast",
                "video request #$n ($range): upstream ${reply.status} in ${System.currentTimeMillis() - asked}ms",
            )
        }
        val extra = buildMap {
            put("Accept-Ranges", "bytes")
            reply.contentRange?.let { put("Content-Range", it) }
        }
        if (reply.failed) Diag.warn("cast", "video upstream answered ${reply.status} for range=$range")
        return HttpReply.Stream(reply.status, reply.contentType, reply.contentLength, extra, reply::body)
    }

    companion object {
        private fun ladderIn(extras: android.os.Bundle?): List<CastRung> =
            extras?.getStringArrayList(EXTRA_VIDEO_LADDER).orEmpty().mapNotNull { line ->
                val (height, codec, url) = line.split('\t', limit = LADDER_FIELDS).takeIf { it.size == LADDER_FIELDS }
                    ?: return@mapNotNull null
                height.toIntOrNull()?.let { CastRung(url, it, codec.ifEmpty { null }) }
            }

        private fun mayCarryVideo(uri: String): Boolean {
            if (uri.contains(YOUTUBE_STREAMS)) return false
            val picture = pictureAt(uri)
            if (picture == Picture.NO) return false
            val torrent = uri.contains("/stream/") && uri.contains("link=")
            return !uri.startsWith("http") || torrent || picture == Picture.YES
        }

        private fun givenAsItIs(uri: String, separateAudio: String?, listening: Boolean): String? = when {
            MANIFEST_HINTS.any(uri::contains) -> "a live or adaptive manifest"
            listening -> null
            separateAudio == null && uri.contains("googlevideo.com") && !uri.contains("mime=audio") ->
                "a single muxed stream"
            else -> null
        }

        const val PORT: Int = 8765
        private const val MAX_SESSIONS = 6
        private const val VIDEO_LOG_FIRST = 3
        private const val VIDEO_LOG_EVERY = 25
        private val MANIFEST_HINTS = listOf(".m3u8", ".mpd", "/manifest/")
        private const val LADDER_FIELDS = 3
        private const val YOUTUBE_STREAMS = "googlevideo.com"
    }
}

private fun SecureRandom.token(): String = ByteArray(
    TOKEN_BYTES
).also(::nextBytes).joinToString("") { "%02x".format(it) }

private const val TOKEN_BYTES = 8
