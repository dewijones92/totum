package com.dewijones92.totum.diagnostics

import com.dewijones92.totum.common.Vitals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class MainThreadStallsTest {

    private val lines = mutableListOf<String>()
    private val stalls = MainThreadStalls(log = { lines += it })

    @Before
    fun clear() = Vitals.clear()

    private fun stack(vararg frames: String) = frames.map { frame ->
        StackTraceElement(frame.substringBeforeLast('.'), frame.substringAfterLast('.'), "F.kt", 7)
    }.toTypedArray()

    private val binder = stack(
        "android.os.BinderProxy.transactNative",
        "android.net.ConnectivityManager.getNetworkCapabilities",
        "com.dewijones92.totum.playback.ActiveNetworkKt.activeNetworkIsMetered",
        "com.dewijones92.totum.queue.QueueAutoDownloader.refused",
        "android.os.Looper.loop",
    )

    @Test
    fun `a long stall says where the main thread was, most often first`() {
        repeat(3) { stalls.sampled(binder) }
        stalls.sampled(stack("com.dewijones92.totum.ui.queue.QueueScreenKt.QueueRow"))
        stalls.ended(stalledMs = 2_100, nowMs = 50_000)

        assertEquals(1, lines.size)
        val line = lines.single()
        assertTrue(
            line,
            line.startsWith(
                "the main thread stalled 2100ms; in 4 sample(s) it was " +
                    "3× at android.os.BinderProxy.transactNative(F.kt:7)"
            )
        )
        assertTrue(
            line,
            "QueueAutoDownloader.refused(F.kt:7)" in line &&
                "1× at com.dewijones92.totum.ui.queue.QueueScreenKt.QueueRow(F.kt:7)" in line,
        )
        assertEquals("1", Vitals.snapshot()["main.stalls"])
        assertEquals("2100", Vitals.snapshot()["main.worstStallMs"])
    }

    @Test
    fun `a short hitch is neither logged nor counted, and its samples do not leak into the next stall`() {
        stalls.sampled(binder)
        stalls.ended(stalledMs = 150, nowMs = 1_000)
        stalls.sampled(stack("com.dewijones92.totum.ui.queue.QueueScreenKt.QueueRow"))
        stalls.ended(stalledMs = 900, nowMs = 2_000)

        assertEquals(1, lines.size)
        assertTrue(
            lines.single(),
            "in 1 sample(s) it was 1× at com.dewijones92.totum.ui.queue.QueueScreenKt.QueueRow(F.kt:7)" in
                lines.single()
        )
        assertEquals("1", Vitals.snapshot()["main.stalls"])
    }

    @Test
    fun `stalls close together are counted into the next line, not logged one by one`() {
        stalls.ended(stalledMs = 500, nowMs = 0)
        stalls.ended(stalledMs = 1_500, nowMs = 2_000)
        stalls.ended(stalledMs = 700, nowMs = 4_000)
        stalls.ended(stalledMs = 600, nowMs = 12_000)

        assertEquals(2, lines.size)
        assertTrue(lines.last(), lines.last().endsWith("plus 2 stall(s) since the last line, the worst 1500ms"))
        assertEquals("4", Vitals.snapshot()["main.stalls"])
        assertEquals("1500", Vitals.snapshot()["main.worstStallMs"])
    }

    @Test
    fun `a stall that ended before the first sample says so`() {
        stalls.ended(stalledMs = 450, nowMs = 0)
        assertTrue(lines.single(), "not caught in the act" in lines.single())
    }

    @Test
    fun `where keeps the top frame and the app's own frames below it, platform frames left out`() {
        assertEquals(
            "at android.os.BinderProxy.transactNative(F.kt:7) < " +
                "at com.dewijones92.totum.playback.ActiveNetworkKt.activeNetworkIsMetered(F.kt:7) < " +
                "at com.dewijones92.totum.queue.QueueAutoDownloader.refused(F.kt:7)",
            MainThreadStalls.where(binder),
        )
    }

    @Test
    fun `the app's own frames are kept however many library frames sit above them`() {
        val compose = (1..20).map { "androidx.compose.runtime.Composer$it.run" }.toTypedArray()
        val where = MainThreadStalls.where(stack(*compose, "com.dewijones92.totum.ui.queue.QueueScreenKt.QueueRow"))
        assertTrue(where, where.endsWith("at com.dewijones92.totum.ui.queue.QueueScreenKt.QueueRow(F.kt:7)"))
    }

    @Test
    fun `the worst stall keeps where it was, even when it was not logged`() {
        stalls.ended(stalledMs = 500, nowMs = 0)
        stalls.sampled(binder)
        stalls.ended(stalledMs = 3_000, nowMs = 1_000)

        assertEquals(1, lines.size)
        assertTrue(
            Vitals.snapshot()["main.worstStall"].orEmpty().contains(
                "stalled 3000ms; in 1 sample(s) it was 1× at android.os.BinderProxy"
            )
        )
    }
}
