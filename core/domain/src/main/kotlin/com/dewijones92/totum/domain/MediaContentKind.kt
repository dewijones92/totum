package com.dewijones92.totum.domain

/**
 * The sub-kind of a video item, so a unified feed can tag each row instead of
 * splitting videos, live streams and Shorts onto separate pages. [STANDARD]
 * covers normal videos and every podcast (they are never live/short).
 */
public enum class MediaContentKind { STANDARD, LIVE, SHORT, MUSIC }
