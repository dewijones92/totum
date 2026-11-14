package com.dewijones92.totum.ui.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class SettingsRegistryTest {

    private val settingsSources: List<File> by lazy {
        val moduleDir = File(System.getProperty("user.dir"))
        File(moduleDir, "src/main/java/com/dewijones92/totum/ui/settings").listFiles { f -> f.extension == "kt" }
            .orEmpty().toList()
            .also { assertTrue("found no settings sources under $moduleDir", it.isNotEmpty()) }
    }

    private val anchored: Set<String> by lazy {
        val anchor = Regex("""(?:AnchoredRow|settingsRow)\(\s*(?:row\s*=\s*)?SettingsRow\.(\w+)""")
        settingsSources.flatMap { file -> anchor.findAll(file.readText()).map { it.groupValues[1] }.toList() }.toSet()
    }

    @Test
    fun `every searchable row on the main page is anchored, so picking it has somewhere to scroll`() {
        val unanchored = SettingsRow.entries.filter { it.page == null && it.name !in anchored }.map { it.name }

        assertEquals("rows with no AnchoredRow or settingsRow on the page", emptyList<String>(), unanchored)
    }

    @Test
    fun `every anchor names a row that exists, so nothing on the page is unsearchable`() {
        val names = SettingsRow.entries.map { it.name }.toSet()

        assertTrue(anchored.toString(), anchored.all { it in names })
        assertTrue("the scan found no anchors at all", anchored.size > 20)
    }

    @Test
    fun `no two rows share a name and a section, so a result is never ambiguous`() {
        val twins = SettingsRow.entries.groupBy { it.label to it.section }.filterValues { it.size > 1 }

        assertTrue(twins.values.joinToString { rows -> rows.joinToString { it.name } }, twins.isEmpty())
    }

    @Test
    fun `every row has a real label and section`() {
        SettingsRow.entries.forEach { row ->
            assertTrue("${row.name} label", row.label != 0)
            assertTrue("${row.name} section", row.section != 0)
        }
    }

    @Test
    fun `the keywords that help a search are lower case words`() {
        SettingsRow.entries.forEach { row ->
            assertEquals("${row.name} keywords", row.keywords.lowercase(), row.keywords)
        }
    }

    @Test
    fun `the second line of a result leaves out a section that only repeats the name`() {
        assertEquals("Playback", resultSecondLine("Skip silence", "Playback", null))
        assertNull(resultSecondLine("Backup", "backup", null))
        assertNull(resultSecondLine("  Backup ", "BACKUP", null))
        assertEquals("a summary", resultSecondLine("Backup", "Backup", "a summary"))
    }

    @Test
    fun `the second line joins section and summary with a middle dot`() {
        assertEquals("Queue · Rewinds a little", resultSecondLine("Rewind", "Queue", "Rewinds a little"))
        assertEquals("Queue", resultSecondLine("Rewind", "Queue", null))
    }
}
