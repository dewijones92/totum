package com.dewijones92.totum.playback.cast

import com.dewijones92.totum.common.Diag
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedDeque

internal class SharedPictures {
    private class Shared(val file: File, val type: String)

    private val shared = ConcurrentHashMap<String, Shared>()
    private val order = ConcurrentLinkedDeque<String>()

    fun open(file: File, base: String, token: String): String? {
        val type = typeOf(file)
        if (type == null) {
            Diag.warn(
                "cast",
                "dewidebug picture ${file.name}: not a picture a browser shows (${file.extension}); not shared",
            )
            return null
        }
        if (!file.isFile) {
            Diag.warn("cast", "dewidebug picture ${file.name}: no such file on this phone; not shared")
            return null
        }
        shared[token] = Shared(file, type)
        order.addLast(token)
        while (order.size > MAX_SHARED) order.pollFirst()?.let(shared::remove)
        Diag.log(
            "cast",
            "dewidebug picture ${file.name} ($type, ${file.length()} bytes) shared as $token; ${shared.size} held",
        )
        return "$base/p/$token/picture.${file.extension.lowercase()}"
    }

    fun replyTo(request: HttpRequest): HttpReply? {
        val parts = request.path.substringBefore('?').trim('/').split('/')
        if (parts.firstOrNull() != "p") return null
        val found = parts.getOrNull(1)?.let(shared::get)
        if (found == null) {
            Diag.warn("cast", "dewidebug ${request.from} asked for ${request.path}: no such picture shared")
            return HttpReply.Bytes(Http.NOT_FOUND, "text/plain", "no such picture".toByteArray())
        }
        Diag.log(
            "cast",
            "dewidebug ${request.from} ${request.method} picture ${found.file.name} range=${request.header("range")}",
        )
        return LocalRanges.reply(found.file, request.header("range"), found.type)
    }

    fun clear() {
        shared.clear()
        order.clear()
    }

    companion object {
        private const val MAX_SHARED = 16

        private val TYPES = mapOf(
            "jpg" to "image/jpeg",
            "jpeg" to "image/jpeg",
            "png" to "image/png",
            "webp" to "image/webp",
            "gif" to "image/gif",
        )

        fun typeOf(file: File): String? = TYPES[file.extension.lowercase()]
    }
}
