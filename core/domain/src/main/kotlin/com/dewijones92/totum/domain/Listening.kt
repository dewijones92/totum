package com.dewijones92.totum.domain

public enum class Listening {
    MUSIC,
    TALK,
    ;

    public val other: Listening get() = if (this == MUSIC) TALK else MUSIC
}

public val MediaItem.listening: Listening
    get() = if (contentKind == MediaContentKind.MUSIC) Listening.MUSIC else Listening.TALK

public sealed interface SwitchTarget {
    public val why: String

    public data class JumpTo(val index: Int, override val why: String) : SwitchTarget

    public data class PlayRecentMusic(override val why: String) : SwitchTarget

    public data class Nowhere(override val why: String) : SwitchTarget
}

public fun switchTarget(
    queue: List<MediaItem>,
    current: Int,
    lastOf: Map<Listening, MediaItemId>,
): SwitchTarget {
    val playing = queue.getOrNull(current)
        ?: return SwitchTarget.Nowhere("nothing is playing, so there is nothing to switch from")
    val wanted = playing.listening.other
    val remembered = lastOf[wanted]?.let { id -> queue.indexOfFirst { it.id == id } }?.takeIf { it >= 0 }
    if (remembered != null) {
        return SwitchTarget.JumpTo(remembered, "back to the ${wanted.word} left at queue place ${remembered + 1}")
    }
    val ahead = (current + 1 until queue.size).firstOrNull { queue[it].listening == wanted }
    if (ahead != null) return SwitchTarget.JumpTo(ahead, "the next ${wanted.word} in the queue, place ${ahead + 1}")
    val behind = (0 until current).lastOrNull { queue[it].listening == wanted }
    if (behind != null) return SwitchTarget.JumpTo(behind, "the nearest ${wanted.word} above, place ${behind + 1}")
    return if (wanted == Listening.MUSIC) {
        SwitchTarget.PlayRecentMusic("no music in the queue")
    } else {
        SwitchTarget.Nowhere("no podcast or video in the queue")
    }
}

public val Listening.word: String get() = if (this == Listening.MUSIC) "music" else "talk"
