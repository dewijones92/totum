package com.dewijones92.totum.playback.cast

import com.dewijones92.totum.common.Diag
import com.dewijones92.totum.domain.CastScreen
import com.dewijones92.totum.domain.VideoCodecFamily
import java.net.URLDecoder
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

public data class ScreenReport(
    val positionMs: Long,
    val playing: Boolean,
    val buffering: Boolean,
    val ended: Boolean,
    val error: String?,
    val atMs: Long,
    val load: Long? = null,
)

public data class NowPlaying(val title: String, val by: String?, val playing: Boolean, val onScreen: String?)

public data class Screen(
    val id: String,
    val name: String,
    val seenAtMs: Long,
    val codecs: String = "",
    val height: Int? = null,
)

public enum class ScreenHost(public val word: String) {
    PHONE("phone"),
    PI("pi"),
}

@Suppress("TooManyFunctions")
public class ScreenLink(
    private val key: String?,
    private val page: () -> ByteArray,
    private val clock: () -> Long = System::currentTimeMillis,
    private val pollWaitMs: Long = POLL_WAIT_MS,
    private val host: ScreenHost = ScreenHost.PHONE,
) {
    private val lock = ReentrantLock()
    private val arrived = lock.newCondition()
    private val screens = ConcurrentHashMap<String, Screen>()
    private val sent = mutableMapOf<String, MutableList<Pair<Long, String>>>()
    private var next = 0L
    private var numbered = 0L

    @Volatile
    public var onReport: (String, ScreenReport) -> Unit = { _, _ -> }

    @Volatile
    public var onScreensChanged: (List<Screen>) -> Unit = {}

    @Volatile
    public var onWatch: (String) -> Unit = {}

    @Volatile
    public var onSeek: (String, Long) -> Unit = { _, _ -> }

    @Volatile
    public var nowPlaying: NowPlaying? = null

    public fun present(): List<Screen> = screens.values.filter {
        clock() - it.seenAtMs < GONE_AFTER_MS
    }.sortedBy { it.name }

    public fun screen(id: String): Screen? = screens[id]

    public fun heardWithin(id: String, ms: Long): Boolean = screens[id]?.let { clock() - it.seenAtMs < ms } == true

    public fun send(screenId: String, command: String): Long = lock.withLock {
        val number = ++numbered
        val queue = sent.getOrPut(screenId) { mutableListOf() }
        queue += number to command
        while (queue.size > KEPT_COMMANDS) queue.removeAt(0)
        arrived.signalAll()
        Diag.log(TAG, "screen $screenId <- #$number $command")
        number
    }

    public fun replyTo(request: HttpRequest): HttpReply? {
        val path = request.path.substringBefore('?').trimEnd('/')
        val pagePath = path == "/screen" || (key == null && path.isEmpty())
        if (!pagePath && !path.startsWith("/screen/")) return null
        if (pagePath) return HttpReply.Bytes(Http.OK, "text/html; charset=utf-8", pageForHost(), NO_CACHE)
        val query = queryOf(request.path)
        if (key != null && query["key"] != key) {
            Diag.warn(TAG, "${request.from} asked for $path with the wrong key; refused")
            return json(Http.CLIENT_ERROR, """{"error":"wrong key"}""")
        }
        return when (path) {
            "/screen/hello" -> hello(query, request.from)
            "/screen/poll" -> poll(query)
            "/screen/state" -> state(query)
            "/screen/watch" -> watch(query, request.from)
            "/screen/seek" -> seek(query, request.from)
            else -> json(Http.NOT_FOUND, """{"error":"no such call"}""")
        }
    }

    private fun hello(query: Map<String, String>, from: String): HttpReply {
        val id = query["id"]?.takeIf { it.isNotBlank() } ?: "screen-${++next}-${clock()}"
        val name = query["name"]?.take(NAME_CHARS)?.ifBlank { null } ?: "PC (browser)"
        val before = screens[id]
        if (before == null && !roomForAnother()) {
            Diag.warn(TAG, "$from said hello as $id, but $MAX_SCREENS screens are already here; refused")
            return json(Http.CLIENT_ERROR, """{"error":"too many screens"}""")
        }
        val codecs = query["codecs"].orEmpty()
        val height = query["height"]?.toDoubleOrNull()?.toInt()
        screens[id] = Screen(id, name, clock(), codecs, height)
        if (before == null) {
            Diag.log(
                TAG,
                "screen \"$name\" ($id) from $from said hello to the ${host.word} [codecs=$codecs height=$height]"
            )
        } else if (before.name != name) {
            Diag.log(TAG, "screen $id is now called \"$name\" (was \"${before.name}\")")
        }
        if (before == null || before.name != name) onScreensChanged(present())
        val after = lock.withLock { sent[id]?.lastOrNull()?.first ?: 0L }
        return json(
            Http.OK,
            """{"id":${quoted(id)},"after":$after,"host":${quoted(host.word)},"now":${nowJson(nowPlaying, id)}}""",
        )
    }

    private fun poll(query: Map<String, String>): HttpReply {
        val id = query["id"] ?: return json(Http.CLIENT_ERROR, """{"error":"no id"}""")
        val after = query["after"]?.toLongOrNull() ?: 0L
        touch(id)
        val waitUntil = clock() + pollWaitMs
        val due = lock.withLock {
            var waiting = pending(id, after)
            while (waiting.isEmpty() && clock() < waitUntil) {
                arrived.await((waitUntil - clock()).coerceAtLeast(1), TimeUnit.MILLISECONDS)
                waiting = pending(id, after)
            }
            waiting
        }
        return json(Http.OK, due.joinToString(",", "[", "]") { (n, command) -> """{"n":$n,"command":$command}""" })
    }

    private fun pending(id: String, after: Long) = sent[id].orEmpty().filter { it.first > after }

    private fun state(query: Map<String, String>): HttpReply {
        val id = query["id"] ?: return json(Http.CLIENT_ERROR, """{"error":"no id"}""")
        touch(id)
        val report = ScreenReport(
            positionMs = query["pos"]?.toLongOrNull() ?: 0L,
            playing = query["playing"] == "1",
            buffering = query["buffering"] == "1",
            ended = query["ended"] == "1",
            error = query["error"]?.ifBlank { null },
            atMs = clock(),
            load = query["load"]?.toLongOrNull(),
        )
        report.error?.let { Diag.warn(TAG, "screen $id reports an error: $it") }
        onReport(id, report)
        return json(Http.OK, """{"now":${nowJson(nowPlaying, id)}}""")
    }

    private fun watch(query: Map<String, String>, from: String): HttpReply {
        val id = query["id"] ?: return json(Http.CLIENT_ERROR, """{"error":"no id"}""")
        val screen = screens[id]
        if (screen == null) {
            Diag.warn(TAG, "Watch here from $from for screen $id, which has not said hello; refused")
            return json(Http.NOT_FOUND, """{"error":"say hello first"}""")
        }
        touch(id)
        Diag.log(
            TAG,
            "Watch here pressed on \"${screen.name}\" ($id) from $from; now=${nowPlaying ?: "nothing on the phone"}"
        )
        onWatch(id)
        return json(Http.OK, """{"asked":true}""")
    }

    private fun seek(query: Map<String, String>, from: String): HttpReply {
        val id = query["id"] ?: return json(Http.CLIENT_ERROR, """{"error":"no id"}""")
        val positionMs = query["pos"]?.toLongOrNull()?.takeIf { it >= 0 }
        if (screens[id] == null || positionMs == null) {
            Diag.warn(
                TAG,
                "seek from $from for screen $id refused (said hello=${screens[id] != null}, pos=${query["pos"]})"
            )
            return json(Http.CLIENT_ERROR, """{"error":"unknown screen or position"}""")
        }
        touch(id)
        Diag.log(TAG, "the seek bar on screen $id asks for ${positionMs}ms (from $from)")
        onSeek(id, positionMs)
        return json(Http.OK, """{"asked":true}""")
    }

    private fun roomForAnother(): Boolean {
        if (screens.size < MAX_SCREENS) return true
        screens.values.removeAll { clock() - it.seenAtMs >= GONE_AFTER_MS }
        return screens.size < MAX_SCREENS
    }

    private fun pageForHost(): ByteArray {
        val bytes = page()
        if (host == ScreenHost.PHONE) return bytes
        return String(bytes, Charsets.UTF_8).replace(HOST_MARKER, "data-host=\"${host.word}\"").toByteArray()
    }

    private fun touch(id: String) {
        screens.computeIfPresent(id) { _, screen -> screen.copy(seenAtMs = clock()) }
    }

    private fun json(status: Int, body: String) =
        HttpReply.Bytes(status, "application/json", body.toByteArray(), NO_CACHE)

    private companion object {
        const val TAG = "screen"
        const val POLL_WAIT_MS = 20_000L
        const val GONE_AFTER_MS = 60_000L
        const val KEPT_COMMANDS = 50
        const val NAME_CHARS = 40
        const val MAX_SCREENS = 32
        const val HOST_MARKER = "data-host=\"phone\""
        val NO_CACHE = mapOf("Cache-Control" to "no-store")
    }
}

private fun nowJson(now: NowPlaying?, id: String): String {
    if (now == null) return "null"
    return """{"title":${quoted(now.title)},"by":${now.by?.let(::quoted) ?: "null"},""" +
        """"playing":${now.playing},"here":${now.onScreen == id}}"""
}

public fun quoted(text: String): String = buildString {
    append('"')
    text.forEach { c ->
        when (c) {
            '"' -> append("\\\"")
            '\\' -> append("\\\\")
            '\n' -> append("\\n")
            else -> if (c < ' ') append("\\u%04x".format(c.code)) else append(c)
        }
    }
    append('"')
}

private fun queryOf(path: String): Map<String, String> =
    path.substringAfter('?', "").split('&').filter { '=' in it }.associate {
        URLDecoder.decode(it.substringBefore('='), "UTF-8") to URLDecoder.decode(it.substringAfter('='), "UTF-8")
    }

public fun screenProfile(screen: Screen?): CastScreen {
    val reported = screen?.codecs.orEmpty().split('+').mapNotNull { token ->
        val family = VideoCodecFamily.entries.firstOrNull { it.name == token.substringBefore(':') }
        family?.let { it to token.substringAfter(':', "").toIntOrNull() }
    }
    val families = reported.map { it.first }.toSet().ifEmpty { setOf(VideoCodecFamily.AVC) }
    val height = (screen?.height ?: DEFAULT_SCREEN_HEIGHT).coerceIn(MIN_SCREEN_HEIGHT, MAX_SCREEN_HEIGHT)
    val perCodec = reported.mapNotNull { (family, cap) -> cap?.let { family to it } }.toMap()
    return CastScreen(screen?.name ?: "a PC", families, height, known = false, codecHeights = perCodec)
}

private const val DEFAULT_SCREEN_HEIGHT = 1080
private const val MIN_SCREEN_HEIGHT = 480
private const val MAX_SCREEN_HEIGHT = 2160

public fun reportIsCurrent(reportLoad: Long?, currentLoad: Long): Boolean =
    reportLoad == null || reportLoad >= currentLoad
