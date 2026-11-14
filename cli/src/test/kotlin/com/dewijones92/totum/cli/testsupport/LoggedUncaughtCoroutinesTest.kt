package com.dewijones92.totum.cli.testsupport

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineExceptionHandler
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.util.ServiceLoader
import kotlin.coroutines.EmptyCoroutineContext

class LoggedUncaughtCoroutinesTest {

    private fun said(block: () -> Unit): String {
        val before = System.err
        val captured = ByteArrayOutputStream()
        System.setErr(PrintStream(captured, true))
        try {
            block()
        } finally {
            System.setErr(before)
        }
        return captured.toString()
    }

    @Test
    fun `the handler is registered, so an uncaught coroutine exception in any test leaves its stack in the log`() {
        val registered = ServiceLoader.load(CoroutineExceptionHandler::class.java)
            .any { it is LoggedUncaughtCoroutines }

        assertTrue(registered)
    }

    @Test
    fun `an uncaught exception is written with where it was thrown, and a cancellation is not`() {
        val handler = LoggedUncaughtCoroutines()

        val thrown = said { handler.handleException(EmptyCoroutineContext, IllegalStateException("boom")) }
        val cancelled = said { handler.handleException(EmptyCoroutineContext, CancellationException("done")) }

        assertTrue(thrown, "dewidebug uncaught coroutine exception" in thrown && "boom" in thrown)
        assertTrue(thrown, "LoggedUncaughtCoroutinesTest" in thrown)
        assertFalse(cancelled, cancelled.isNotEmpty())
    }
}
