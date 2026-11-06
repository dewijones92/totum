package com.dewijones92.totum.recorder

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class TapsShown { OFF, BY_SYSTEM, IN_TOTUM }

sealed interface RecorderStatus {
    data object Idle : RecorderStatus

    data class Recording(
        val sinceMs: Long,
        val mic: Boolean,
        val face: Boolean,
        val taps: TapsShown,
        val faceLost: String? = null,
    ) : RecorderStatus
}

object RecorderState {
    private val state = MutableStateFlow<RecorderStatus>(RecorderStatus.Idle)
    val status: StateFlow<RecorderStatus> = state.asStateFlow()

    val recording: Boolean get() = state.value is RecorderStatus.Recording

    internal fun set(status: RecorderStatus) {
        state.value = status
    }
}
