package com.dewijones92.totum.playback.cast

import com.dewijones92.totum.common.Diag
import java.net.HttpURLConnection
import java.net.URL

public object HomeLan {
    @Volatile private var load: () -> String? = { null }

    @Volatile private var save: (String) -> Unit = {}

    @Volatile private var held: String? = null

    public fun use(load: () -> String?, save: (String) -> Unit) {
        this.load = load
        this.save = save
        held = null
    }

    public fun remember(address: String) {
        val clean = address.trim().takeIf { it.isNotEmpty() } ?: return
        if (clean == address()) return
        held = clean
        save(clean)
        Diag.log("cast-out", "dewidebug the home network's own address for the Pi is now $clean")
    }

    public fun address(): String? = held ?: load()?.also { held = it }
}

public class HomeLanProbe(
    private val address: () -> String?,
    private val fetch: (url: String, timeoutMs: Int) -> String? = ::fetchText,
    private val now: () -> Long = System::currentTimeMillis,
    private val timeoutMs: Int = TIMEOUT_MS,
    private val keptMs: Long = KEPT_MS,
) {
    private var verdict: Boolean? = null
    private var judgedAt = Long.MIN_VALUE
    private var judgedFor: String? = null

    @Synchronized
    public fun onHomeLan(): Boolean? {
        val where = address() ?: run {
            Diag.log("cast-out", "dewidebug home network: no Pi address known yet, so it cannot be judged")
            return null
        }
        val at = now()
        if (judgedFor == where && at - judgedAt < keptMs) return verdict
        val began = at
        val answer = awaiting("home network probe", timeoutMs + PROBE_GRACE_MS) {
            fetch("http://$where${CastOutPaths.LAN_CHECK}", timeoutMs)
        }
        val home = answer?.trim() == CastOutPaths.LAN_CHECK_ANSWER
        verdict = home
        judgedAt = now()
        judgedFor = where
        Diag.log(
            "cast-out",
            "dewidebug home network: asked $where${CastOutPaths.LAN_CHECK} for ${now() - began}ms (limit " +
                "${timeoutMs}ms) and got ${answer?.take(ANSWER_SHOWN)?.let { "\"$it\"" } ?: "no answer"}, " +
                "so the phone is ${if (home) "on" else "NOT on"} the home network (kept ${keptMs / MS_PER_S}s)",
        )
        return home
    }

    public companion object {
        public const val TIMEOUT_MS: Int = 700
        public const val KEPT_MS: Long = 20_000L
        private const val ANSWER_SHOWN = 24
        private const val PROBE_GRACE_MS = 400L
        private const val MS_PER_S = 1_000L
    }
}

private const val MAX_ANSWER_BYTES = 64

private fun fetchText(url: String, timeoutMs: Int): String? {
    val connection = URL(url).openConnection() as HttpURLConnection
    connection.connectTimeout = timeoutMs
    connection.readTimeout = timeoutMs
    connection.instanceFollowRedirects = false
    return try {
        readAnswer(connection)
    } catch (e: java.io.IOException) {
        Diag.log("cast-out", "dewidebug home network probe of $url failed: ${e.javaClass.simpleName} ${e.message}")
        null
    } finally {
        connection.disconnect()
    }
}

private fun readAnswer(connection: HttpURLConnection): String? =
    if (connection.responseCode == HttpURLConnection.HTTP_OK) {
        connection.inputStream.use { it.readNBytes(MAX_ANSWER_BYTES).toString(Charsets.UTF_8) }
    } else {
        null
    }
