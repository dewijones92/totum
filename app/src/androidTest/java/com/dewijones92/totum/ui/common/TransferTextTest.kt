package com.dewijones92.totum.ui.common

import androidx.test.platform.app.InstrumentationRegistry
import com.dewijones92.totum.domain.Transfer
import com.dewijones92.totum.domain.TransferPhase
import com.dewijones92.totum.domain.TransferPlace
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TransferTextTest {

    private val resources = InstrumentationRegistry.getInstrumentation().targetContext.resources

    private fun route(from: TransferPlace, to: TransferPlace) =
        transferRoute(resources, Transfer.running(from, to, null))

    @Test
    fun everyRouteSaysWhatIsHappeningFromWhereToWhere() {
        assertEquals("⬇️ Web → 📱 Phone", route(TransferPlace.WEB, TransferPlace.PHONE))
        assertEquals("⬇️ Web → 🏠 Pi", route(TransferPlace.WEB, TransferPlace.PI))
        assertEquals("📥 🏠 Pi → 📱 Phone", route(TransferPlace.PI, TransferPlace.PHONE))
        assertEquals("📤 📱 Phone → 🏠 Pi", route(TransferPlace.PHONE, TransferPlace.PI))
    }

    @Test
    fun everyPairOfPlacesIsWordedAndNoTwoPairsReadAlike() {
        val places = TransferPlace.entries
        val pairs = places.flatMap { from -> places.filter { it != from }.map { from to it } }

        val words = pairs.map { (from, to) -> route(from, to) }

        assertEquals(words.size, words.toSet().size)
        assertTrue(words.none { it.isBlank() })
    }

    @Test
    fun aRunningTransferSaysItsPercentOrThatItIsStarting() {
        assertEquals(
            "⬇️ Web → 📱 Phone · 42%",
            transferText(resources, Transfer.running(TransferPlace.WEB, TransferPlace.PHONE, 42)),
        )
        assertEquals(
            "📤 📱 Phone → 🏠 Pi · starting…",
            transferText(resources, Transfer.running(TransferPlace.PHONE, TransferPlace.PI, null)),
        )
    }

    @Test
    fun aWaitingTransferSaysItIsWaitingAndAFailedOneSaysWhy() {
        assertEquals(
            "⏳ ⬇️ Web → 🏠 Pi · waiting",
            transferText(resources, Transfer.waiting(TransferPlace.WEB, TransferPlace.PI)),
        )
        assertEquals(
            "❌ 📥 🏠 Pi → 📱 Phone · failed: the Pi is full",
            transferText(resources, Transfer.failed(TransferPlace.PI, TransferPlace.PHONE, "the Pi is full")),
        )
    }

    @Test
    fun aFailureWithNoReasonStillReadsAsAFailure() {
        val text = transferText(resources, Transfer.failed(TransferPlace.WEB, TransferPlace.PHONE, null))

        assertTrue(text, text.startsWith("❌ ") && "failed" in text)
    }

    @Test
    fun aTitleFollowsTheRouteAfterAMiddleDotNeverAColon() {
        val text = transferTitled(resources, Transfer.running(TransferPlace.WEB, TransferPlace.PI, null), "Peep Show")

        assertEquals("⬇️ Web → 🏠 Pi · Peep Show", text)
        assertFalse(text.contains(":"))
    }

    @Test
    fun sendingToThePiStartsAsATransferOfItsOwnWithNoPercent() {
        val sending = sendingToPi(TransferPlace.PHONE)

        assertEquals(TransferPhase.RUNNING, sending.phase)
        assertEquals(TransferPlace.PI, sending.to)
        assertEquals(null, sending.percent)
    }
}
