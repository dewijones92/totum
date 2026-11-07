package com.dewijones92.totum.domain

/**
 * The sub-kind of a video item, so a unified feed can tag each row instead of
 * splitting videos, live streams and Shorts onto separate pages. [STANDARD]
 * covers normal videos and every podcast (they are never live/short). [AUDIOBOOK] is a book's
 * chapter: talk that keeps your speed but never skips silence (ADR 105).
 */
public enum class MediaContentKind { STANDARD, LIVE, SHORT, MUSIC, AUDIOBOOK, WAS_LIVE }

public fun MediaContentKind.skipsSilence(asked: Boolean): Boolean = when (this) {
    MediaContentKind.MUSIC, MediaContentKind.LIVE, MediaContentKind.AUDIOBOOK -> false
    MediaContentKind.STANDARD, MediaContentKind.SHORT, MediaContentKind.WAS_LIVE -> asked
}

public fun MediaContentKind.playsAtSpeed(chosen: Float): Float = when (this) {
    MediaContentKind.MUSIC, MediaContentKind.LIVE -> 1f
    MediaContentKind.AUDIOBOOK, MediaContentKind.STANDARD, MediaContentKind.SHORT, MediaContentKind.WAS_LIVE -> chosen
}
