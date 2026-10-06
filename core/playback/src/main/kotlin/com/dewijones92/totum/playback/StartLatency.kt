package com.dewijones92.totum.playback

public object PlaybackIntent {
    @Volatile
    private var last: Pair<String, Long>? = null

    public fun mark(what: String, atMs: Long = nowMs()) {
        last = what to atMs
    }

    internal fun take(atMs: Long, withinMs: Long): Pair<String, Long>? {
        val intent = last ?: return null
        last = null
        return intent.takeIf { atMs - it.second in 0..withinMs }
    }

    internal fun nowMs(): Long = System.nanoTime() / NANOS_PER_MS

    private const val NANOS_PER_MS = 1_000_000L
}

internal class StartLatency(
    private val clock: () -> Long = PlaybackIntent::nowMs,
    private val intentWindowMs: Long = INTENT_WINDOW_MS,
) {
    private var itemId: String? = null
    private var playAt = 0L
    private var intent: Pair<String, Long>? = null
    private var soundSaid = false
    private var pictureSaid = false

    fun played(itemId: String) {
        val now = clock()
        this.itemId = itemId
        playAt = now
        intent = PlaybackIntent.take(now, intentWindowMs)
        soundSaid = false
        pictureSaid = false
    }

    fun playing(itemId: String): String? {
        if (itemId != this.itemId || soundSaid) return null
        soundSaid = true
        return line("first sound", itemId)
    }

    fun firstFrame(itemId: String): String? {
        if (itemId != this.itemId || pictureSaid) return null
        pictureSaid = true
        return line("first picture", itemId)
    }

    private fun line(what: String, itemId: String): String {
        val now = clock()
        val sincePlay = "$what for $itemId ${now - playAt}ms after play()"
        val tapped = intent?.let { (label, at) -> ", ${now - at}ms after \"$label\" (play() came ${playAt - at}ms in)" }
        return sincePlay + tapped.orEmpty()
    }

    private companion object {
        const val INTENT_WINDOW_MS = 60_000L
    }
}
