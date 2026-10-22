package com.dewijones92.totum.kidlock

import com.dewijones92.totum.common.Diag
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class KidLock {
    private val lockedState = MutableStateFlow(false)
    val locked: StateFlow<Boolean> = lockedState.asStateFlow()

    private val pinnedState = MutableStateFlow(false)
    val pinnedBySystem: StateFlow<Boolean> = pinnedState.asStateFlow()

    fun lock(why: String) {
        if (lockedState.value) return Diag.log(TAG, "already locked; asked again from $why")
        Diag.log(TAG, "locked from $why: controls hidden, back ignored, asking Android to pin the app")
        lockedState.value = true
    }

    fun unlock(why: String) {
        if (!lockedState.value) return
        Diag.log(TAG, "unlocked: $why")
        lockedState.value = false
        pinnedState.value = false
    }

    fun systemPinned(pinned: Boolean, why: String) {
        if (pinnedState.value == pinned) return
        Diag.log(TAG, "Android's app pinning is ${if (pinned) "on" else "off"} ($why)")
        pinnedState.value = pinned
    }

    private companion object {
        const val TAG = "kidlock"
    }
}
