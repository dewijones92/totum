package com.dewijones92.totum.downloads.home

import com.dewijones92.totum.data.targets.Requester
import com.dewijones92.totum.innertube.auth.AccessToken
import com.dewijones92.totum.innertube.auth.OAuthTokens
import com.dewijones92.totum.innertube.auth.RefreshToken
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SignInHandoverTest {

    private val home = FakeHomeApi()
    private val told = mutableListOf<String>()
    private val me = Requester("me-id", "Pixel 7 (a3f9)")
    private val handover = SignInHandover(
        tokens = { OAuthTokens(AccessToken("at"), RefreshToken("rt"), 3_600) },
        identity = { me },
        tell = { told += it },
    )

    @Test
    fun `the hand-over says which phone it is from`() = runTest {
        handover.ensure(home)

        assertEquals(me, home.handedOver.single().from)
        assertTrue(told.isEmpty())
    }

    @Test
    fun `a hand-over the Pi refuses is not repeated, and the phone says so once`() = runTest {
        home.refuseHandover =
            "🔒 The home server is signed in to YouTube from another phone (Pixel 7 (77c1)); sign that one out first"

        handover.ensure(home)
        handover.ensure(home)
        handover.ensure(home)

        assertEquals(1, home.handedOver.size)
        assertEquals(1, told.size)
        assertTrue(told.single().startsWith("🔒"))
    }

    @Test
    fun `a network failure is tried again, and forgetting lets a refused phone try again too`() = runTest {
        home.refuseHandover = "❌ The home server didn't answer"
        handover.ensure(home)
        handover.ensure(home)
        assertEquals(2, home.handedOver.size)
        assertTrue(told.isEmpty())

        home.refuseHandover = "🔒 another phone"
        handover.ensure(home)
        handover.ensure(home)
        assertEquals(3, home.handedOver.size)

        handover.forget()
        handover.ensure(home)
        assertEquals(4, home.handedOver.size)
    }
}
