package com.dewijones92.totum.diagnostics

import com.dewijones92.totum.common.Breadcrumbs
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class FlightRecorderTest {

    @get:Rule
    val temp = TemporaryFolder()

    private var clock = 1_800_000_000_000L
    private val dir: File by lazy { File(temp.root, "flight-recorder") }
    private val recorder by lazy { FlightRecorder(dir, appVersion = "0.1.920", now = { clock }) }

    private fun trail(count: Int, message: (Int) -> String = { "line $it" }) =
        (1..count).map { Breadcrumbs.Entry(clock - (count - it) * 1_000L, "cast", message(it), it.toLong()) }

    private fun kinds() = snapshots().map { it.name.substringAfter('-').removeSuffix(".json") }

    private fun details(report: JSONArray) =
        (0 until report.length()).map { report.getJSONObject(it).getString("detail") }

    private fun snapshots() = dir.listFiles { f -> f.extension == "json" }.orEmpty().sortedBy { it.name }

    @Test
    fun aSavedSnapshotNamesWhatHappenedWhenAndInWhichBuild() {
        recorder.save("cast-drop", "the TV left", trail(3))

        val file = snapshots().single()
        assertEquals("$clock-cast-drop.json", file.name)
        val saved = JSONObject(file.readText())
        assertEquals("cast-drop", saved.getString("kind"))
        assertEquals("the TV left", saved.getString("detail"))
        assertEquals("0.1.920", saved.getString("appVersion"))
        assertEquals(3, saved.getInt("trailLines"))
        assertEquals(3, saved.getInt("entriesKept"))
        assertEquals("line 3", saved.getJSONArray("events").getJSONObject(2).getString("message"))
        assertEquals("cast", saved.getJSONArray("events").getJSONObject(0).getString("tag"))
    }

    @Test
    fun theTrailIsSavedOldestFirstSoItReadsAsAStory() {
        recorder.save("cast-drop", "x", trail(4))

        val messages = JSONObject(snapshots().single().readText()).getJSONArray("events")
        val read = (0 until 4).map { messages.getJSONObject(it).getString("message") }
        assertEquals(listOf("line 1", "line 2", "line 3", "line 4"), read)
    }

    @Test
    fun aKindIsMadeSafeForAFileName() {
        recorder.save("Cast Drop!! (TV)", "x", trail(1))

        assertEquals("$clock-cast-drop-tv-.json", snapshots().single().name)
    }

    @Test
    fun theSameKindWithinTwoMinutesIsNotSavedAgainButAnotherKindIs() {
        recorder.save("cast-drop", "first", trail(1))
        clock += 119_000
        recorder.save("cast-drop", "again too soon", trail(1))
        recorder.save("stall-give-up", "a different kind", trail(1))

        assertEquals(listOf("cast-drop", "stall-give-up"), kinds())
        assertEquals("first", JSONObject(snapshots().first().readText()).getString("detail"))
    }

    @Test
    fun afterTwoMinutesTheSameKindIsSavedAgain() {
        recorder.save("cast-drop", "first", trail(1))
        clock += 120_000
        recorder.save("cast-drop", "second", trail(1))

        assertEquals(2, snapshots().size)
    }

    @Test
    fun aHugeTrailKeepsItsNewestLinesWithinTheBudgetAndCutsLongMessages() {
        val long = "x".repeat(2_000)
        recorder.save("cast-drop", "big", trail(3_000) { "$it $long" })

        val saved = JSONObject(snapshots().single().readText())
        val events = saved.getJSONArray("events")
        assertEquals(3_000, saved.getInt("trailLines"))
        assertTrue(saved.getInt("entriesKept") in 1 until 3_000)
        assertEquals(saved.getInt("entriesKept"), events.length())
        assertTrue(events.getJSONObject(events.length() - 1).getString("message").startsWith("3000 "))
        assertEquals(FlightRecorder.MAX_MESSAGE_CHARS, events.getJSONObject(0).getString("message").length)
        assertTrue(snapshots().single().length() < FlightRecorder.MAX_SNAPSHOT_CHARS * 2)
    }

    @Test
    fun onlyTheFiveNewestSnapshotsAreKept() {
        (1..8).forEach { n ->
            recorder.save("kind-$n", "n$n", trail(1))
            clock += 1_000
        }

        assertEquals((4..8).map { "kind-$it" }, kinds())
    }

    @Test
    fun aSnapshotOlderThanAWeekGoesWhenTheNextOneIsSaved() {
        dir.mkdirs()
        File(dir, "${clock - 8 * 24 * 3_600_000L}-old.json").writeText("{}")
        File(dir, "${clock - 6 * 24 * 3_600_000L}-recent.json").writeText("{}")

        recorder.save("cast-drop", "x", trail(1))

        assertEquals(listOf("recent", "cast-drop"), kinds())
    }

    @Test
    fun aHalfWrittenFileIsTidiedAway() {
        dir.mkdirs()
        File(dir, "123-cast-drop.tmp").writeText("half")

        recorder.save("stall-give-up", "x", trail(1))

        assertFalse(File(dir, "123-cast-drop.tmp").exists())
    }

    @Test
    fun theReportCarriesTheSnapshotsNewestFirstEachNamingItsFile() {
        recorder.save("kind-a", "a", trail(1))
        clock += 1_000
        recorder.save("kind-b", "b", trail(1))

        val report = FlightRecorder.forReport(dir)

        assertEquals(listOf("b", "a"), details(report))
        assertTrue(report.getJSONObject(0).getString("file").endsWith("-kind-b.json"))
    }

    @Test
    fun aSnapshotThatCannotBeReadIsLeftOutOfTheReportAndTheRestStillGo() {
        recorder.save("kind-a", "a", trail(1))
        File(dir, "${clock + 5}-broken.json").writeText("{ not json")

        val report = FlightRecorder.forReport(dir)

        assertEquals(listOf("a"), details(report))
    }

    @Test
    fun aSnapshotOverTheReportBudgetIsLeftOut() {
        dir.mkdirs()
        val pad = "x".repeat(FlightRecorder.MAX_REPORT_CHARS)
        File(dir, "${clock + 9}-huge.json").writeText("""{"detail":"huge","pad":"$pad"}""")
        recorder.save("kind-a", "a", trail(1))

        val report = FlightRecorder.forReport(dir)

        assertEquals(listOf("a"), details(report))
    }

    @Test
    fun noRecorderDirectoryIsNoSnapshotsAndNoCrash() {
        assertEquals(0, FlightRecorder.forReport(File(temp.root, "never-made")).length())
    }
}
