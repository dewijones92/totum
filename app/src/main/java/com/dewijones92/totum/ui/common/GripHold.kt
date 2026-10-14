package com.dewijones92.totum.ui.common

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import com.dewijones92.totum.common.Diag

class GripHold {
    private var holds by mutableIntStateOf(0)

    val held: Boolean get() = holds > 0

    fun Modifier.holdsGrip(): Modifier = pointerInput(Unit) {
        awaitEachGesture {
            awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
            holds++
            Diag.log("reorder", "dewidebug grip down, holds=$holds")
            try {
                do {
                    val event = awaitPointerEvent(PointerEventPass.Initial)
                } while (event.changes.any { it.pressed })
            } finally {
                holds--
                Diag.log("reorder", "dewidebug grip up, holds=$holds")
            }
        }
    }
}
