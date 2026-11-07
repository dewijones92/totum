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

    private val tvState = MutableStateFlow<String?>(null)
    val tv: StateFlow<String?> = tvState.asStateFlow()

    private val modeState = MutableStateFlow(false)
    val modeOn: StateFlow<Boolean> = modeState.asStateFlow()

    fun lock(why: String) {
        if (lockedState.value) return Diag.log(TAG, "already locked; asked again from $why")
        Diag.log(TAG, "locked from $why: controls hidden, back ignored, asking Android to pin the app")
        lockedState.value = true
        refresh("locked")
    }

    fun unlock(why: String) {
        if (!lockedState.value) return
        Diag.log(TAG, "unlocked: $why")
        lockedState.value = false
        pinnedState.value = false
        refresh("unlocked")
    }

    fun tvOn(tv: String, why: String) {
        if (tvState.value == tv) return Diag.log(TAG, "kid TV mode already on for $tv; asked again from $why")
        Diag.log(TAG, "kid TV mode on for $tv from $why: 1x, no silence skipping, the group repeats, no surprises")
        tvState.value = tv
        refresh("kid TV mode on")
    }

    fun tvOff(why: String) {
        if (tvState.value == null) return
        Diag.log(TAG, "kid TV mode off for ${tvState.value}: $why")
        tvState.value = null
        refresh("kid TV mode off")
    }

    fun systemPinned(pinned: Boolean, why: String) {
        if (pinnedState.value == pinned) return
        Diag.log(TAG, "Android's app pinning is ${if (pinned) "on" else "off"} ($why)")
        pinnedState.value = pinned
    }

    private fun refresh(because: String) {
        val on = lockedState.value || tvState.value != null
        if (modeState.value == on) return
        Diag.log(
            TAG,
            "kid mode is now ${if (on) "on" else "off"} ($because; locked=${lockedState.value} tv=${tvState.value})",
        )
        modeState.value = on
    }

    private companion object {
        const val TAG = "kidlock"
    }
}
