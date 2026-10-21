package com.dewijones92.totum.playback.cast

import android.content.Context
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import com.dewijones92.totum.common.Diag
import com.dewijones92.totum.playback.EXTRA_AUDIO_ONLY
import com.dewijones92.totum.playback.EXTRA_AUDIO_URL
import com.dewijones92.totum.playback.EXTRA_SKIP_SILENCE
import com.dewijones92.totum.playback.SilenceCuttingAudioProcessor
import com.dewijones92.totum.playback.VolumeBoost
import java.security.SecureRandom
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedDeque

@OptIn(markerClass = [UnstableApi::class])
internal class CastStreams(
    private val context: Context,
    private val boost: () -> VolumeBoost,
    private val skipSilence: () -> Boolean,
    private val newCutter: () -> SilenceCuttingAudioProcessor,
    private val baseUrl: () -> String?,
    private val onActive: (Boolean) -> Unit,
) {
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

        @Volatile var layout: StreamLayout? = null

        @Volatile var prepared = false

        @Synchronized
        fun ready() {
            if (prepared) return
            audio.prepare()
            layout = videoUrl?.let { StreamLayouts.probe { span -> Upstream.bytes(it, span) } }
            prepared = true
            Diag.log(
                "cast",
                "session $token ready: ${item.mediaId} video=${layout?.describe() ?: "none"} " +
                    "audio=processed ${audio.format.sampleRate}Hz boost=${boost()}",
            )
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
        separateAudio != null && !uri.startsWith("http") -> "video on disk is not cast with processing yet"
        else -> null
    }

    fun urlFor(item: MediaItem): String? {
        val sources = sourcesFor(item) ?: return null
        val base = baseUrl() ?: run {
            Diag.warn("cast", "${item.mediaId}: no address the TV can reach (not on Wi-Fi?); given as it is")
            return null
        }
        server.start(PORT)
        val token = ByteArray(TOKEN_BYTES).also(random::nextBytes).joinToString("") { "%02x".format(it) }
        val session = Session(token, item, sources.video, sources.audio)
        sessions[token] = session
        latest[item.mediaId] = session
        order.addLast(token)
        while (order.size > MAX_SESSIONS) order.pollFirst()?.let { sessions.remove(it)?.audio?.close() }
        onActive(true)
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
        server.stop()
        onActive(false)
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
            what == "video" -> proxy(session.videoUrl ?: return notFound(), request.header("range"))
            what == "audio/init.mp4" -> {
                session.ready()
                HttpReply.Bytes(Http.OK, MimeTypes.AUDIO_MP4, session.audio.init())
            }
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

    private fun notFound() = HttpReply.Bytes(Http.NOT_FOUND, "text/plain", ByteArray(0))

    private fun proxy(upstream: String, range: String?): HttpReply {
        val reply = Upstream.get(upstream, range)
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
        private const val TOKEN_BYTES = 8
        private val MANIFEST_HINTS = listOf(".m3u8", ".mpd", "/manifest/")
    }
}
