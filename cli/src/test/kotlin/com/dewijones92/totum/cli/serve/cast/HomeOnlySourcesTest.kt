package com.dewijones92.totum.cli.serve.cast

import com.dewijones92.totum.cli.serve.Rewrites
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class HomeOnlySourcesTest {

    private val rewrites = Rewrites(listOf("https://totum.home.test/ts/" to "http://gluetun:8090/"))
    private val id = "0123456789abcdef0123456789abcdef"

    private fun sources(
        jellyfin: ((String) -> String)? = { "http://jellyfin:8096/Videos/$it/stream?static=true&api_key=K" },
    ) = HomeOnlySources("totum.home.test", jellyfin, rewrites) { wanted ->
        File("/media/totum/$wanted.mkv").takeIf { wanted == "item1" }
    }

    private fun map(url: String, from: HomeOnlySources = sources()): HomeOnlyMapped = runBlocking { from.map(url) }

    private fun address(mapped: HomeOnlyMapped): String = (mapped as HomeOnlyMapped.To).address

    private fun why(mapped: HomeOnlyMapped): String = (mapped as HomeOnlyMapped.Refused).why

    @Test
    fun `a Jellyfin stream on the home server becomes Jellyfin's own address, kept on the Pi`() {
        assertEquals(
            "http://jellyfin:8096/Videos/$id/stream?static=true&api_key=K",
            address(map("https://totum.home.test/jellyfin/stream/$id.mkv")),
        )
        assertEquals(
            "http://jellyfin:8096/Videos/$id/stream?static=true&api_key=K",
            address(map("https://TOTUM.home.test/jellyfin/stream/$id")),
        )
    }

    @Test
    fun `a torrent stream becomes TorrServer's own address through the rewrite`() {
        assertEquals(
            "http://gluetun:8090/stream/film.mkv?link=abc&index=1&play",
            address(map("https://totum.home.test/ts/stream/film.mkv?link=abc&index=1&play")),
        )
    }

    @Test
    fun `without a rewrite for it, a torrent is refused rather than fetched through the public name`() {
        val bare = HomeOnlySources("totum.home.test", null, Rewrites(emptyList())) { null }

        assertTrue(why(map("https://totum.home.test/ts/stream/film.mkv", bare)).contains("TOTUM_REWRITE"))
    }

    @Test
    fun `a home copy is the file on the Pi`() {
        assertEquals("/media/totum/item1.mkv", address(map("https://totum.home.test/fetch/files?id=item1")))
        assertEquals(
            "/media/totum/item1.mkv",
            address(map("https://totum.home.test/fetch/files?audioOnly=true&id=item1"))
        )
        assertTrue(why(map("https://totum.home.test/fetch/files?id=other")).contains("no copy"))
        assertTrue(why(map("https://totum.home.test/fetch/files")).contains("no item"))
    }

    @Test
    fun `Jellyfin that is not set up is said so`() {
        assertTrue(
            why(map("https://totum.home.test/jellyfin/stream/$id.mkv", sources(jellyfin = null))).contains("not set up")
        )
    }

    @Test
    fun `an address that is not on the home server, or not one of its media routes, is refused`() {
        listOf(
            "https://elsewhere.test/jellyfin/stream/$id.mkv",
            "https://totum.home.test.evil.test/jellyfin/stream/$id.mkv",
            "https://totum.home.test/jobs",
            "https://totum.home.test/targets",
            "https://totum.home.test/fetch/account",
            "https://totum.home.test/jellyfin/stream/../../fetch/jobs",
            "https://totum.home.test/jellyfin/stream/not-an-id!.mkv",
            "https://totum.home.test/jellyfin/browse",
            "file:///etc/passwd",
            "not a url at all",
            "",
        ).forEach { assertTrue(it, map(it) is HomeOnlyMapped.Refused) }
    }
}
