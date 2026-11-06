package com.dewijones92.totum.ui.common

import com.dewijones92.totum.common.Diag
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async

class HeldRemoval<out S, out R>(val summary: S, private val taken: Deferred<R>) {
    suspend fun taken(): R = taken.await()
}

fun <S, R> CoroutineScope.removeAndHold(tag: String, summary: S, take: suspend () -> R): HeldRemoval<S, R> {
    val taken = async { take() }
    taken.invokeOnCompletion { cause ->
        if (cause != null) Diag.log(tag, "dewidebug removal failed, so nothing can be put back: $cause")
    }
    return HeldRemoval(summary, taken)
}
