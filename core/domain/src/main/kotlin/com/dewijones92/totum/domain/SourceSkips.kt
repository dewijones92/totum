package com.dewijones92.totum.domain

import kotlin.time.Duration.Companion.milliseconds

public data class SourceSkips(val introEndMs: Long? = null, val outroLengthMs: Long? = null) {

    public val isEmpty: Boolean get() = introEndMs == null && outroLengthMs == null

    public fun segmentsFor(durationMs: Long?): List<SkipSegment> {
        val intro = introEndMs?.takeIf { it > 0 && (durationMs == null || it < durationMs) }
        val outroStart = durationMs?.let { duration ->
            outroLengthMs?.let { duration - it }?.takeIf { it > (intro ?: 0) && it < duration }
        }
        return listOfNotNull(
            intro?.let { SkipSegment(0.milliseconds, it.milliseconds) },
            outroStart?.let { SkipSegment(it.milliseconds, durationMs.milliseconds) },
        )
    }
}

public fun encodeSourceSkips(skips: Map<SourceId, SourceSkips>): String =
    skips.filterValues { !it.isEmpty }.entries.joinToString("\n") { (id, skip) ->
        "${id.value}\t${skip.introEndMs ?: "-"}\t${skip.outroLengthMs ?: "-"}"
    }

public fun decodeSourceSkips(encoded: String?): Map<SourceId, SourceSkips> =
    encoded.orEmpty().lineSequence().mapNotNull { line ->
        val parts = line.split('\t')
        if (parts.size != ENCODED_FIELDS || parts[0].isBlank()) return@mapNotNull null
        val skip = SourceSkips(parts[1].toLongOrNull(), parts[2].toLongOrNull())
        if (skip.isEmpty) null else SourceId(parts[0]) to skip
    }.toMap()

public fun MediaItem.showKey(): SourceId? = when (pillar) {
    MediaKind.VIDEO -> statedChannel()?.let { channel ->
        channel.youTubeChannelId?.let {
            SourceId("yt:$it")
        } ?: channel.id
    }
    MediaKind.PODCAST -> statedFeed()?.id
} ?: author?.trim()?.lowercase()?.takeIf { it.isNotEmpty() }?.let { SourceId("name:$it") }

private const val ENCODED_FIELDS = 3
