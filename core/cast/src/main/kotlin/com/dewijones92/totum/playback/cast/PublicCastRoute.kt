package com.dewijones92.totum.playback.cast

import com.dewijones92.totum.common.Diag
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

public sealed interface PublicCastPick {
    public data class Public(val url: String, val sessionId: String, val expiresAtMs: Long?) : PublicCastPick

    public data class Phone(val why: String) : PublicCastPick
}

public enum class PublicCastAudience(internal val phrase: String) {
    CHROMECAST("the friend's Chromecast"),
    PC_BROWSER("the PC's browser"),
}

public data class RoutedStream(val url: String, val direct: Boolean)

public fun PublicCastRoute?.routedStream(
    item: CastItem,
    model: String?,
    boost: String?,
    audience: PublicCastAudience,
    onPublic: () -> Unit = {},
    phoneDirect: () -> String?,
): RoutedStream? {
    val pick = this?.pick(item, model, boost, audience)
    return if (pick is PublicCastPick.Public) {
        onPublic()
        RoutedStream(pick.url, direct = true)
    } else {
        phoneDirect()?.let { RoutedStream(it, direct = false) }
    }
}

public interface PublicCastApi {
    public fun start(request: CastOutStart): CastOutAnswer?

    public fun end(sessionIds: List<String>)
}

public class PublicCastRoute(
    private val enabled: () -> Boolean,
    private val homeHost: () -> String?,
    private val signedIn: () -> Boolean,
    private val onHomeLan: () -> Boolean?,
    private val api: PublicCastApi,
    private val inTheBackground: (() -> Unit) -> Unit = ::runInTheBackground,
) {
    private val minted = ConcurrentHashMap<String, String>()

    public fun pick(
        item: CastItem,
        model: String?,
        boost: String?,
        audience: PublicCastAudience = PublicCastAudience.CHROMECAST,
    ): PublicCastPick {
        val phone = phoneReason(item)
        if (phone != null) {
            Diag.log(
                "cast-out",
                "dewidebug ${item.mediaId}: the phone serves it to ${audience.phrase}, because $phone",
            )
            return PublicCastPick.Phone(phone)
        }
        val answer = api.start(
            CastOutStart(
                mediaId = item.mediaId,
                uri = item.uri,
                separateAudioUrl = item.separateAudioUrl,
                listening = item.listening,
                skipSilence = false.also {
                    if (item.skipsSilence == true) {
                        Diag.log(
                            "cast-out",
                            "dewidebug ${item.mediaId}: silence skipping is left off here, because the phone cannot " +
                                "map the TV's clock back to the episode's for a stream the home server cuts",
                        )
                    }
                },
                boost = boost,
                durationMs = item.durationMs,
                model = model,
            ),
        )
        val url = answer?.url?.takeIf { answer.route == CastOutAnswer.PUBLIC && answer.sessionId != null }
        if (answer == null || url == null) {
            val why = answer?.why ?: "the home server did not answer in time"
            Diag.warn(
                "cast-out",
                "dewidebug ${item.mediaId}: the home server would not cast it itself ($why); " +
                    "the phone serves it to ${audience.phrase}",
            )
            return PublicCastPick.Phone(why)
        }
        val sessionId = answer.sessionId.orEmpty()
        val earlier = minted.put(item.mediaId, sessionId)
        if (earlier != null && earlier != sessionId) inTheBackground { api.end(listOf(earlier)) }
        Diag.log(
            "cast-out",
            "dewidebug ${item.mediaId}: ${audience.phrase} fetches it from the home server " +
                "(session ${sessionId.take(SHOWN)}…, model ${model ?: "unknown"}, " +
                "expires ${answer.expiresAtMs ?: "at an unstated time"}); replaced ${earlier?.take(SHOWN) ?: "none"}",
        )
        return PublicCastPick.Public(url, sessionId, answer.expiresAtMs)
    }

    public fun endAll() {
        val ids = minted.values.toList()
        minted.clear()
        if (ids.isEmpty()) {
            Diag.log("cast-out", "dewidebug the cast ended with no public session to end")
            return
        }
        Diag.log("cast-out", "dewidebug the cast ended; ending ${ids.size} public session(s) on the home server")
        inTheBackground { api.end(ids) }
    }

    private fun phoneReason(item: CastItem): String? {
        val host = homeHost()
        val homeOnly = HomeOnly.why(item.uri, item.mediaId, host)
        return when {
            !enabled() -> "casting from home is switched off in Settings"
            homeOnly == null -> "it does not come from the home server, so the phone can fetch it itself"
            !signedIn() -> "this phone is not signed in to the home server ($homeOnly)"
            else -> when (onHomeLan()) {
                true -> "the phone is on the home network, where it serves home items as before ($homeOnly)"
                null -> "it is not known whether the phone is at home yet, so it serves it as before ($homeOnly)"
                false -> null
            }
        }
    }

    private companion object {
        const val SHOWN = 6
    }
}

public class HttpPublicCastApi(
    private val host: () -> String?,
    private val headers: () -> Map<String, String>,
    private val scheme: String = "https",
    private val connectMs: Int = CONNECT_MS,
    private val readMs: Int = READ_MS,
) : PublicCastApi {

    override fun start(request: CastOutStart): CastOutAnswer? {
        val where = url() ?: return null
        val body = CastOutJson.encode(request).toByteArray()
        return awaiting("cast-out-start", connectMs + readMs + GRACE_MS) {
            call("POST", where, body)?.let(CastOutJson::answer)
        }
    }

    override fun end(sessionIds: List<String>) {
        val base = url() ?: return
        sessionIds.forEach { id ->
            val result = awaiting("cast-out-end", connectMs + readMs + GRACE_MS) {
                call("DELETE", "$base?id=$id", null)
            }
            Diag.log("cast-out", "dewidebug ending public session ${id.take(SHOWN)}… answered ${result ?: "nothing"}")
        }
    }

    private fun url(): String? =
        host()?.takeIf { it.isNotBlank() }?.let { "$scheme://$it/fetch${CastOutPaths.SESSION}" }

    private fun call(method: String, url: String, body: ByteArray?): String? {
        val connection = URL(url).openConnection() as HttpURLConnection
        return try {
            connection.requestMethod = method
            connection.connectTimeout = connectMs
            connection.readTimeout = readMs
            connection.instanceFollowRedirects = false
            headers().forEach { (name, value) -> connection.setRequestProperty(name, value) }
            if (body != null) {
                connection.doOutput = true
                connection.setRequestProperty("Content-Type", "application/json")
                connection.outputStream.use { it.write(body) }
            }
            val code = connection.responseCode
            if (code == HttpURLConnection.HTTP_OK) {
                connection.inputStream.use { it.readBytes().toString(Charsets.UTF_8) }
            } else {
                Diag.warn("cast-out", "dewidebug $method ${connection.url.path} answered HTTP $code")
                null
            }
        } catch (e: IOException) {
            Diag.warn("cast-out", "dewidebug $method ${connection.url.path} failed: ${e.javaClass.simpleName}", e)
            null
        } finally {
            connection.disconnect()
        }
    }

    private companion object {
        const val CONNECT_MS = 2_500
        const val READ_MS = 4_000
        const val GRACE_MS = 500L
        const val SHOWN = 6
    }
}

private val offMain = Executors.newCachedThreadPool { runnable ->
    Thread(runnable, "cast-out").apply { isDaemon = true }
}

internal fun <T> awaiting(what: String, limitMs: Long, work: () -> T?): T? {
    val job = offMain.submit<T?> { work() }
    return try {
        job.get(limitMs, TimeUnit.MILLISECONDS)
    } catch (e: TimeoutException) {
        job.cancel(true)
        Diag.warn("cast-out", "dewidebug $what gave up after ${limitMs}ms", e)
        null
    } catch (e: java.util.concurrent.ExecutionException) {
        Diag.warn("cast-out", "dewidebug $what failed", e.cause ?: e)
        null
    }
}

private fun runInTheBackground(work: () -> Unit) {
    offMain.execute(work)
}
