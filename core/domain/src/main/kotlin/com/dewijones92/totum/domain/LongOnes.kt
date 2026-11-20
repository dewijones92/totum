package com.dewijones92.totum.domain

import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes

public data class LongOnesRules(
    val minimum: Duration = 20.minutes,
    val limit: Int = 12,
    val perSource: Int = 2,
) {
    init {
        require(limit > 0) { "limit must be positive" }
        require(perSource > 0) { "perSource must be positive" }
    }
}

public data class LongOnesPick(
    val chosen: List<MediaItem>,
    val unknownLength: List<MediaItem>,
    val considered: Int,
    val tooShort: Int,
    val heldBack: Int,
)

public fun MediaItem.longOnesSource(): String = sourceUrl?.youTubeChannelId ?: sourceId.value

public fun MediaItem.canBeALongOne(): Boolean = publishedAt != null &&
    when (contentKind) {
        MediaContentKind.STANDARD, MediaContentKind.WAS_LIVE -> true
        MediaContentKind.LIVE, MediaContentKind.SHORT, MediaContentKind.MUSIC, MediaContentKind.AUDIOBOOK -> false
    } &&
    mediaUrl?.value?.contains("/shorts/") != true

public fun pickLongOnes(
    candidates: List<MediaItem>,
    lengthOf: (MediaItem) -> Duration?,
    rules: LongOnesRules = LongOnesRules(),
): LongOnesPick {
    val newestFirst = candidates.filter { it.canBeALongOne() }.distinctBy { it.id }
        .sortedByDescending { it.publishedAt }
    val chosen = mutableListOf<MediaItem>()
    val unknown = mutableListOf<MediaItem>()
    val taken = HashMap<String, Int>()
    var considered = 0
    var tooShort = 0
    var heldBack = 0
    for (item in newestFirst) {
        if (chosen.size >= rules.limit) break
        considered++
        val source = item.longOnesSource()
        val held = taken[source] ?: 0
        if (held >= rules.perSource) {
            heldBack++
            continue
        }
        val length = lengthOf(item)
        when {
            length == null -> unknown += item
            length < rules.minimum -> tooShort++
            else -> {
                chosen += item.copy(duration = length)
                taken[source] = held + 1
            }
        }
    }
    return LongOnesPick(chosen, unknown, considered, tooShort, heldBack)
}
