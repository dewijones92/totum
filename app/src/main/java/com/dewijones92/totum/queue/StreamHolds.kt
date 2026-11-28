package com.dewijones92.totum.queue

import com.dewijones92.totum.domain.MediaItemId
import com.dewijones92.totum.playback.StreamHold
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged

internal fun streamHolds(
    streaming: Flow<MediaItemId?>,
    teeable: Flow<Set<String>>,
    routesRecorded: Flow<Int>,
    routeOf: (String) -> String?,
): Flow<StreamHold?> = combine(streaming, teeable, routesRecorded) { id, teeableNow, _ ->
    StreamHold.of(id?.value, teeableNow, id?.let { routeOf(it.value) })
}.distinctUntilChanged()
