package com.dewijones92.totum.ui.channel

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ChannelSearchKeepsTest {

    @Test
    fun `results still showing from the previous query do not count while the new one loads`() {
        val old = listOf("peppa 1", "peppa 2")

        assertFalse(ChannelViewModel.TabState(loading = true, loaded = true, items = old).foundSomething)
        assertTrue(ChannelViewModel.TabState(loading = false, loaded = true, items = old).foundSomething)
        assertFalse(ChannelViewModel.TabState<String>(loaded = true).foundSomething)
    }
}
