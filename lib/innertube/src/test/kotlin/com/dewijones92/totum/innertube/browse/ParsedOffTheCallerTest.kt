package com.dewijones92.totum.innertube.browse

import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.Executors

class ParsedOffTheCallerTest {

    @Test
    fun `a response is parsed off the thread that asked for it`() {
        val main = Executors.newSingleThreadExecutor { Thread(it, "pretend-main") }
        try {
            val (asker, parser, answer) = runBlocking {
                withContext(main.asCoroutineDispatcher()) {
                    val parsedOn = InnerTubeResponse.Success(
                        "{\"a\":1}"
                    ).parsed { Thread.currentThread().name to it.length }
                    Triple(Thread.currentThread().name, parsedOn.first, parsedOn.second)
                }
            }
            assertTrue(asker, asker.startsWith("pretend-main"))
            assertFalse(parser, parser.startsWith("pretend-main"))
            assertEquals(7, answer)
        } finally {
            main.shutdown()
        }
    }
}
