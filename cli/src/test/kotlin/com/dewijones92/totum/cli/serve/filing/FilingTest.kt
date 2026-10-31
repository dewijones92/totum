package com.dewijones92.totum.cli.serve.filing

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FilingTest {

    private val library = ListedLibrary(
        listOf(
            "series/",
            "series/Slow Horses/",
            "series/Slow Horses/Series 1/",
            "series/Slow Horses/Series 1/Slow.Horses.S01E01.mkv",
            "series/Slow Horses/Series 2/Slow.Horses.S02E01.mkv",
            "series/Agatha Christies Poirot S01-S13/s10/Agatha_Christies_Poirot_S10E01_x265.mkv",
            "series/Peep Show/Peep Show S03/Peep.Show.S03E01.avi",
            "movies/",
            "movies/Luca.2021.1080p.WEBRip.x265-RARBG/Luca.2021.1080p.WEBRip.x265-RARBG.mp4",
            "movies/Elemental.2023.1080p.mkv",
            "movies/Empty Folder/",
        ),
    )

    private fun plan(action: FilePlan.Action, shelf: String, folder: String, file: String) =
        FilePlan(action, shelf, folder, file, "test")

    @Test
    fun `an episode name is recognised however it is written`() {
        listOf(
            "Slow.Horses.S02E03.mkv",
            "Agatha_Christies_Poirot_S10E04_x265.mkv",
            "DaAliGShowS02E03.avi",
            "show s1 e2.mkv"
        )
            .forEach { assertTrue(it, Filing.isEpisode(it)) }
        listOf("Luca.2021.1080p.mkv", "Oppenheimer.2023.x265.mkv").forEach { assertFalse(it, Filing.isEpisode(it)) }
    }

    @Test
    fun `the listed library knows folders, files and empty folders`() {
        assertEquals(listOf("Series 1", "Series 2"), library.dirs(Shelf.SERIES, "Slow Horses"))
        assertEquals(listOf("Elemental.2023.1080p.mkv"), library.files(Shelf.MOVIES, ""))
        assertTrue(library.exists(Shelf.MOVIES, "Empty Folder"))
        assertFalse(library.exists(Shelf.MOVIES, "Nothing Here"))
    }

    @Test
    fun `hiding a folder hides everything under it`() {
        val hidden = library.hiding(setOf("movies/Luca.2021.1080p.WEBRip.x265-RARBG"))
        assertFalse(hidden.exists(Shelf.MOVIES, "Luca.2021.1080p.WEBRip.x265-RARBG"))
        assertTrue(hidden.exists(Shelf.MOVIES, "Empty Folder"))
    }

    @Test
    fun `rules put a new episode in the season folder the show already has`() {
        val p = Filing.byRules("Slow.Horses.S02E03.1080p.mkv", library)
        assertEquals(FilePlan.Action.EPISODE, p.action)
        assertEquals("Slow Horses/Series 2", p.folder)
        assertEquals("Slow.Horses.S02E03.1080p.mkv", p.fileName)
    }

    @Test
    fun `rules find a season folder named s10 and make one when the season is new`() {
        assertEquals(
            "Agatha Christies Poirot S01-S13/s10",
            Filing.byRules("Agatha_Christies_Poirot_S10E04_x265.mkv", library).folder,
        )
        assertEquals("Slow Horses/Season 05", Filing.byRules("Slow.Horses.S05E01.mkv", library).folder)
    }

    @Test
    fun `rules give a new film its own Title (Year) folder`() {
        val p = Filing.byRules("Dune.Part.Two.2024.1080p.WEBRip.x265.mkv", library)
        assertEquals(FilePlan.Action.MOVIE, p.action)
        assertEquals("Dune Part Two (2024)", p.folder)
        assertEquals("Dune Part Two (2024).mkv", p.fileName)
    }

    @Test
    fun `rules use the torrent's name when the file's says nothing`() {
        val p = Filing.byRules("[Prof] S01E01 - To You.mkv", library, from = "Peep Show S03")
        assertEquals("Peep Show/Season 01", p.folder)
    }

    @Test
    fun `the context shows the matching show's folders and files`() {
        val context = Filing.context("Slow.Horses.S02E03.mkv", library)
        assertTrue(context, "Slow Horses/Series 2/" in context)
        assertTrue(context, "Slow.Horses.S02E01.mkv" in context)
        assertTrue(context, "Every top-level name in \"movies\"" in context)
    }

    @Test
    fun `a plan that escapes the library or loses its extension is refused`() {
        val name = "Film.2020.mkv"
        assertNull(PlanCheck.problem(plan(FilePlan.Action.MOVIE, "movies", "Film (2020)", "Film (2020).mkv"), name))
        assertNull(PlanCheck.problem(plan(FilePlan.Action.LEAVE, "", "", ""), name))
        listOf(
            plan(FilePlan.Action.MOVIE, "movies", "../etc", "x.mkv"),
            plan(FilePlan.Action.MOVIE, "movies", "/abs", "x.mkv"),
            plan(FilePlan.Action.MOVIE, "movies", "", "x.mkv"),
            plan(FilePlan.Action.MOVIE, "music", "Film", "x.mkv"),
            plan(FilePlan.Action.MOVIE, "movies", "Film", "a/x.mkv"),
            plan(FilePlan.Action.MOVIE, "movies", "Film", "Film (2020).mp4"),
        ).forEach { assertNotNull(it.toString(), PlanCheck.problem(it, name)) }
    }

    @Test
    fun `a folder name copied with a changed separator snaps to the folder that exists`() {
        val p = PlanCheck.snapped(plan(FilePlan.Action.EPISODE, "series", "slow.horses/Series-2", "x.mkv"), library)
        assertEquals("Slow Horses/Series 2", p.folder)
        val fresh = PlanCheck.snapped(
            plan(FilePlan.Action.EPISODE, "series", "Slow Horses/Season 05", "x.mkv"),
            library
        )
        assertEquals("Slow Horses/Season 05", fresh.folder)
    }

    @Test
    fun `a show's name written twice is taken once when the doubled folder does not exist`() {
        val p = PlanCheck.snapped(
            plan(FilePlan.Action.EPISODE, "series", "Slow Horses/Slow Horses/Series 2", "x.mkv"),
            library
        )
        assertEquals("Slow Horses/Series 2", p.folder)
    }

    @Test
    fun `a plan naming a different episode from the file is refused`() {
        val name = "Agatha_Christies_Poirot_S01E03_x265.mkv"
        assertNotNull(
            PlanCheck.problem(plan(FilePlan.Action.EPISODE, "series", "Poirot/s03", "Poirot_S03E03.mkv"), name)
        )
        assertNull(
            PlanCheck.problem(plan(FilePlan.Action.EPISODE, "series", "Poirot/s01", "Poirot - S01E03.mkv"), name)
        )
    }

    @Test
    fun `the context shows close folders from both libraries, whichever the name suggests`() {
        val context = Filing.context("01e06 Belief.avi", library, from = "Peep Show")
        assertTrue(context, "Peep Show/Peep Show S03/" in context)
        assertTrue(context, "Library \"movies\"" in context)
    }
}
