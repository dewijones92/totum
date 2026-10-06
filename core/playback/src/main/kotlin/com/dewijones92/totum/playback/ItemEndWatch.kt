package com.dewijones92.totum.playback

internal class ItemEndWatch(
    private val tailMs: Long = TAIL_MS,
    private val maxGapMs: Long = MAX_GAP_MS,
    private val keep: Int = KEEP,
) {
    enum class Reason(val word: String) { ENDED("ended"), REPLACED("replaced") }

    private var itemId: String? = null
    private var listedMs: Long? = null
    private var tailFromMs: Long? = null
    private var tailPlayedWallMs = 0L
    private var lastWallMs: Long? = null
    private var segmentSkips = 0
    private var tailSegmentSkips = 0
    private val recent = ArrayDeque<String>()

    val lastEnds: List<String> get() = recent.toList()

    fun start(itemId: String, listedMs: Long?) {
        this.itemId = itemId
        this.listedMs = listedMs
        tailFromMs = null
        tailPlayedWallMs = 0L
        lastWallMs = null
        segmentSkips = 0
        tailSegmentSkips = 0
    }

    fun tick(wallMs: Long, positionMs: Long, durationMs: Long?) {
        if (itemId == null || durationMs == null || durationMs <= 0) return
        if (tailFromMs == null) {
            if (positionMs < durationMs - tailMs) return
            tailFromMs = positionMs
        } else {
            tailPlayedWallMs += playedSince(wallMs)
        }
        lastWallMs = wallMs
    }

    fun segmentSkipped(fromMs: Long, durationMs: Long?) {
        segmentSkips++
        if (durationMs != null && fromMs >= durationMs - tailMs) tailSegmentSkips++
    }

    fun end(reason: Reason, end: EndFacts): String? {
        val id = itemId ?: return null
        val from = tailFromMs
        if (from != null) tailPlayedWallMs += playedSince(end.wallMs)
        val line = buildString {
            append("item-end ").append(id).append(" reason=").append(reason.word)
            append(" at=").append(end.positionMs).append("ms player=").append(end.durationMs ?: -1).append("ms")
            append(" listed=").append(listedMs ?: -1).append("ms")
            append(" | ").append(tail(from, end))
            append(" | skipSilence=").append(end.skipSilence).append(" mode=").append(end.silenceMode)
            append(" speed=").append(end.speed)
            append(" sponsorSkips=").append(segmentSkips).append(" (in last ").append(tailMs / MS_PER_S)
            append("s: ").append(tailSegmentSkips).append(")")
        }
        itemId = null
        recent.addLast(line)
        while (recent.size > keep) recent.removeFirst()
        return line
    }

    private fun tail(from: Long?, end: EndFacts): String {
        if (from == null) return "never reached the last ${tailMs / MS_PER_S}s"
        val media = (end.positionMs - from).coerceAtLeast(0)
        val heard = (tailPlayedWallMs * end.speed).toLong()
        return "last ${media}ms of media took ${tailPlayedWallMs}ms playing (${heard}ms at ${end.speed}x): " +
            "jumped ${media - heard}ms"
    }

    private fun playedSince(wallMs: Long): Long {
        val last = lastWallMs ?: return 0L
        return (wallMs - last).coerceIn(0, maxGapMs)
    }

    data class EndFacts(
        val wallMs: Long,
        val positionMs: Long,
        val durationMs: Long?,
        val speed: Float,
        val skipSilence: Boolean,
        val silenceMode: String,
    )

    private companion object {
        const val TAIL_MS = 10_000L
        const val MAX_GAP_MS = 1_000L
        const val KEEP = 5
        const val MS_PER_S = 1_000L
    }
}
