package com.dewijones92.totum.playback.cast

import androidx.media3.common.MimeTypes
import com.dewijones92.totum.common.Diag
import com.dewijones92.totum.common.HttpUrl
import java.net.InetAddress

public object LocalManifests {
    private val manifests = object : LinkedHashMap<String, String>() {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, String>?) = size > KEPT
    }
    private val server = CastHttpServer(::reply, InetAddress.getByAddress(byteArrayOf(127, 0, 0, 1)))

    public fun publish(id: String, manifest: String): HttpUrl {
        synchronized(manifests) { manifests[id] = manifest }
        val port = server.start(PORT)
        return HttpUrl.of("http://127.0.0.1:$port/live/$id.mpd").also {
            Diag.log("live", "manifest for $id published at $it (${manifest.length} chars)")
        }
    }

    private fun reply(request: HttpRequest): HttpReply {
        val id = request.path.substringBefore('?').trim('/').removePrefix("live/").removeSuffix(".mpd")
        val manifest = synchronized(manifests) { manifests[id] }
            ?: return HttpReply.Bytes(Http.NOT_FOUND, "text/plain", "no manifest for $id".toByteArray()).also {
                Diag.warn("live", "asked for a manifest nobody published: ${request.path}")
            }
        return HttpReply.Bytes(Http.OK, MimeTypes.APPLICATION_MPD, manifest.toByteArray())
    }

    private const val PORT = 8766
    private const val KEPT = 8
}
