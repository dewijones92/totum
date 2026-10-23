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

internal class TailSpeechScans(
    private val weights: () -> SpeechWeights?,
    private val unmetered: () -> Boolean = { false },
) {
    private val asked = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
    private val found = java.util.concurrent.ConcurrentHashMap<String, Long>()
    private val worker = java.util.concurrent.Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "tail-speech-scan").apply {
            isDaemon = true
            priority = Thread.MIN_PRIORITY
        }
    }

    fun speechEnds(id: String): Long? = found[id]

    fun want(id: String, uri: String?, audioOnly: Boolean, positionMs: Long, durationMs: Long) {
        if (id in asked) return
        val source = tailSourceOf(uri, audioOnly, unmetered(), positionMs)
        if (source == TailSource.NotYet) return
        if (source is TailSource.None) {
            asked += id
            com.dewijones92.totum.common.Diag.log(
                TAG,
                "$id: its tail is not scanned for speech (${source.why}); the silent tail still applies",
            )
            return
        }
        val model = weights() ?: return
        asked += id
        val (where, what) = when (source) {
            is TailSource.File -> source.path to "file"
            is TailSource.Stream -> source.url to "audio-only stream, on an unmetered network"
            else -> return
        }
        com.dewijones92.totum.common.Diag.log(TAG, "$id: scanning its tail for speech, from its $what")
        worker.execute { scan(id, where, what, durationMs, model) }
    }

    private fun scan(id: String, where: String, what: String, durationMs: Long, model: SpeechWeights) {
        runCatching { scanTailForSpeech(where, durationMs, model) }
            .onSuccess { scan ->
                if (scan == null) {
                    com.dewijones92.totum.common.Diag.log(TAG, "$id: its $what's tail could not be scanned for speech")
                    return@onSuccess
                }
                scan.speechEndsMs?.let { found[id] = it }
                com.dewijones92.totum.common.Diag.log(
                    TAG,
                    "$id: scanned ${scan.heardMs}ms of its tail from ${scan.fromMs}ms of ${durationMs}ms in " +
                        "${scan.tookMs}ms (the app received ${scan.receivedBytes} bytes meanwhile, the player's " +
                        "own reads included); speech ends at ${scan.speechEndsMs}ms, so it ends " +
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

internal sealed interface TailSource {
    data class File(val path: String) : TailSource
    data class Stream(val url: String) : TailSource
    data object NotYet : TailSource
    data class None(val why: String) : TailSource
}

internal fun tailSourceOf(uri: String?, audioOnly: Boolean, unmetered: Boolean, playedMs: Long): TailSource {
    uri?.let(::localPathOf)?.let { return TailSource.File(it) }
    return when {
        uri == null || !(uri.startsWith("https:") || uri.startsWith("http:")) -> TailSource.None(
            "not a file or a web stream"
        )
        uri.substringBefore('?').substringAfterLast('.').lowercase() in MANIFESTS ->
            TailSource.None("it is a playlist or manifest, which MediaExtractor cannot read")
        !audioOnly -> TailSource.None("it streams with its picture, and reading its tail would fetch the video")
        !unmetered -> TailSource.None("it streams on a metered network")
        playedMs < STREAM_SCAN_AFTER_MS -> TailSource.NotYet
        else -> TailSource.Stream(uri)
    }
}

private const val STREAM_SCAN_AFTER_MS = 10_000L
private val MANIFESTS = setOf("m3u8", "mpd", "ism", "m3u")
