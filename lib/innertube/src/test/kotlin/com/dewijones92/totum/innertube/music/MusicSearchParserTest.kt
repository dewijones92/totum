package com.dewijones92.totum.innertube.music

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Parsing YouTube Music's search rows.
 *
 * `songs-search.json` is a **real** response, captured from the live API on 2026-08-11 and
 * trimmed to four rows with every field the parser reads left exactly as YouTube sent it. A
 * hand-written fixture would only ever prove the parser agrees with my idea of the shape, which
 * is precisely the thing that is wrong when this breaks.
 *
 * The synthetic cases below are for shapes the captured response does not contain — an
 * unfiltered search, an album row, a missing duration — and are written to match the live shapes
 * observed while probing the API.
 */
class MusicSearchParserTest {

    private fun fixture(): String = requireNotNull(
        javaClass.classLoader?.getResourceAsStream("music/songs-search.json"),
    ) { "music/songs-search.json is missing" }.bufferedReader().readText()

    @Test
    fun `it reads the real response`() {
        val songs = MusicSearchParser.songs(fixture())

        assertEquals(4, songs.size)
    }

    @Test
    fun `a song carries its title, artist, album and duration`() {
        val song = MusicSearchParser.songs(fixture()).first()

        assertEquals("Feeling Good", song.title)
        assertEquals("Nina Simone", song.artist)
        assertEquals("I Put A Spell On You", song.album)
        assertEquals(174L, song.durationSeconds)
        assertEquals("276M plays", song.playsText)
    }

    @Test
    fun `and a watch url built from its video id`() {
        val song = MusicSearchParser.songs(fixture()).first()

        assertEquals("BNMKGYiJpvg", song.videoId)
        assertTrue(
            "a song must be playable by the ordinary video path: ${song.watchUrl.value}",
            song.watchUrl.value.endsWith("watch?v=BNMKGYiJpvg"),
        )
    }

    @Test
    fun `and the largest artwork on offer`() {
        val song = MusicSearchParser.songs(fixture()).first()

        assertTrue("no artwork", song.thumbnailUrl != null)
    }

    @Test
    fun `an apostrophe in a title survives`() {
        val titles = MusicSearchParser.songs(fixture()).map { it.title }

        assertTrue(titles.toString(), titles.contains("Don't Let Me Be Misunderstood"))
    }

    // ---- shapes the captured fixture does not contain -------------------------------------

    @Test
    fun `an unfiltered row does not credit the song to its TYPE`() {
        // The live unfiltered response leads the second column with "Video" or "Song". Reading
        // segment zero as the artist credited a quarter of the results to "Video".
        val song = MusicSearchParser.songs(
            row("Nina Simone - Feeling Good", listOf("Video", " • ", "M M P F", " • ", "2.6M views", " • ", "2:58")),
        ).single()

        assertEquals("M M P F", song.artist)
        assertEquals(178L, song.durationSeconds)
    }

    @Test
    fun `a view or play count is never mistaken for an album`() {
        val song = MusicSearchParser.songs(
            row("A Song", listOf("An Artist", " • ", "1.2M views", " • ", "3:00")),
        ).single()

        assertNull(song.album)
        assertEquals("An Artist", song.artist)
    }

    @Test
    fun `an album row is skipped entirely, because it cannot be played`() {
        // Albums, artists and playlists carry no watchEndpoint. Returning them as songs would put
        // rows in the list that do nothing when tapped.
        val songs = MusicSearchParser.songs(
            rowWithoutVideo("Feeling Good: The Very Best Of", listOf("Album", " • ", "Nina Simone", " • ", "1994")),
        )

        assertTrue(songs.isEmpty())
    }

    @Test
    fun `a year is not read as an album`() {
        val song = MusicSearchParser.songs(
            row("A Song", listOf("An Artist", " • ", "1994", " • ", "3:00")),
        ).single()

        assertNull(song.album)
    }

    @Test
    fun `a song with no stated duration is still offered`() {
        val song = MusicSearchParser.songs(row("A Song", listOf("An Artist"))).single()

        assertNull(song.durationSeconds)
        assertEquals("An Artist", song.artist)
    }

    @Test
    fun `an hour-long mix parses its duration`() {
        val song = MusicSearchParser.songs(row("A Mix", listOf("An Artist", " • ", "1:02:03"))).single()

        assertEquals(3_723L, song.durationSeconds)
    }

    @Test
    fun `the same song twice comes back once, in the order first seen`() {
        // Same video id twice — which a mixed response really does contain, because the top
        // "card shelf" result is repeated in the list below it.
        val songs = MusicSearchParser.songs(
            """{"a":${row("First", listOf("X"))},"b":${row("Second", listOf("Y"))}}""",
        )

        assertEquals(listOf("First"), songs.map { it.title })
    }

    @Test
    fun `a row with no title is dropped rather than shown blank`() {
        assertTrue(MusicSearchParser.songs(row("", listOf("An Artist"))).isEmpty())
    }

    @Test
    fun `nonsense in gives nothing out`() {
        assertTrue(MusicSearchParser.songs("not json").isEmpty())
        assertTrue(MusicSearchParser.songs("{}").isEmpty())
        assertTrue(MusicSearchParser.songs("").isEmpty())
    }

    /** One row in the live shape: a title column, a details column, and a watchEndpoint. */
    private fun row(title: String, details: List<String>, videoId: String = VIDEO_ID): String = """
        {"musicResponsiveListItemRenderer":{
          "overlay":{"musicItemThumbnailOverlayRenderer":{"content":{"musicPlayButtonRenderer":{
            "playNavigationEndpoint":{"watchEndpoint":{"videoId":"$videoId"}}}}}},
          "flexColumns":[
            ${column(listOf(title))},
            ${column(details)}
          ]}}
    """.trimIndent()

    private fun rowWithoutVideo(title: String, details: List<String>): String = """
        {"musicResponsiveListItemRenderer":{"flexColumns":[
          ${column(listOf(title))},
          ${column(details)}
        ]}}
    """.trimIndent()

    private fun column(runs: List<String>): String =
        """{"musicResponsiveListItemFlexColumnRenderer":{"text":{"runs":[""" +
            runs.joinToString(",") { """{"text":${quote(it)}}""" } +
            """]}}}"""

    private fun quote(text: String) = "\"" + text.replace("\\", "\\\\").replace("\"", "\\\"") + "\""

    private companion object {
        const val VIDEO_ID = "BNMKGYiJpvg"
    }
}
