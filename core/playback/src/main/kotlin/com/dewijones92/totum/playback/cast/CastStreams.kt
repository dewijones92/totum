package com.dewijones92.totum.playback.cast

import android.content.Context
import androidx.annotation.OptIn
import androidx.core.net.toUri
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import com.dewijones92.totum.common.Diag
import com.dewijones92.totum.domain.VideoCodecFamily
import com.dewijones92.totum.playback.EXTRA_AUDIO_ONLY
import com.dewijones92.totum.playback.EXTRA_AUDIO_URL
import com.dewijones92.totum.playback.EXTRA_SKIP_SILENCE
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
    private val refusals = ConcurrentHashMap<String, String>()

    private val server = CastHttpServer(::handle)
    private val sessions = ConcurrentHashMap<String, Session>()
    private val order = ConcurrentLinkedDeque<String>()
    private val latest = ConcurrentHashMap<String, Session>()

    fun timelineFor(mediaId: String): CastTimeline? = latest[mediaId]?.audio?.timeline
    private val random = SecureRandom()

    private inner class Session(
        val token: String,
        val item: MediaItem,
        val videoUrl: String?,
        val audioSource: String,
    ) {
        val cuts = videoUrl == null && (itemSkipsSilence(item) ?: skipSilence())
        val audio = ProcessedAudio(context, audioSource, boost, "${item.mediaId}/$token", newCutter.takeIf { cuts })
        val subtitles = if (cuts) {
            emptyList()
        } else {
            item.localConfiguration?.subtitleConfigurations.orEmpty().filter { it.mimeType == MimeTypes.TEXT_VTT }
        }

        @Volatile var videoRequests = 0

        @Volatile var layout: StreamLayout? = null

        @Volatile var videoFile: File? = null

        @Volatile var prepared = false

        @Synchronized
        fun ready() {
            if (prepared) return
            audio.prepare()
            layout = videoUrl?.let(::video)
            prepared = true
            Diag.log(
                "cast",
                "session $token ready: ${item.mediaId} video=${layout?.describe() ?: "none"} " +
                    "audio=processed ${audio.format.sampleRate}Hz boost=${boost()}",
            )
        }

        private fun video(url: String): StreamLayout? {
            val found = try {
                if (url.startsWith("http")) {
                    StreamLayouts.probe { span -> Upstream.bytes(url, span) }
                } else {
                    localVideo(File(url.toUri().path ?: url))
                }
            } catch (e: UnreadableLayout) {
                return refuse("in a format Totum cannot read for a TV", e.message ?: "unreadable")
            } ?: return null
            val screen = tvs.current()?.screen
            if (screen != null && !screen.canDecode(found.codecs, found.height)) {
                val family = VideoCodecFamily.of(found.codecs)?.name ?: found.codecs
                return refuse("$family ${found.height}p", "beyond ${screen.label}")
            }
            if (latest[item.mediaId] === this && refusals.remove(item.mediaId) != null) tvs.refused(refusals.toMap())
            return found
        }

        private fun refuse(what: String, why: String): StreamLayout? {
            videoFile = null
            if (latest[item.mediaId] === this) {
                refusals[item.mediaId] = what
                tvs.refused(refusals.toMap())
            }
            Diag.warn("cast", "${item.mediaId}: the TV gets sound only — its video is $what ($why)")
            return null
        }

        private fun localVideo(source: File): StreamLayout? {
            val remuxed = VideoRemux.videoOnly(context, source, "${item.mediaId}/$token") ?: return null
            videoFile = remuxed
            return StreamLayouts.probe { span -> LocalRanges.bytes(remuxed, span) }
        }

        fun manifest(): String {
            ready()
            val durationMs = audio.durationUs.takeIf { it != C.TIME_UNSET }?.div(Http.MILLIS)
                ?: item.mediaMetadata.durationMs ?: 0L
            return DashManifest.build(
                durationMs,
                layout?.let { CastVideo(it, "video") },
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

    private data class Sources(val video: String?, val audio: String, val listening: Boolean)

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
            separateAudio != null && !listening -> Sources(uri, separateAudio, listening)
            !listening && !uri.startsWith("http") -> Sources(uri, uri, listening)
            separateAudio != null -> Sources(null, separateAudio, listening)
            else -> Sources(null, uri, listening)
        }
    }

    private fun itemSkipsSilence(item: MediaItem): Boolean? =
        item.requestMetadata.extras?.takeIf { it.containsKey(EXTRA_SKIP_SILENCE) }?.getBoolean(EXTRA_SKIP_SILENCE)

    private fun givenAsItIs(uri: String, separateAudio: String?, listening: Boolean): String? = when {
        MANIFEST_HINTS.any(uri::contains) -> "a live or adaptive manifest"
        listening -> null
        separateAudio == null && uri.contains("googlevideo.com") && !uri.contains("mime=audio") ->
            "a single muxed stream"
        else -> null
    }

    fun urlFor(item: MediaItem): String? {
        val sources = sourcesFor(item) ?: return null
        val base = host.baseUrl(PORT) ?: run {
            Diag.warn("cast", "${item.mediaId}: no address the TV can reach (not on Wi-Fi?); given as it is")
            return null
        }
        server.start(PORT)
        val token = ByteArray(TOKEN_BYTES).also(random::nextBytes).joinToString("") { "%02x".format(it) }
        val session = Session(token, item, sources.video, sources.audio)
        sessions[token] = session
        latest[item.mediaId] = session
        if (refusals.remove(item.mediaId) != null) tvs.refused(refusals.toMap())
        order.addLast(token)
        while (order.size > MAX_SESSIONS) order.pollFirst()?.let { sessions.remove(it)?.audio?.close() }
        host.hold(true)
        val url = "$base/c/$token/manifest.mpd"
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
        sessions.values.forEach { it.audio.close() }
        sessions.clear()
        order.clear()
        latest.clear()
        refusals.clear()
        tvs.refused(emptyMap())
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
        val started = System.currentTimeMillis()
        val reply = when {
            what == "manifest.mpd" -> HttpReply.Bytes(
                Http.OK,
                MimeTypes.APPLICATION_MPD,
                session.manifest().toByteArray()
            )
            what == "video" -> session.videoFile?.let { LocalRanges.reply(it, request.header("range")) }
                ?: proxy(session, session.videoUrl ?: return notFound(), request.header("range"))
            what == "audio/init.mp4" -> {
                session.ready()
                HttpReply.Bytes(Http.OK, MimeTypes.AUDIO_MP4, session.audio.init())
            }
            what.startsWith("subtitles/") -> subtitle(session, what)
            what.startsWith("audio/") && what.endsWith(".m4s") -> {
                session.ready()
                val n = what.removePrefix("audio/").removeSuffix(".m4s").toInt()
                HttpReply.Bytes(Http.OK, MimeTypes.AUDIO_MP4, session.audio.segment(n))
            }
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
        const val PORT: Int = 8765
        private const val MAX_SESSIONS = 6
        private const val VIDEO_LOG_FIRST = 3
        private const val VIDEO_LOG_EVERY = 25
        private const val TOKEN_BYTES = 8
        private val MANIFEST_HINTS = listOf(".m3u8", ".mpd", "/manifest/")
    }
}
