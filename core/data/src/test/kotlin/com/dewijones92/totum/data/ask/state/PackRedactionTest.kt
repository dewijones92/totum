package com.dewijones92.totum.data.ask.state

import com.dewijones92.totum.common.Breadcrumbs
import com.dewijones92.totum.common.HomeServerHosts
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PackRedactionTest {

    private fun event(tag: String, message: String) = Breadcrumbs.Entry(StateTestData.NOW - 1_000, tag, message, 1)

    private val realistic = listOf(
        event("podcasts", "subscribe https://feeds.example.org/private/feedpathkey-77/show.rss?auth=querykey99"),
        event("settings", "change(homeServer, myhome.example.net)"),
        event("home", "POST https://totum.myhome.example.net/fetch/downloads -> 200"),
        event("home", "signed in as dewi.person@mail.example.com from 203.0.113.9"),
        event("home", "device token HOMETOKEN-abcdef-0123456789 accepted"),
        event("play", "route v2 -> https://www.youtube.com/watch?v=abcDEF12345&list=PL1"),
    )

    private val pack: String = AppStateBuilder.build(
        StateTestData.inputs(
            events = realistic,
            secrets = listOf("HOMETOKEN-abcdef-0123456789"),
            hosts = HomeServerHosts.allFor(listOf("myhome.example.net")),
        ),
    ).json.toString()

    @Test
    fun `no feed address path or key, email or network address survives in an event message`() {
        listOf("feedpathkey", "querykey99", "dewi.person@", "203.0.113.9", "mail.example.com/", "myhome.example.net", "HOMETOKEN").forEach {
            assertFalse("\"$it\" is in the pack", pack.contains(it))
        }
    }

    @Test
    fun `an ordinary video link keeps what names the video`() {
        assertTrue(pack, pack.contains("https://www.youtube.com/watch?v=abcDEF12345&list=PL1"))
    }
}
