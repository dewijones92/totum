package com.dewijones92.totum.queue

import com.dewijones92.totum.domain.MediaItemId
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.transformLatest

internal class StreamingIntent(
    private val wanted: Flow<MediaItemId?>,
    private val graceMs: Long = LIFT_GRACE_MS,
) {
    private val streaming = MutableStateFlow<MediaItemId?>(null)
    private val pending = MutableStateFlow<MediaItemId?>(null)

    fun decided(id: MediaItemId?) {
        streaming.value = id
        pending.value = id
    }

    fun handedOver(id: MediaItemId) {
        pending.compareAndSet(id, null)
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    val now: Flow<MediaItemId?> = flow {
        var told: MediaItemId? = null
        emitAll(
            combine(streaming, pending, wanted) { streaming, pending, wanted ->
                streaming?.takeIf { it == wanted || it == pending }
            }.transformLatest { value ->
                if (value == null && told != null) delay(graceMs)
                told = value
                emit(value)
            },
        )
    }.distinctUntilChanged()

    companion object {
        const val LIFT_GRACE_MS = 1_500L
    }
}
