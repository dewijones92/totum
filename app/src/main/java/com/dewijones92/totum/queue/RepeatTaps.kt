package com.dewijones92.totum.queue

import com.dewijones92.totum.domain.MediaItemId

internal class RepeatTaps(private val clock: () -> Long, private val windowMs: Long = REPEAT_WINDOW_MS) {
    private var lastId: MediaItemId? = null
    private var lastAt = 0L

    fun repeatMs(id: MediaItemId): Long? {
        val now = clock()
        val since = now - lastAt
        val repeat = id == lastId && since < windowMs
        lastId = id
        lastAt = now
        return since.takeIf { repeat }
    }
}

private const val REPEAT_WINDOW_MS = 400L
