package com.dewijones92.totum.domain

import java.time.Instant
import kotlin.time.Duration
import kotlin.time.Duration.Companion.days

public enum class CleanupGroup { NOT_OPENED, FINISHED, BIG, NOT_QUEUED }

public data class CleanupCandidate(
    val id: MediaItemId,
    val sizeBytes: Long,
    val downloadedAt: Instant?,
    val openedAt: Instant?,
    val finished: Boolean,
    val queued: Boolean,
) {
    val lastTouched: Instant? get() = listOfNotNull(downloadedAt, openedAt).maxOrNull()
}

public data class CleanupSuggestion(val group: CleanupGroup, val ids: List<MediaItemId>, val bytes: Long) {
    val count: Int get() = ids.size
}

public data class CleanupRules(
    val notOpenedFor: Duration = 30.days,
    val bigOverBytes: Long = BIG_OVER_MEGABYTES * BYTES_PER_MEGABYTE,
)

private const val BIG_OVER_MEGABYTES = 500L
private const val BYTES_PER_MEGABYTE = 1024L * 1024L

public fun suggestCleanup(
    candidates: List<CleanupCandidate>,
    now: Instant,
    rules: CleanupRules = CleanupRules(),
): List<CleanupSuggestion> {
    val stale = now.minusMillis(rules.notOpenedFor.inWholeMilliseconds)
    return CleanupGroup.entries.mapNotNull { group ->
        val members = candidates.filter { candidate ->
            when (group) {
                CleanupGroup.NOT_OPENED -> candidate.lastTouched?.let { !it.isAfter(stale) } ?: false
                CleanupGroup.FINISHED -> candidate.finished
                CleanupGroup.BIG -> candidate.sizeBytes > rules.bigOverBytes
                CleanupGroup.NOT_QUEUED -> !candidate.queued
            }
        }.sortedByDescending { it.sizeBytes }
        members.takeIf { it.isNotEmpty() }
            ?.let { CleanupSuggestion(group, it.map { m -> m.id }, it.sumOf { m -> m.sizeBytes }) }
    }
}
