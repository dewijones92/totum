package com.dewijones92.totum.groups

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class FileGroupNewsStoreTest {

    @get:Rule
    val folder = TemporaryFolder()

    private val states = mapOf(
        "g1" to GroupNewsState(
            seenAt = 1_000L,
            freshBy = mapOf("chan" to listOf("v1", "v2")),
            notified = listOf("v1"),
            joined = mapOf("chan" to 500L),
        ),
        "g2" to GroupNewsState(),
    )

    @Test
    fun `what is saved is loaded back exactly`() = runTest {
        val file = folder.root.resolve("group-news.json")

        FileGroupNewsStore(file).save(states)

        assertEquals(states, FileGroupNewsStore(file).load())
    }

    @Test
    fun `with no file there is nothing to load`() = runTest {
        assertEquals(emptyMap<String, GroupNewsState>(), FileGroupNewsStore(folder.root.resolve("none.json")).load())
    }

    @Test
    fun `a second save replaces the first and leaves no temporary file behind`() = runTest {
        val file = folder.root.resolve("group-news.json")
        val store = FileGroupNewsStore(file)
        store.save(states)

        store.save(mapOf("g3" to GroupNewsState(seenAt = 7L)))

        assertEquals(mapOf("g3" to GroupNewsState(seenAt = 7L)), store.load())
        assertFalse(folder.root.resolve("group-news.json.next").exists())
    }

    @Test
    fun `a save that fails keeps the old file and does not throw`() = runTest {
        val file = folder.root.resolve("group-news.json")
        val store = FileGroupNewsStore(file)
        store.save(states)
        val locked = folder.newFolder("blocked")
        locked.resolve("occupied").writeText("x")

        FileGroupNewsStore(locked).save(mapOf("g3" to GroupNewsState()))

        assertEquals(states, store.load())
    }

    @Test
    fun `a field added later in the file is ignored rather than failing the load`() = runTest {
        val file = folder.newFile("group-news.json")
        file.writeText("""{"g1":{"seenAt":5,"somethingNew":true}}""")

        assertEquals(mapOf("g1" to GroupNewsState(seenAt = 5L)), FileGroupNewsStore(file).load())
    }
}
