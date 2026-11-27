package com.dewijones92.totum.cli.serve.cast

import com.dewijones92.totum.cli.serve.Rewrites
import com.dewijones92.totum.cli.serve.Routes
import com.dewijones92.totum.cli.serve.query
import com.dewijones92.totum.cli.serve.reply
import com.dewijones92.totum.common.Diag
import com.dewijones92.totum.data.jellyfin.JELLYFIN_ID
import com.dewijones92.totum.data.targets.SoundSettings
import com.dewijones92.totum.playback.VolumeBoost
import com.dewijones92.totum.playback.cast.CastItem
import com.dewijones92.totum.playback.cast.CastOutAnswer
import com.dewijones92.totum.playback.cast.CastOutJson
import com.dewijones92.totum.playback.cast.CastOutPaths
import com.dewijones92.totum.playback.cast.CastOutStart
import com.sun.net.httpserver.HttpExchange
import kotlinx.coroutines.runBlocking
import java.io.File
import java.net.URI
import java.net.URLDecoder
import java.security.SecureRandom

internal sealed interface HomeOnlyMapped {
    class To(val address: String) : HomeOnlyMapped

    class Refused(val why: String) : HomeOnlyMapped
}

internal class HomeOnlySources(
    private val homeHost: String,
    private val jellyfin: ((String) -> String)?,
    private val rewrites: Rewrites,
    private val fileOf: suspend (String) -> File?,
) {
    suspend fun map(url: String): HomeOnlyMapped {
        val uri = runCatching { URI(url) }.getOrNull()
        val path = uri?.rawPath.orEmpty()
        val stream = JELLYFIN_STREAM.matchEntire(path)
        return when {
            uri == null || !uri.host.equals(homeHost, ignoreCase = true) ->
                HomeOnlyMapped.Refused("that address is not on the home server")
            stream != null -> jellyfinAddress(stream.groupValues[1])
            path.startsWith(TORRENTS) -> torrentAddress(url)
            path == HOME_FILES -> homeFile(uri.rawQuery)
            else -> HomeOnlyMapped.Refused("the home server does not serve $path for a cast away from home")
        }
    }

    private fun jellyfinAddress(id: String): HomeOnlyMapped {
        val make = jellyfin ?: return HomeOnlyMapped.Refused("Jellyfin is not set up on the home server")
        if (!JELLYFIN_ID.matches(id)) return HomeOnlyMapped.Refused("that is not a Jellyfin item")
        return HomeOnlyMapped.To(make(id))
    }

    private fun torrentAddress(url: String): HomeOnlyMapped {
        val direct = rewrites.apply(url)
        return if (direct == url) {
            HomeOnlyMapped.Refused("the home server has no direct address for its torrents (TOTUM_REWRITE)")
        } else {
            HomeOnlyMapped.To(direct)
        }
    }

    private suspend fun homeFile(query: String?): HomeOnlyMapped {
        val id = query.orEmpty().split('&').firstOrNull { it.startsWith("id=") }?.removePrefix("id=")
            ?.let { URLDecoder.decode(it, Charsets.UTF_8) }
            ?: return HomeOnlyMapped.Refused("no item named for the home copy")
        val file = fileOf(id) ?: return HomeOnlyMapped.Refused("the home server has no copy of that item")
        return HomeOnlyMapped.To(file.path)
    }

    private companion object {
        val JELLYFIN_STREAM = Regex("^/jellyfin/stream/([0-9a-fA-F-]{8,40})(\\.[A-Za-z0-9]{1,5})?$")
        const val TORRENTS = "/ts/"
        const val HOME_FILES = "/fetch/files"
    }
}

internal class CastOutRoutes(
    private val casts: PublicCasts?,
    private val streams: PiStreams,
    private val sources: HomeOnlySources,
    private val random: SecureRandom = SecureRandom(),
) : Routes {

    override fun handles(path: String): Boolean = path == CastOutPaths.SESSION

    override fun handle(exchange: HttpExchange, method: String, path: String) = runBlocking {
        when (method) {
            "POST" -> start(exchange)
            "DELETE" -> end(exchange)
            else -> exchange.reply(NOT_FOUND, """{"error":"no such route"}""")
        }
    }

    private suspend fun start(exchange: HttpExchange) {
        val text = exchange.requestBody.readNBytes(MAX_BODY + 1)
        val request = text.takeIf { it.size <= MAX_BODY }?.toString(Charsets.UTF_8)?.let(CastOutJson::start)
        if (request == null || !MEDIA_ID.matches(request.mediaId)) {
            Diag.warn("cast-out", "dewidebug a public cast was asked for with an unreadable or oversized request")
            return exchange.reply(BAD_REQUEST, """{"error":"unreadable request"}""")
        }
        exchange.reply(OK, CastOutJson.encode(answer(request)))
    }

    private suspend fun answer(request: CastOutStart): CastOutAnswer {
        val gate = casts ?: return refused(request, "the home server is not set up to serve casts away from home")
        val video = sources.map(request.uri)
        val audio = request.separateAudioUrl?.let { sources.map(it) }
        val why = (video as? HomeOnlyMapped.Refused)?.why ?: (audio as? HomeOnlyMapped.Refused)?.why
        if (why != null) return refused(request, why)
        val session = "cast-out|${hex(SESSION_BYTES)}|${request.mediaId}"
        val sound = SoundSettings(request.skipSilence ?: false, VolumeBoost.fromStoredName(request.boost))
        val item = CastItem(
            mediaId = session,
            uri = (video as HomeOnlyMapped.To).address,
            separateAudioUrl = (audio as? HomeOnlyMapped.To)?.address,
            listening = request.listening,
            skipsSilence = request.skipSilence,
            durationMs = request.durationMs,
        )
        val tv = PiCastTv("a Chromecast away from home", screen = { null }) { request.model }
        val opened = streams.openForAnotherHost(tv, item, sound)
            ?: return refused(request, "the home server could not build a stream for it").also {
                streams.release(session)
            }
        val link = gate.open(session, opened.token, opened.file)
        Diag.log(
            "cast-out",
            "dewidebug ${request.mediaId}: opened for a Chromecast model \"${request.model ?: "unknown"}\" away from " +
                "home (session ${link.sessionId.take(SHOWN)}…, silence ${sound.skipSilence}, boost ${sound.boost}, " +
                "listening=${request.listening})",
        )
        return CastOutAnswer(CastOutAnswer.PUBLIC, link.url, link.sessionId, link.expiresAtMs)
    }

    private fun refused(request: CastOutStart, why: String): CastOutAnswer {
        Diag.warn("cast-out", "dewidebug ${request.mediaId}: not cast from the home server: $why")
        return CastOutAnswer.phone(why)
    }

    private fun end(exchange: HttpExchange) {
        val id = exchange.query("id")?.takeIf { SESSION_ID.matches(it) }
            ?: return exchange.reply(BAD_REQUEST, """{"error":"which session? (?id=)"}""")
        val revoked = casts?.revoke(id, "the phone ended the cast") ?: false
        exchange.reply(OK, """{"ok":true,"revoked":$revoked}""")
    }

    private fun hex(bytes: Int): String = ByteArray(
        bytes
    ).also(random::nextBytes).joinToString("") { "%02x".format(it) }

    private companion object {
        const val OK = 200
        const val BAD_REQUEST = 400
        const val NOT_FOUND = 404
        const val MAX_BODY = 16 * 1024
        const val SESSION_BYTES = 4
        const val SHOWN = 6
        val MEDIA_ID = Regex("^[A-Za-z0-9:_.\\-|~@+% ]{1,200}$")
        val SESSION_ID = Regex("^[0-9a-f]{32}$")
    }
}
