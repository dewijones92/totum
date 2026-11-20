package com.dewijones92.totum.data.phone

import com.dewijones92.totum.domain.PhoneMediaEntry
import com.dewijones92.totum.domain.PhoneMediaIds
import com.dewijones92.totum.domain.PhoneMediaKind
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

private fun entry(n: Int, kind: PhoneMediaKind = PhoneMediaKind.VIDEO) = PhoneMediaEntry(
    kind = kind,
    key = PhoneMediaIds.keyOf(n.toString(), "/storage/emulated/0/Movies/$n.mp4"),
    title = "File $n",
    path = "/storage/emulated/0/Movies/$n.mp4",
    uri = "content://media/external/video/media/$n",
)

private class PagedFake(private val total: Int) : PhoneMediaSource {
    val asked = mutableListOf<PhoneMediaQuery>()
    var failNext: Boolean = false

    override suspend fun page(query: PhoneMediaQuery): PhoneMediaPage {
        asked += query
        if (failNext) {
            failNext = false
            error("the media store said no")
        }
        val rows = (query.offset until minOf(total, query.offset + query.limit)).map { entry(it) }
        return PhoneMediaPage(rows, query.nextOffset(rows.size))
    }

    override suspend fun folders(kind: PhoneMediaKind): List<PhoneFolder> = emptyList()
}

@OptIn(ExperimentalCoroutinesApi::class)
class PhoneMediaBrowserTest {

    @Test
    fun `thousands of files are read a page at a time, never all at once`() = runTest {
        val source = PagedFake(total = 2_345)
        val browser = PhoneMediaBrowser(source)

        browser.open(PhoneMediaQuery(PhoneMediaKind.VIDEO))

        assertEquals(100, browser.state.value.entries.size)
        assertEquals(100, browser.state.value.next)
        assertEquals(1, source.asked.size)
        assertEquals(100, source.asked.single().limit)
    }

    @Test
    fun `asking for more appends the next page and ends on the last one`() = runTest {
        val source = PagedFake(total = 230)
        val browser = PhoneMediaBrowser(source)
        browser.open(PhoneMediaQuery(PhoneMediaKind.VIDEO))

        browser.more()
        browser.more()

        assertEquals(230, browser.state.value.entries.size)
        assertNull(browser.state.value.next)
        assertEquals(listOf(0, 100, 200), source.asked.map { it.offset })
        assertEquals(0, browser.more())
        assertEquals(3, source.asked.size)
    }

    @Test
    fun `a row that arrives twice is shown once`() = runTest {
        val source = object : PhoneMediaSource {
            override suspend fun page(query: PhoneMediaQuery): PhoneMediaPage = PhoneMediaPage(
                listOf(entry(1), entry(if (query.offset == 0) 2 else 1)),
                if (query.offset == 0) 2 else null,
            )

            override suspend fun folders(kind: PhoneMediaKind): List<PhoneFolder> = emptyList()
        }
        val browser = PhoneMediaBrowser(source)
        browser.open(PhoneMediaQuery(PhoneMediaKind.VIDEO, limit = 2))

        browser.more()

        assertEquals(listOf("1", "2"), browser.state.value.entries.map { it.ref })
    }

    @Test
    fun `a failed read says why and leaves what was already shown`() = runTest {
        val source = PagedFake(total = 500)
        val browser = PhoneMediaBrowser(source)
        browser.open(PhoneMediaQuery(PhoneMediaKind.VIDEO))

        source.failNext = true
        browser.more()

        assertEquals("the media store said no", browser.state.value.failure)
        assertEquals(100, browser.state.value.entries.size)
        assertFalse(browser.state.value.loading)
        assertEquals(100, browser.state.value.next)
    }

    @Test
    fun `an empty library is empty, not failed or loading`() = runTest {
        val browser = PhoneMediaBrowser(PagedFake(total = 0))

        browser.open(PhoneMediaQuery(PhoneMediaKind.AUDIO))

        assertTrue(browser.state.value.empty)
    }

    @Test
    fun `a slow answer to an old query never overwrites a newer one`() = runTest {
        val slow = CompletableDeferred<PhoneMediaPage>()
        val source = object : PhoneMediaSource {
            override suspend fun page(query: PhoneMediaQuery): PhoneMediaPage =
                if (query.search == "old") slow.await() else PhoneMediaPage(listOf(entry(9)), null)

            override suspend fun folders(kind: PhoneMediaKind): List<PhoneFolder> = emptyList()
        }
        val browser = PhoneMediaBrowser(source)

        val old = async { browser.open(PhoneMediaQuery(PhoneMediaKind.VIDEO, search = "old")) }
        advanceUntilIdle()
        browser.open(PhoneMediaQuery(PhoneMediaKind.VIDEO, search = "new"))
        slow.complete(PhoneMediaPage(listOf(entry(1), entry(2)), null))
        old.await()

        assertEquals(listOf("9"), browser.state.value.entries.map { it.ref })
        assertEquals("new", browser.state.value.query?.search)
    }

    @Test
    fun `opening a new query starts again from the top`() = runTest {
        val source = PagedFake(total = 500)
        val browser = PhoneMediaBrowser(source)
        browser.open(PhoneMediaQuery(PhoneMediaKind.VIDEO))
        browser.more()

        browser.open(PhoneMediaQuery(PhoneMediaKind.VIDEO, folder = "Camera", offset = 300))

        assertEquals(100, browser.state.value.entries.size)
        assertEquals(0, source.asked.last().offset)
    }
}
