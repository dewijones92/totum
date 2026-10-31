package com.dewijones92.totum.domain

/**
 * The sub-kind of a video item, so a unified feed can tag each row instead of
 * splitting videos, live streams and Shorts onto separate pages. [STANDARD]
 * covers normal videos and every podcast (they are never live/short). [AUDIOBOOK] is a book's
 * chapter: talk that keeps your speed but never skips silence (ADR 105).
 */
public enum class MediaContentKind { STANDARD, LIVE, SHORT, MUSIC, AUDIOBOOK }
