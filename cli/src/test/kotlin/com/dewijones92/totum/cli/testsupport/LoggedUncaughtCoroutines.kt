package com.dewijones92.totum.cli.testsupport

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

class LoggedUncaughtCoroutines :
    AbstractCoroutineContextElement(CoroutineExceptionHandler),
    CoroutineExceptionHandler {
    override fun handleException(context: CoroutineContext, exception: Throwable) {
        if (exception is CancellationException) return
        System.err.println(
            "dewidebug uncaught coroutine exception on ${Thread.currentThread().name} in $context: " +
                exception.stackTraceToString(),
        )
    }
}
