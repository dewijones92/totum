package com.dewijones92.totum.queue

import com.dewijones92.totum.common.Diag
import com.dewijones92.totum.domain.Refusal
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

data class RefusedTap(val title: String, val reason: Refusal?)

internal class RefusedTaps {
    private var last: Refusal? = null
    private val emitted = MutableSharedFlow<RefusedTap>(extraBufferCapacity = BUFFER)
    val taps: SharedFlow<RefusedTap> = emitted.asSharedFlow()

    fun clear() {
        last = null
    }

    fun note(reason: Refusal?) {
        last = reason
    }

    fun announce(title: String) {
        val why = last ?: "it resolved to nothing"
        Diag.warn("queue", "told the user \"${title.take(TITLE_CHARS)}\" would not play ($why)")
        emitted.tryEmit(RefusedTap(title, last))
    }

    private companion object {
        const val BUFFER = 4
        const val TITLE_CHARS = 60
    }
}
