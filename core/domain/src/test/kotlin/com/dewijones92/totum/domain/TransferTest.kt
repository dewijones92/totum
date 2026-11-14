package com.dewijones92.totum.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

class TransferTest {

    @Test
    fun `a transfer has to go somewhere`() {
        TransferPlace.entries.forEach { place ->
            assertThrows(IllegalArgumentException::class.java) { Transfer.running(place, place, 10) }
        }
    }

    @Test
    fun `a percent is within zero to a hundred or not known`() {
        fun running(percent: Int?) = Transfer.running(TransferPlace.WEB, TransferPlace.PHONE, percent)

        assertThrows(IllegalArgumentException::class.java) { running(-1) }
        assertThrows(IllegalArgumentException::class.java) { running(101) }
        assertEquals(0, running(0).percent)
        assertEquals(100, running(100).percent)
        assertNull(running(null).percent)
    }

    @Test
    fun `the three states are built by name, each carrying only what it has`() {
        val waiting = Transfer.waiting(TransferPlace.PHONE, TransferPlace.PI)
        val failed = Transfer.failed(TransferPlace.PI, TransferPlace.PHONE, "the Pi is full")

        assertEquals(TransferPhase.WAITING, waiting.phase)
        assertNull(waiting.percent)
        assertNull(waiting.reason)
        assertEquals(TransferPhase.FAILED, failed.phase)
        assertEquals("the Pi is full", failed.reason)
        assertNull(failed.percent)
        assertEquals(TransferPhase.RUNNING, Transfer.running(TransferPlace.WEB, TransferPlace.PI, 5).phase)
    }

    @Test
    fun `a percent is whole and rounds down`() {
        assertEquals(33, percentOf(1, 3))
        assertEquals(66, percentOf(2, 3))
        assertEquals(0, percentOf(0, 10))
        assertEquals(50, percentOf(50, 100))
        assertEquals(99, percentOf(999, 1_000))
        assertEquals(100, percentOf(1_000, 1_000))
    }

    @Test
    fun `no size known, or an empty one, is no percent`() {
        assertNull(percentOf(500, null))
        assertNull(percentOf(500, 0))
        assertNull(percentOf(500, -10))
    }

    @Test
    fun `a percent never leaves its range, whatever the byte counts do`() {
        assertEquals(100, percentOf(2_000, 1_000))
        assertEquals(0, percentOf(-50, 1_000))
    }

    @Test
    fun `a film's worth of bytes does not overflow`() {
        assertEquals(50, percentOf(5_000_000_000L, 10_000_000_000L))
        assertEquals(100, percentOf(Long.MAX_VALUE / 200, Long.MAX_VALUE / 200))
    }

    @Test
    fun `a download from the web is web to phone, and one from the Pi is Pi to phone`() {
        val state = DownloadState.Downloading(downloadedBytes = 250, totalBytes = 1_000)

        assertEquals(Transfer.running(TransferPlace.WEB, TransferPlace.PHONE, 25), state.transfer(fromPi = false))
        assertEquals(Transfer.running(TransferPlace.PI, TransferPlace.PHONE, 25), state.transfer(fromPi = true))
    }

    @Test
    fun `a download of unknown size has no percent yet`() {
        val state = DownloadState.Downloading(downloadedBytes = 250, totalBytes = null)

        assertNull(state.transfer(fromPi = false).percent)
    }
}
