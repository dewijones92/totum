package com.dewijones92.totum.playback

private const val FRAMES_NAMED = 3

private val OWN_FRAMES = listOf(
    "com.dewijones92.totum.playback.PlaybackRequesterKt",
    "com.dewijones92.totum.playback.Media3PlaybackController",
    "java.",
    "kotlin.",
    "jdk.",
)

internal fun requestedFrom(): String =
    Thread.currentThread().stackTrace
        .filterNot { frame -> OWN_FRAMES.any { frame.className.startsWith(it) } }
        .take(FRAMES_NAMED)
        .joinToString(" <- ") { "${it.className}.${it.methodName}:${it.lineNumber}" }
        .ifEmpty { "no app frame (a deferred call)" }
