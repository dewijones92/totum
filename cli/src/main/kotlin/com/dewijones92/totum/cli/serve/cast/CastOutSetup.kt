package com.dewijones92.totum.cli.serve.cast

import com.dewijones92.totum.cli.serve.filing.Jellyfin
import com.dewijones92.totum.cli.serve.rewritesFrom
import com.dewijones92.totum.common.Diag
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File
import java.net.URI

internal const val DEFAULT_PUBLIC_CAST_PORT = 8773
private const val SWEEP_MS = 60_000L

internal fun castOutFromEnv(
    scope: CoroutineScope,
    streams: PiStreams,
    jellyfin: Jellyfin?,
    fileOf: suspend (String) -> File?,
    env: (String) -> String? = System::getenv,
): CastOutRoutes {
    val base = env("TOTUM_CAST_PUBLIC_BASE")?.trim()?.trimEnd('/')?.takeIf { it.isNotEmpty() }
    val host = base?.takeIf { it.startsWith("https://") }?.let { runCatching { URI(it).host }.getOrNull() }
    if (base == null) {
        Diag.log("cast-out", "dewidebug TOTUM_CAST_PUBLIC_BASE is not set; casting away from home is OFF")
    } else if (host == null) {
        Diag.warn(
            "cast-out",
            "dewidebug TOTUM_CAST_PUBLIC_BASE is set but is not an https address with a host; casting away from " +
                "home is OFF",
        )
    }
    val casts = if (base != null && host != null) {
        PublicCasts(base, streams::serveSession, streams::release).also { gate ->
            val port = env("TOTUM_CAST_PUBLIC_PORT")?.toIntOrNull() ?: DEFAULT_PUBLIC_CAST_PORT
            PublicCastListener(gate).start(port)
            scope.launch {
                while (true) {
                    delay(SWEEP_MS)
                    gate.sweep()
                }
            }
            Diag.log("cast-out", "dewidebug casting away from home is ON: links are $base/<link>/manifest.mpd")
        }
    } else {
        null
    }
    val streamUrl = jellyfin?.let { j -> { id: String -> j.streamUrl(id) } }
    val rewrites = rewritesFrom(env("TOTUM_REWRITE"))
    return CastOutRoutes(casts, streams, HomeOnlySources(host.orEmpty(), streamUrl, rewrites, fileOf))
}
