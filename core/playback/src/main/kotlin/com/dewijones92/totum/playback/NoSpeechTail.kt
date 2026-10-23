package com.dewijones92.totum.playback

internal fun endsWithNoSpeechLeft(previousMs: Long, positionMs: Long, durationMs: Long, speechEndsMs: Long?): Boolean {
    val endAt = (speechEndsMs ?: return false) + HEARD_OUT_MS
    val ranPastIt = previousMs < endAt && positionMs >= endAt && positionMs - previousMs <= RUN_NOT_SEEK_MS
    return ranPastIt && durationMs - positionMs > WORTH_ENDING_MS
}

internal fun landedPastBySeeking(previousMs: Long, positionMs: Long, speechEndsMs: Long): Boolean {
    val endAt = speechEndsMs + HEARD_OUT_MS
    return previousMs < endAt && positionMs >= endAt && positionMs - previousMs > RUN_NOT_SEEK_MS
}

private const val HEARD_OUT_MS = 2_500L
private const val WORTH_ENDING_MS = 5_000L
private const val RUN_NOT_SEEK_MS = 5_000L

internal class TailSpeechScans(private val weights: () -> SpeechWeights?) {
    private val asked = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
    private val found = java.util.concurrent.ConcurrentHashMap<String, Long>()
    private val worker = java.util.concurrent.Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "tail-speech-scan").apply {
            isDaemon = true
            priority = Thread.MIN_PRIORITY
        }
    }

    fun speechEnds(id: String): Long? = found[id]

    fun want(id: String, uri: String?, durationMs: Long) {
        if (id in asked) return
        val path = uri?.let(::localPathOf)
        if (path == null) {
            asked += id
            com.dewijones92.totum.common.Diag.log(
                TAG,
                "$id streams, so its tail is not scanned for speech; the silent tail still applies",
            )
            return
        }
        val model = weights() ?: return
        asked += id
        worker.execute { scan(id, path, durationMs, model) }
    }

    private fun scan(id: String, path: String, durationMs: Long, model: SpeechWeights) {
        runCatching { scanTailForSpeech(path, durationMs, model) }
            .onSuccess { scan ->
                if (scan == null) {
                    com.dewijones92.totum.common.Diag.log(TAG, "$id: its file's tail could not be scanned for speech")
                    return@onSuccess
                }
                scan.speechEndsMs?.let { found[id] = it }
                com.dewijones92.totum.common.Diag.log(
                    TAG,
                    "$id: scanned ${scan.heardMs}ms of its tail from ${scan.fromMs}ms of ${durationMs}ms in " +
                        "${scan.tookMs}ms; speech ends at ${scan.speechEndsMs}ms, so it ends " +
                        "${durationMs - (scan.speechEndsMs ?: durationMs)}ms early",
                )
            }
            .onFailure { com.dewijones92.totum.common.Diag.warn(TAG, "$id: scanning its tail for speech failed", it) }
    }

    private companion object {
        const val TAG = "silence"
    }
}

internal fun localPathOf(uri: String): String? = when {
    uri.startsWith("file:") -> runCatching { java.net.URI(uri).path }.getOrNull()
    uri.startsWith("/") -> uri
    else -> null
}
